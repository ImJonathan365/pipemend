package com.pipemend.pipeline.schema;

public record FieldSpec(String name, String sourceHeader, boolean required, FieldRule rule) {}
