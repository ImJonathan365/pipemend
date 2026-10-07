package com.pipemend.pipeline.schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class SchemaTestSupport {

    private SchemaTestSupport() {}

    public static String resource(String name) {
        try (InputStream in = SchemaTestSupport.class.getResourceAsStream("/schemas/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static SchemaDefinition realSchema() {
        return SchemaLoader.load(resource("sales_transaction.v1.yaml"), SchemaTestSupport::resource);
    }
}
