package com.pipemend.pipeline.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipemend.pipeline.schema.SchemaLoadException;
import com.pipemend.pipeline.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CoreConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(CoreConfiguration.class);

    @Test
    void wiresTheValidatorFromTheClasspathSchema() {
        runner.run(context -> assertThat(context).hasSingleBean(Validator.class));
    }

    @Test
    void ac_06_5_anInvalidSchemaStopsTheApplication() {
        runner.withClassLoader(new BrokenSchemaClassLoader()).run(context -> {
            assertThat(context).hasFailed();
            assertThat(causes(context.getStartupFailure())).hasAtLeastOneElementOfType(SchemaLoadException.class);
        });
    }

    private static List<Throwable> causes(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }

    /** Serves a malformed schema YAML in place of the real one; everything else comes from the parent. */
    private static final class BrokenSchemaClassLoader extends ClassLoader {

        BrokenSchemaClassLoader() {
            super(CoreConfigurationTest.class.getClassLoader());
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (name.equals("schemas/" + CoreConfiguration.SCHEMA_FILE)) {
                return new ByteArrayInputStream("fields: [unclosed".getBytes(StandardCharsets.UTF_8));
            }
            return super.getResourceAsStream(name);
        }
    }
}
