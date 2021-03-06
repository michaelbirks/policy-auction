package net.retakethe.policyauction.data.impl.schema.supercolumn;

import java.util.UUID;

import net.retakethe.policyauction.data.impl.query.api.Mutator;
import net.retakethe.policyauction.data.impl.query.api.SubcolumnMutator;
import net.retakethe.policyauction.data.impl.query.impl.MutatorInternal;
import net.retakethe.policyauction.data.impl.schema.family.SupercolumnFamily;
import net.retakethe.policyauction.data.impl.schema.timestamp.Timestamp;

/**
 * Cassandra supercolumns with fixed names.
 *
 * @param <K> the key type of the supercolumn family, e.g. {@link UUID} or {@link String}  or {@link Integer} etc.
 * @param <SN> the supercolumn name type, e.g. {@link UUID} or {@link String}  or {@link Integer} etc.
 * @param <N> the subcolumn name type of the column, e.g. {@link UUID} or {@link String}  or {@link Integer} etc.
 *
 * @author Nick Clarke
 */
public abstract class NamedSupercolumn<K, T extends Timestamp, SN, N> extends Supercolumn<K, T, SN, N> {

    private final SN name;

    protected NamedSupercolumn(SN name, SupercolumnFamily<K, T, SN, N> supercolumnFamily) {
        super(supercolumnFamily);
        this.name = name;
    }

    public SN getName() {
        return name;
    }

    public SubcolumnMutator<K, T, SN, N> createSubcolumnMutator(Mutator<K, T> m, K key) {
        return ((MutatorInternal<K, T>) m).createSubcolumnMutator(key, this, getName());
    }

    public void addSupercolumnDeletion(Mutator<K, T> m, K key) {
        ((MutatorInternal<K, T>) m).addSupercolumnDeletion(key, this, getName());
    }
}
