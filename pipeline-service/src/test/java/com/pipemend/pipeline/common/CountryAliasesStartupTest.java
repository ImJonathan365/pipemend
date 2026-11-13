package com.pipemend.pipeline.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipemend.pipeline.schema.SchemaLoadException;
import com.pipemend.pipeline.verifiers.CountryAliases;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** CountryAliases loads at startup: a broken aliases file stops the application, as a broken schema does. */
class CountryAliasesStartupTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(CoreConfiguration.class);

    @Test
    void theRealAliasesFileLoadsAtStartup() {
        runner.run(context -> assertThat(context).hasSingleBean(CountryAliases.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"Atlantis\": [\"AT\"]", "\"Austria\": [\"AU\"]\n  \"Australia\": [\"AU\"]"})
    void aCountryOutsideTheCatalogueOrAnAmbiguousAliasStopsTheApplication(String aliases) {
        String yaml = "version: 1\ncatalogue: countries.v1.txt\naliases:\n  " + aliases + "\n";

        runner.withClassLoader(new AliasesClassLoader(yaml)).run(context -> {
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

    /** Serves a hand-written aliases YAML in place of the real one; everything else comes from the parent. */
    private static final class AliasesClassLoader extends ClassLoader {

        private final String yaml;

        AliasesClassLoader(String yaml) {
            super(CountryAliasesStartupTest.class.getClassLoader());
            this.yaml = yaml;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (name.equals("schemas/" + CoreConfiguration.ALIASES_FILE)) {
                return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
            }
            return super.getResourceAsStream(name);
        }
    }
}
