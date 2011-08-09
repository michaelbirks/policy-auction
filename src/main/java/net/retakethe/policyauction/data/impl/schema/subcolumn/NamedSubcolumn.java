package net.retakethe.policyauction.data.impl.schema.subcolumn;

import me.prettyprint.hector.api.Serializer;
import net.retakethe.policyauction.data.impl.schema.supercolumn.Supercolumn;

public interface NamedSubcolumn<K, SN, N, V> {

    Supercolumn<K, SN, N> getSupercolumn();

    Serializer<V> getValueSerializer();

    N getName();
}