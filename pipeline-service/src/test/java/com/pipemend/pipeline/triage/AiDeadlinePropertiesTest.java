package com.pipemend.pipeline.triage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** NFR-07 (docs/03 l.98): read timeout > deadline x 1000 + 2000, or the context does not start. */
class AiDeadlinePropertiesTest {

    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiDeadlineProperties.class)
    static class Binding {}

    @Test
    void nfr_07_theDefaultsOfDocs03Start() {
        context.withPropertyValues("pipemend.ai.read-timeout=30000ms", "pipemend.ai.request-deadline=25s")
                .run(started -> assertThat(started)
                        .hasNotFailed()
                        .getBean(AiDeadlineProperties.class)
                        .isEqualTo(new AiDeadlineProperties(Duration.ofSeconds(30), Duration.ofSeconds(25))));
    }

    @Test
    void nfr_07_aReadTimeoutOfExactlyDeadlinePlus2sDoesNotStart() {
        context.withPropertyValues("pipemend.ai.read-timeout=27000ms", "pipemend.ai.request-deadline=25s")
                .run(failed -> assertThat(failed)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("AI_CLIENT_READ_TIMEOUT_MS (27000 ms)"));
    }

    @Test
    void nfr_07_oneMillisecondMoreStarts() {
        context.withPropertyValues("pipemend.ai.read-timeout=27001ms", "pipemend.ai.request-deadline=25s")
                .run(started -> assertThat(started).hasNotFailed());
    }
}
