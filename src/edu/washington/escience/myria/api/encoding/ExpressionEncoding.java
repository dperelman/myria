package edu.washington.escience.myria.api.encoding;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;

import edu.washington.escience.myria.Schema;
import edu.washington.escience.myria.Type;
import edu.washington.escience.myria.expression.Expression;
import edu.washington.escience.myria.expression.ExpressionOperator;

/**
 * Holds the AST and output name.
 */
public final class ExpressionEncoding extends MyriaApiEncoding {
  @JsonProperty
  private final String outputName;

  @JsonProperty
  private final ExpressionOperator rootExpressionOperator;

  private static final ImmutableList<String> requiredFields = ImmutableList.of("outputName", "rootExpressionOperator");

  public ExpressionEncoding() {
    outputName = null;
    rootExpressionOperator = null;
  }

  public ExpressionEncoding(String outputName, ExpressionOperator rootExpressionOperator) {
    this.outputName = outputName;
    this.rootExpressionOperator = rootExpressionOperator;
  }

  @Override
  protected List<String> getRequiredFields() {
    return requiredFields;
  }

  /**
   * @param schema the input schema
   * @return the type of the output of the expression
   */
  public Type getOutputType(Schema schema) {
    return rootExpressionOperator.getOutputType(schema);
  }

  public Expression construct() {
    return new Expression(outputName, rootExpressionOperator.getJavaString(), ImmutableList
        .copyOf(rootExpressionOperator.getVariables()), this);
  }
}