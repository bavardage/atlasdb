package com.palantir.atlasdb.sql.jdbc.results;

import java.util.Collection;

import com.google.protobuf.Message;
import com.palantir.atlasdb.table.description.ColumnValueDescription;
import com.palantir.atlasdb.table.description.ValueType;

public interface JdbcColumnMetadata {
    ColumnValueDescription.Format getFormat();

    ValueType getValueType();

    String getLabel();

    String getName();

    Message hydrateProto(byte[] val);

    @Override
    String toString();

    boolean isRowComp();  // part of row key
    boolean isNamedCol(); // named (not dynamic) column
    boolean isColComp();  // dynamic column components
    boolean isValueCol(); // dynamic column's value

    static boolean anyDynamicColumns(Collection<JdbcColumnMetadata> allCols) {
        return allCols.stream().anyMatch(JdbcColumnMetadata::isColComp);
    }
}
