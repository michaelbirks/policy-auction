package net.retakethe.policyauction.data.impl.query;

import java.util.List;

import me.prettyprint.cassandra.model.ExecutionResult;
import me.prettyprint.cassandra.model.QueryResultImpl;
import me.prettyprint.hector.api.Keyspace;
import me.prettyprint.hector.api.Serializer;
import me.prettyprint.hector.api.beans.OrderedRows;
import me.prettyprint.hector.api.factory.HFactory;
import me.prettyprint.hector.api.query.QueryResult;
import me.prettyprint.hector.api.query.RangeSlicesQuery;
import net.retakethe.policyauction.data.impl.schema.ColumnFamily;
import net.retakethe.policyauction.data.impl.schema.ColumnRange;
import net.retakethe.policyauction.data.impl.schema.NamedColumn;

/**
 * @author Nick Clarke
 */
public class VariableValueTypedRangeSlicesQueryImpl<K, N> implements VariableValueTypedRangeSlicesQuery<K, N> {

    private final RangeSlicesQuery<K, N, Object> wrappedQuery;

    public VariableValueTypedRangeSlicesQueryImpl(Keyspace ks, ColumnFamily<K> cf, List<NamedColumn<K, N, ?>> columns) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("At least one column is required");
        }

        NamedColumn<K, N, ?> firstColumn = columns.get(0);
        Serializer<N> nameSerializer = firstColumn.getNameSerializer();

        N[] columnNames = QueryFactory.getColumnNamesUnresolved(cf, columns);

        wrappedQuery = HFactory.createRangeSlicesQuery(ks, cf.getKeySerializer(),
                    nameSerializer, new DummySerializer<Object>())
                .setColumnFamily(cf.getName())
                .setColumnNames(columnNames);
    }

    public VariableValueTypedRangeSlicesQueryImpl(Keyspace ks, ColumnFamily<K> cf, ColumnRange<K, N, ?> columnRange,
            N start, N finish, boolean reversed, int count) {
        QueryFactory.checkColumnRangeBelongsToColumnFamily(cf, columnRange);

        wrappedQuery = HFactory.createRangeSlicesQuery(ks, cf.getKeySerializer(),
                    columnRange.getNameSerializer(), new DummySerializer<Object>())
                .setColumnFamily(cf.getName())
                .setRange(start, finish, reversed, count);
    }

    @Override
    public QueryResult<VariableValueTypedOrderedRows<K, N>> execute() {
        QueryResult<OrderedRows<K, N, Object>> wrappedResult = wrappedQuery.execute();

        return new QueryResultImpl<VariableValueTypedOrderedRows<K, N>>(
                new ExecutionResult<VariableValueTypedOrderedRows<K, N>>(
                        new VariableValueTypedOrderedRowsImpl<K, N>(wrappedResult.get()),
                        wrappedResult.getExecutionTimeMicro(),
                        wrappedResult.getHostUsed()),
                this);
    }

    @Override
    public VariableValueTypedRangeSlicesQuery<K, N> setKeys(K start, K end) {
        wrappedQuery.setKeys(start, end);
        return this;
    }

    @Override
    public VariableValueTypedRangeSlicesQuery<K, N> setRowCount(int rowCount) {
        wrappedQuery.setRowCount(rowCount);
        return this;
    }

    @Override
    public VariableValueTypedRangeSlicesQuery<K, N> setReturnKeysOnly() {
        wrappedQuery.setReturnKeysOnly();
        return this;
    }
}