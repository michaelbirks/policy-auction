package net.retakethe.policyauction.data.impl.schema.family;

import java.util.List;
import java.util.UUID;

import me.prettyprint.hector.api.Serializer;
import net.retakethe.policyauction.data.impl.KeyspaceManager;
import net.retakethe.policyauction.data.impl.query.QueryFactory;
import net.retakethe.policyauction.data.impl.query.api.VariableValueTypedSuperSliceQuery;
import net.retakethe.policyauction.data.impl.schema.Schema.SchemaKeyspace;
import net.retakethe.policyauction.data.impl.schema.supercolumn.NamedSupercolumn;

/**
 * Schema definition and query creation for Cassandra Column Families.
 *
 * @param <K> the row key type of the column family, e.g. {@link UUID} or {@link String} or {@link Integer} etc.
 * @param <SN> the supercolumn name type, e.g. {@link UUID} or {@link String} or {@link Integer} etc.
 * @param <N> the subcolumn name type, e.g. {@link UUID} or {@link String} or {@link Integer} etc.
 *
 * @author Nick Clarke
 */
public abstract class SupercolumnFamily<K, SN, N> extends BaseColumnFamily<K> {

    private final Class<SN> supercolumnNameType;
    private final Serializer<SN> supercolumnNameSerializer;
    private final Class<N> subcolumnNameType;
    private final Serializer<N> subcolumnNameSerializer;

    protected SupercolumnFamily(SchemaKeyspace keyspace, String name, Class<K> keyType, Serializer<K> keySerializer,
            Class<SN> supercolumnNameType, Serializer<SN> supercolumnNameSerializer,
            Class<N> subcolumnNameType, Serializer<N> subcolumnNameSerializer) {
        super(keyspace, name, keyType, keySerializer);
        this.supercolumnNameType = supercolumnNameType;
        this.supercolumnNameSerializer = supercolumnNameSerializer;
        this.subcolumnNameType = subcolumnNameType;
        this.subcolumnNameSerializer = subcolumnNameSerializer;
    }

    public Class<SN> getSupercolumnNameType() {
        return this.supercolumnNameType;
    }

    public Serializer<SN> getSupercolumnNameSerializer() {
        return this.supercolumnNameSerializer;
    }

    public Class<N> getSubcolumnNameType() {
        return this.subcolumnNameType;
    }

    public Serializer<N> getSubcolumnNameSerializer() {
        return this.subcolumnNameSerializer;
    }

    public VariableValueTypedSuperSliceQuery<K, SN, N> createVariableValueTypedSuperSliceQuery(
            KeyspaceManager keyspaceManager,
            List<NamedSupercolumn<K, SN, N>> supercolumns, K key) {
        return QueryFactory.createVariableValueTypedSuperSliceQuery(keyspaceManager, this, supercolumns, key);
    }
}
