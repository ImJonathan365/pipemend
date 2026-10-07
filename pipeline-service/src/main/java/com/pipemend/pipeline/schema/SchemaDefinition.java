package com.pipemend.pipeline.schema;

import java.util.List;
import java.util.Set;

public record SchemaDefinition(String name, int version, Set<String> nullTokens, List<FieldSpec> fields) {

    public FieldSpec field(String fieldName) {
        return fields.stream()
                .filter(field -> field.name().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown field " + fieldName));
    }
}
