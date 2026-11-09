package com.pipemend.pipeline.triage;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** How to reach ai-service (docs/04 section 8, NFR-07). The defaults live in application.yml. */
@ConfigurationProperties("pipemend.ai")
public record AiClientProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout) {

    public AiClientProperties {
        Objects.requireNonNull(baseUrl);
        Objects.requireNonNull(connectTimeout);
        Objects.requireNonNull(readTimeout);
    }
}
