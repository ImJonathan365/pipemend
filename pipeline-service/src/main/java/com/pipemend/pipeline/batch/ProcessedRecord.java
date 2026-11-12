package com.pipemend.pipeline.batch;

import com.pipemend.pipeline.transform.SalesTransaction;
import com.pipemend.pipeline.triage.contract.Severity;
import com.pipemend.pipeline.validation.Violation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The single outcome of processing one record: it is loaded or it is quarantined, never both (NFR-06). */
public sealed interface ProcessedRecord {

    record Loaded(SalesTransaction transaction) implements ProcessedRecord {}

    /**
     * @param rawPayload the record as read, keyed by header; {@code {"_rawLine": ...}} for a malformed row
     * @param analysis only from an answer that passed the validator (docs/05 section 3.3)
     * @param aiRequestId set on every attempted call to ai-service, also a failed one
     */
    record Quarantined(
            UUID batchId,
            int sourceRowNumber,
            Map<String, String> rawPayload,
            List<Violation> violations,
            QuarantineReason reason,
            String summary,
            @Nullable AiAnalysis analysis,
            @Nullable UUID aiRequestId)
            implements ProcessedRecord {}

    /**
     * @param body the answer of ai-service as received
     * @param maxSeverity computed by the validator, never the one received
     */
    record AiAnalysis(String body, Severity maxSeverity) {}

    /** quarantine_reason values of docs/02 section 5. */
    enum QuarantineReason {
        AI_REVIEW_REQUIRED,
        POLICY_REJECTED,
        VERIFICATION_FAILED,
        REVALIDATION_FAILED,
        AI_UNAVAILABLE,
        AI_INVALID_RESPONSE,
        AI_BUDGET_EXCEEDED,
        PROCESSING_ERROR
    }
}
