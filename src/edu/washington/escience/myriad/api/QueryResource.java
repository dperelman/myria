package edu.washington.escience.myriad.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import edu.washington.escience.myriad.RelationKey;
import edu.washington.escience.myriad.Schema;
import edu.washington.escience.myriad.Type;
import edu.washington.escience.myriad.coordinator.catalog.CatalogException;
import edu.washington.escience.myriad.operator.LocalJoin;
import edu.washington.escience.myriad.operator.Operator;
import edu.washington.escience.myriad.operator.SQLiteInsert;
import edu.washington.escience.myriad.operator.SQLiteQueryScan;
import edu.washington.escience.myriad.parallel.CollectConsumer;
import edu.washington.escience.myriad.parallel.CollectProducer;
import edu.washington.escience.myriad.parallel.Exchange.ExchangePairID;
import edu.washington.escience.myriad.parallel.PartitionFunction;
import edu.washington.escience.myriad.parallel.RoundRobinPartitionFunction;
import edu.washington.escience.myriad.parallel.ShuffleConsumer;
import edu.washington.escience.myriad.parallel.ShuffleProducer;
import edu.washington.escience.myriad.parallel.SingleFieldHashPartitionFunction;

/**
 * Class that handles queries.
 * 
 * @author dhalperi
 */
@Path("/query")
public final class QueryResource {
  /** The logger for this class. */
  private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(QueryResource.class.getName());

  /**
   * For now, simply echoes back its input.
   * 
   * @param userData the payload of the POST request itself.
   * @param uriInfo the URI of the current request.
   * @return the URI of the created query.
   */
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response postNewQuery(final Map<?, ?> userData, @Context final UriInfo uriInfo) {
    try {
      /* Must contain the raw data, the logical_ra, and the query_plan. */
      if (!userData.containsKey("raw_datalog") || !userData.containsKey("logical_ra")
          || !userData.containsKey("query_plan")) {
        LOGGER.warn("required fields: raw_datalog, logical_ra, and query_plan");
        throw new WebApplicationException(Response.status(Status.BAD_REQUEST).entity(
            "required fields: raw_datalog, logical_ra, and query_plan").build());
      }
      /* Deserialize the three arguments we need */
      final String rawQuery = (String) userData.get("raw_datalog");
      final String logicalRa = (String) userData.get("logical_ra");
      Map<Integer, Operator[]> queryPlan = deserializeJsonQueryPlan(userData.get("query_plan"));

      Set<Integer> usingWorkers = new HashSet<Integer>();
      usingWorkers.addAll(queryPlan.keySet());
      /* Remove the server plan if present */
      usingWorkers.remove(0);
      /* Make sure that the requested workers are alive. */
      if (!MasterApiServer.getMyriaServer().getAliveWorkers().containsAll(usingWorkers)) {
        /* Throw a 503 (Service Unavailable) */
        throw new WebApplicationException(Response.status(Status.SERVICE_UNAVAILABLE).build());
      }

      /* Start the query, and get its Server-assigned Query ID */
      final Long queryId = MasterApiServer.getMyriaServer().startQuery(rawQuery, logicalRa, queryPlan);
      /* In the response, tell the client what ID this query was assigned. */
      UriBuilder queryUri = uriInfo.getAbsolutePathBuilder();
      return Response.created(queryUri.path("query-" + queryId.toString()).build()).build();
    } catch (WebApplicationException e) {
      throw e;
    } catch (Exception e) {
      LOGGER.warn(e.toString());
      throw new WebApplicationException(Response.status(Status.BAD_REQUEST).entity(e.getMessage()).build());
    }
  }

