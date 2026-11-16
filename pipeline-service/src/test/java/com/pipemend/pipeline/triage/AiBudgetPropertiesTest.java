package com.pipemend.pipeline.triage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** AI_MAX_CALLS_PER_BATCH (AC-08.6): a whole number of at least 1, or the context does not start. */
class AiBudgetPropertiesTest {

    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiBudgetProperties.class)
    static class Binding {}

    @Test
    void ac_08_6_theDefaultOfApplicationYmlIs500() {
        context.withInitializer(new ConfigDataApplicationContextInitializer())
                .run(started -> assertThat(started)
                        .hasNotFailed()
                        .getBean(AiBudgetProperties.class)
                        .isEqualTo(new AiBudgetProperties(500)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void ac_08_6_aCapUnder1DoesNotStart(String cap) {
        context.withPropertyValues("pipemend.ai.max-calls-per-batch=" + cap)
                .run(failed -> assertThat(failed)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessage("AI_MAX_CALLS_PER_BATCH must be at least 1, got " + cap + " (AC-08.6)"));
    }

    @Test
    void ac_08_6_aMissingCapDoesNotStart() {
        context.run(failed -> assertThat(failed).hasFailed());
    }

    @Test
    void ac_08_6_aCapOf1Starts() {
        context.withPropertyValues("pipemend.ai.max-calls-per-batch=1")
                .run(started -> assertThat(started).hasNotFailed());
    }
}
