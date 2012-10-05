package edu.washington.escience.myriad.parallel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import edu.washington.escience.myriad.Schema;
import edu.washington.escience.myriad.Type;
import edu.washington.escience.myriad.table._TupleBatch;

public class DupElim extends Operator {

  // private static final long serialVersionUID = 1L;

  private class IndexedTuple {
    int index;
    _TupleBatch tb;

    public IndexedTuple(_TupleBatch tb, int index) {
      this.tb = tb;
      this.index = index;
    }

    public boolean compareField(IndexedTuple another, int colIndx) {
      Type type = this.tb.inputSchema().getFieldType(colIndx);
      int rowIndx1 = this.index;
      int rowIndx2 = another.index;
      // System.out.println(rowIndx1 + " " + rowIndx2 + " " + colIndx + " " + type);
      if (type.equals(Type.INT_TYPE))
        return this.tb.getInt(colIndx, rowIndx1) == another.tb.getInt(colIndx, rowIndx2);
      if (type.equals(Type.DOUBLE_TYPE))
        return this.tb.getDouble(colIndx, rowIndx1) == another.tb.getDouble(colIndx, rowIndx2);
      if (type.equals(Type.STRING_TYPE))
        return this.tb.getString(colIndx, rowIndx1).equals(another.tb.getString(colIndx, rowIndx2));
      if (type.equals(Type.FLOAT_TYPE))
        return this.tb.getFloat(colIndx, rowIndx1) == another.tb.getFloat(colIndx, rowIndx2);
      if (type.equals(Type.BOOLEAN_TYPE))
        return this.tb.getBoolean(colIndx, rowIndx1) == another.tb.getBoolean(colIndx, rowIndx2);
      if (type.equals(Type.LONG_TYPE))
        return this.tb.getLong(colIndx, rowIndx1) == another.tb.getLong(colIndx, rowIndx2);
      return false;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof IndexedTuple))
        return false;
      IndexedTuple another = (IndexedTuple) o;
      if (!(this.tb.inputSchema().equals(another.tb.inputSchema())))
        return false;
      for (int i = 0; i < this.tb.inputSchema().numFields(); ++i)
        if (!compareField(another, i))
          return false;
      return true;
    }

    @Override
    public int hashCode() {
      return this.tb.hashCode(this.index);
    }
  }

  Operator child;
  Schema outputSchema;
  HashMap<Integer, List<IndexedTuple>> uniqueTuples;

  public DupElim(Schema outputSchema, Operator child) {
    this.outputSchema = outputSchema;
    this.child = child;
    this.uniqueTuples = new HashMap<Integer, List<IndexedTuple>>();
  }

  @Override
  protected _TupleBatch fetchNext() throws DbException {
    if (child.hasNext()) {
      _TupleBatch tb = child.next();

      for (int i = 0; i < tb.numInputTuples(); ++i) {
        IndexedTuple cntTuple = new IndexedTuple(tb, i);
        int cntHashCode = cntTuple.hashCode();
        // might need to check invalid | change to use outputTuples later
        if (uniqueTuples.get(cntHashCode) == null)
          uniqueTuples.put(cntHashCode, new ArrayList<IndexedTuple>());
        List<IndexedTuple> tupleList = uniqueTuples.get(cntHashCode);
        boolean unique = true;
        for (int j = 0; j < tupleList.size(); ++j) {
          IndexedTuple oldTuple = tupleList.get(j);
          if (cntTuple.equals(oldTuple)) {
            unique = false;
            break;
          }
        }
        System.out.println(i + " " + unique);
        if (unique)
          tupleList.add(cntTuple);
        else
          tb.remove(i);
      }
      return tb;
    }
    return null;
  }

  @Override
  public Operator[] getChildren() {
    return new Operator[] { this.child };
  }

  @Override
  public Schema getSchema() {
    return this.outputSchema;
  }

  @Override
  public void setChildren(Operator[] children) {
    this.child = children[0];
  }

  @Override
  public void open() throws DbException {
    if (child != null) {
      // for (Operator child : children)
      child.open();
    }
    super.open();
  }

}