  /**
   * Deserialize the mapping of worker subplans to workers.
   * 
   * @param jsonQuery a JSON object that contains a Myria query plan.
   * @return the query plan.
   * @throws Exception in the event of a bad plan.
   */
  private static Map<Integer, Operator[]> deserializeJsonQueryPlan(final Object jsonQuery) throws Exception {
    /* Better be a map */
    if (!(jsonQuery instanceof Map)) {
      throw new ClassCastException("argument is not a Map");
    }
    Map<?, ?> jsonQueryPlan = (Map<?, ?>) jsonQuery;
    Map<Integer, Operator[]> ret = new HashMap<Integer, Operator[]>();
    for (Entry<?, ?> entry : jsonQueryPlan.entrySet()) {
      Integer workerId = Integer.parseInt((String) entry.getKey());
      Operator[] workerPlan = deserializeJsonLocalPlans(entry.getValue());
      ret.put(workerId, workerPlan);
    }
    return ret;
  }

  private static Operator[] deserializeJsonLocalPlans(Object jsonLocalPlanList) throws Exception {
    /* Better be a List */
    if (!(jsonLocalPlanList instanceof List)) {
      throw new ClassCastException("argument is not a List of Operator definitions.");
    }
    List<?> localPlanList = (List<?>) jsonLocalPlanList;
    Operator[] ret = new Operator[localPlanList.size()];
    int i = 0;
    for (Object o : localPlanList) {
      ret[i++] = deserializeJsonLocalPlan(o);
    }
    return ret;
  }

  private static Operator deserializeJsonLocalPlan(Object jsonLocalPlan) throws Exception {
    /* Better be a List */
    if (!(jsonLocalPlan instanceof List)) {
      throw new ClassCastException("argument is not a List of Operator definitions.");
    }
    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> jsonOperators = (List<Map<String, Object>>) jsonLocalPlan;
    /* Better have at least one operator. */
    if (jsonOperators.isEmpty()) {
      throw new IOException("worker plan must contain at least one operator.");
    }
    final Map<String, Operator> operators = new HashMap<String, Operator>();
    Operator op = null;
    for (Map<String, Object> jsonOperator : jsonOperators) {
      /* Better have an operator name. */
      String opName = (String) jsonOperator.get("op_name");
      Objects.requireNonNull(opName, "all Operators must have an op_name defined");
      op = deserializeJsonOperator(jsonOperator, operators);
      operators.put(opName, op);
    }
    return op;
  }

