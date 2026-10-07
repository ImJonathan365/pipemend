package com.pipemend.pipeline.common;

import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.schema.SchemaLoadException;
import com.pipemend.pipeline.schema.SchemaLoader;
import com.pipemend.pipeline.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

@Configuration(proxyBeanMethods = false)
public class CoreConfiguration {

    static final String SCHEMA_DIR = "classpath:schemas/";
    static final String SCHEMA_FILE = "sales_transaction.v1.yaml";

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SchemaDefinition salesTransactionSchema(ResourceLoader resources) {
        return SchemaLoader.load(read(resources, SCHEMA_FILE), name -> read(resources, name));
    }

    @Bean
    Validator validator(SchemaDefinition schema, Clock clock) {
        return new Validator(schema, clock);
    }

    private static String read(ResourceLoader resources, String name) {
        try (InputStream in = resources.getResource(SCHEMA_DIR + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SchemaLoadException("cannot read " + SCHEMA_DIR + name, e);
        }
    }
}
