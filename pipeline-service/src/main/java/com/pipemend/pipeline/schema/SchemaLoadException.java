package com.pipemend.pipeline.schema;

public class SchemaLoadException extends RuntimeException {

    public SchemaLoadException(String message) {
        super(message);
    }

    public SchemaLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