  private static Operator deserializeJsonOperator(final Map<String, Object> jsonOperator,
      final Map<String, Operator> operators) throws Exception {
    /* Better have an operator type */
    String opType = (String) jsonOperator.get("op_type");
    Objects.requireNonNull(opType, "all Operators must have an op_type defined");

    /* Generic variable names */
    String childName;
    String child2Name;
    String userName;
    String programName;
    String relationName;
    Operator child;
    Operator child2;

    /* Do the case-by-case work. */
    switch (opType) {

      case "SQLiteInsert":
        childName = deserializeString(jsonOperator, "arg_child");
        child = operators.get(childName);
        Objects.requireNonNull(child, "SQLiteInsert child Operator " + childName + " not previously defined");
        userName = deserializeString(jsonOperator, "arg_user_name");
        programName = deserializeString(jsonOperator, "arg_program_name");
        relationName = deserializeString(jsonOperator, "arg_relation_name");
        String overwriteString = deserializeOptionalField(jsonOperator, "arg_overwrite_table");
        Boolean overwrite = Boolean.FALSE;
        if (overwriteString != null) {
          overwrite = Boolean.parseBoolean(overwriteString);
        }
        return new SQLiteInsert(child, RelationKey.of(userName, programName, relationName), null, null, overwrite);

      case "LocalJoin":
        /* Child 1 */
        childName = deserializeString(jsonOperator, "arg_child1");
        int[] child1columns = deserializeIntArray(jsonOperator, "arg_columns1", false);
        /* Child 2 arguments */
        child2Name = deserializeString(jsonOperator, "arg_child2");
        int[] child2columns = deserializeIntArray(jsonOperator, "arg_columns2", false);
        /* Mutual checks */
        Preconditions.checkState(child1columns.length == child2columns.length,
            "arg_columns1 and arg_columns2 must have the same length!");

        /* Find the operators. */
        child = operators.get(childName);
        Objects.requireNonNull(child, "LocalJoin child Operator " + childName + " not previously defined");
        child2 = operators.get(child2Name);
        Objects.requireNonNull(child2, "LocalJoin child2 Operator " + child2Name + " not previously defined");

        /* Get the optional arguments. */
        int[] child1select = deserializeIntArray(jsonOperator, "arg_select1", true);
        int[] child2select = deserializeIntArray(jsonOperator, "arg_select2", true);
        if ((child1select != null) && (child2select != null)) {
          return new LocalJoin(child, child2, child1columns, child2columns, child1select, child2select);
        }
        if ((child1select == null) && (child2select == null)) {
          return new LocalJoin(child, child2, child1columns, child2columns);
        }
        throw new IllegalArgumentException(
            "LocalJoin: either both or neither of arg_select1 and arg_select2 must be specified");

      case "SQLiteScan": {
        userName = deserializeString(jsonOperator, "arg_user_name");
        programName = deserializeString(jsonOperator, "arg_program_name");
        relationName = deserializeString(jsonOperator, "arg_relation_name");
        RelationKey relationKey = RelationKey.of(userName, programName, relationName);
        Schema schema;
        try {
          schema = MasterApiServer.getMyriaServer().getSchema(relationKey);
        } catch (final CatalogException e) {
          /* Throw a 500 (Internal Server Error) */
          throw new WebApplicationException(Response.status(Status.INTERNAL_SERVER_ERROR).build());
        }
        if (schema == null) {
          throw new IOException("Specified relation " + relationKey + " does not exist.");
        }
        return new SQLiteQueryScan(null, "SELECT * from " + relationKey, schema);
      }

      case "ShuffleConsumer": {
        Schema schema = deserializeSchema(jsonOperator, "arg_schema");
        int[] workerIDs = deserializeIntArray(jsonOperator, "arg_workerIDs", false);
        ExchangePairID operatorID =
            ExchangePairID.fromExisting(Long.parseLong(deserializeString(jsonOperator, "arg_operatorID")));
        return new ShuffleConsumer(schema, operatorID, workerIDs);
      }

      case "CollectConsumer": {
        Schema schema = deserializeSchema(jsonOperator, "arg_schema");
        int[] workerIDs = deserializeIntArray(jsonOperator, "arg_workerIDs", false);
        ExchangePairID operatorID =
            ExchangePairID.fromExisting(Long.parseLong(deserializeString(jsonOperator, "arg_operatorID")));
        return new CollectConsumer(schema, operatorID, workerIDs);
      }

      case "ShuffleProducer": {
        int[] workerIDs = deserializeIntArray(jsonOperator, "arg_workerIDs", false);
        PartitionFunction<?, ?> pf = deserializePF(jsonOperator, "arg_pf", workerIDs.length);
        ExchangePairID operatorID =
            ExchangePairID.fromExisting(Long.parseLong(deserializeString(jsonOperator, "arg_operatorID")));

        childName = deserializeString(jsonOperator, "arg_child");
        child = operators.get(childName);
        Objects.requireNonNull(child, "ShuffleProducer child Operator " + childName + " not previously defined");
        return new ShuffleProducer(child, operatorID, workerIDs, pf);
      }

      case "CollectProducer": {
        int workerID = Integer.parseInt(deserializeString(jsonOperator, "arg_workerID"));
        ExchangePairID operatorID =
            ExchangePairID.fromExisting(Long.parseLong(deserializeString(jsonOperator, "arg_operatorID")));
        childName = deserializeString(jsonOperator, "arg_child");
        child = operators.get(childName);
        Objects.requireNonNull(child, "CollectProducer child Operator " + childName + " not previously defined");
        return new CollectProducer(child, operatorID, workerID);
      }

      default:
        throw new RuntimeException("Not implemented deserializing Operator of type " + opType);
    }
  }

  /**
   * Helper function to deserialize a String.
   * 
   * @param map the JSON map.
   * @param field the name of the field.
   * @return the String value of the field.
   * @throws NullPointerException if the field is not present.
   */
  private static String deserializeString(final Map<String, Object> map, final String field) {
    Object ret = map.get(field);
    Objects.requireNonNull(ret, "missing field: " + field);
    return (String) ret;
  }

