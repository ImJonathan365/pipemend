package com.pipemend.pipeline.triage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI_MAX_CALLS_PER_BATCH (AC-08.6, docs/04 section 8). The default lives in application.yml. */
@ConfigurationProperties("pipemend.ai")
public record AiBudgetProperties(int maxCallsPerBatch) {

    public AiBudgetProperties {
        if (maxCallsPerBatch < 1) {
            throw new IllegalArgumentException(
                    "AI_MAX_CALLS_PER_BATCH must be at least 1, got " + maxCallsPerBatch + " (AC-08.6)");
        }
    }
}
