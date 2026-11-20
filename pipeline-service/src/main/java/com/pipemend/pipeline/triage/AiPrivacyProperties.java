package com.pipemend.pipeline.triage;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI_REDACT_FIELDS and AI_SEND_FULL_RECORD (NFR-15, docs/04 section 8). The defaults live in application.yml. An empty
 * list redacts nothing (D235).
 */
@ConfigurationProperties("pipemend.ai.privacy")
public record AiPrivacyProperties(List<String> redactFields, boolean sendFullRecord) {

    public AiPrivacyProperties(@Nullable List<String> redactFields, boolean sendFullRecord) {
        this.redactFields = redactFields == null ? List.of() : List.copyOf(redactFields);
        this.sendFullRecord = sendFullRecord;
    }
}
