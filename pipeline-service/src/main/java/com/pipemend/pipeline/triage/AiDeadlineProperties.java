package com.pipemend.pipeline.triage;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The configuration invariant of NFR-07 (docs/03 l.98): the pipeline does not start unless its read timeout outlasts the
 * whole deadline of ai-service by 2 s. It binds read-timeout again, beside AiClientProperties, so binding fails.
 */
@ConfigurationProperties("pipemend.ai")
public record AiDeadlineProperties(Duration readTimeout, Duration requestDeadline) {

    static final Duration MARGIN = Duration.ofSeconds(2);

    public AiDeadlineProperties {
        Objects.requireNonNull(readTimeout);
        Objects.requireNonNull(requestDeadline);
        if (readTimeout.compareTo(requestDeadline.plus(MARGIN)) <= 0) {
            throw new IllegalArgumentException("AI_CLIENT_READ_TIMEOUT_MS (" + readTimeout.toMillis()
                    + " ms) must exceed AI_REQUEST_DEADLINE_SECONDS x 1000 + 2000 (" + requestDeadline.toMillis()
                    + " ms + 2000 ms), docs/03 NFR-07");
        }
    }
}
