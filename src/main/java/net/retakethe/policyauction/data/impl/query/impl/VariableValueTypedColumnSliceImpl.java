package net.retakethe.policyauction.data.impl.query.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.prettyprint.hector.api.beans.ColumnSlice;
import me.prettyprint.hector.api.beans.HColumn;
import net.retakethe.policyauction.data.impl.query.api.UnresolvedVariableValueTypedColumn;
import net.retakethe.policyauction.data.impl.query.api.VariableValueTypedColumn;
import net.retakethe.policyauction.data.impl.query.api.VariableValueTypedColumnSlice;
import net.retakethe.policyauction.data.impl.schema.column.ColumnRange;
import net.retakethe.policyauction.data.impl.schema.column.NamedColumn;

/**
 * @author Nick Clarke
 */
public class VariableValueTypedColumnSliceImpl<N> implements VariableValueTypedColumnSlice<N> {

    private final ColumnSlice<N, Object> wrappedColumnSlice;
    private final List<UnresolvedVariableValueTypedColumn<N>> columns;

    public VariableValueTypedColumnSliceImpl(ColumnSlice<N, Object> wrappedColumnSlice) {
        this.wrappedColumnSlice = wrappedColumnSlice;

        List<HColumn<N, Object>> wrappedColumns = wrappedColumnSlice.getColumns();
        int size = wrappedColumns.size();
        columns = new ArrayList<UnresolvedVariableValueTypedColumn<N>>(size);

        for (HColumn<N, Object> wrappedColumn : wrappedColumns) {
            columns.add(new UnresolvedVariableValueTypedColumnImpl<N>(wrappedColumn));
        }
    }

    @Override
    public List<UnresolvedVariableValueTypedColumn<N>> getColumns() {
        return Collections.unmodifiableList(columns);
    }

    @Override
    public <V> VariableValueTypedColumn<N, V> getColumn(NamedColumn<?, N, V> column) {
        HColumn<N, Object> wrappedColumn = wrappedColumnSlice.getColumnByName(column.getName());
        if (wrappedColumn == null) {
            return null;
        }
        return new VariableValueTypedColumnImpl<N, V>(wrappedColumn, column.getValueSerializer());
    }

    @Override
    public <V> VariableValueTypedColumn<N, V> getColumn(ColumnRange<?, N, V> column, N columnName) {
        HColumn<N, Object> wrappedColumn = wrappedColumnSlice.getColumnByName(columnName);
        if (wrappedColumn == null) {
            return null;
        }
        return new VariableValueTypedColumnImpl<N, V>(wrappedColumn, column.getValueSerializer());
    }
}