  /**
   * Helper function to deserialize an optional String.
   * 
   * @param map the JSON map.
   * @param field the name of the field.
   * @return the String value of the field, or null if the field is not present.
   */
  private static String deserializeOptionalField(final Map<String, Object> map, final String field) {
    Object ret = map.get(field);
    return (String) ret;
  }

  /**
   * Helper function to deserialize an array of Integers.
   * 
   * @param map the JSON map.
   * @param field the field containing the list.
   * @param optional whether the field is optional, or an IllegalArgumentException should be thrown.
   * @return the list of integers stored in field, or null if the field is missing and optional is true.
   */
  private static int[] deserializeIntArray(final Map<String, Object> map, final String field, final boolean optional) {
    List<?> list = (List<?>) map.get(field);
    if (list == null) {
      if (optional) {
        return null;
      }
      Preconditions.checkArgument(false, "mandatory field " + field + " missing");
    }
    int[] ret = new int[list.size()];
    int count = 0;
    for (final Object o : list) {
      ret[count++] = Integer.parseInt((String) o);
    }
    return ret;
  }

  /**
   * Helper function to deserialize a schema.
   * 
   * @param map the JSON map.
   * @param field the field containing the list.
   * @param optional whether the field is optional, or an IllegalArgumentException should be thrown.
   * @return the schema, or null if the field is missing and optional is true.
   */
  private static Schema deserializeSchema(final Map<String, Object> map, final String field) {
    Schema schema;
    LinkedHashMap<?, ?> tmp = (LinkedHashMap<?, ?>) map.get(field);
    List<String> tmpTypes = (List<String>) tmp.get("column_types");
    List<Type> types = new ArrayList<Type>();

    for (String s : tmpTypes) {
      switch (s) {
        case "INT_TYPE":
          types.add(Type.INT_TYPE);
          break;
        case "FLOAT_TYPE":
          types.add(Type.FLOAT_TYPE);
          break;
        case "DOUBLE_TYPE":
          types.add(Type.DOUBLE_TYPE);
          break;
        case "BOOLEAN_TYPE":
          types.add(Type.BOOLEAN_TYPE);
          break;
        case "STRING_TYPE":
          types.add(Type.STRING_TYPE);
          break;
        case "LONG_TYPE":
          types.add(Type.LONG_TYPE);
          break;
      }
    }
    List<String> names = (List<String>) tmp.get("column_names");
    schema = Schema.of(types, names);
    // schema = objectMapper.readValue((String) (map.get(field)), Schema.class);
    if (schema == null) {
      Preconditions.checkArgument(false, "mandatory field " + field + " missing");
    }
    return schema;
  }

  /**
   * Helper function to deserialize a partition function.
   * 
   * @param map the JSON map.
   * @param field the field containing the list.
   * @param optional whether the field is optional, or an IllegalArgumentException should be thrown.
   * @return the schema, or null if the field is missing and optional is true.
   */
  private static PartitionFunction<?, ?> deserializePF(final Map<String, Object> map, final String field,
      final int numWorker) {
    List<?> list = (List<?>) map.get(field);
    if (list == null) {
      Preconditions.checkArgument(false, "mandatory field " + field + " missing");
    }
    if (list.size() == 1) {
      Preconditions.checkArgument(((String) list.get(0)).equals("RoundRobin"), "unknown partition function "
          + (String) list.get(0));
      return new RoundRobinPartitionFunction(numWorker);
    } else if (list.size() == 2) {
      Preconditions.checkArgument(((String) list.get(0)).equals("SingleFieldHash"), "unknown partition function "
          + (String) list.get(0));
      int tmp = Integer.parseInt((String) list.get(1));
      SingleFieldHashPartitionFunction pf = new SingleFieldHashPartitionFunction(numWorker);
      pf.setAttribute(SingleFieldHashPartitionFunction.FIELD_INDEX, tmp);
      return pf;
    } else {
      Preconditions.checkArgument(false, "unknown partition function " + list);
    }
    return null;
  }
}
