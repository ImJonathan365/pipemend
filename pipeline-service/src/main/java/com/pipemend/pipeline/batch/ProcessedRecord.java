package com.pipemend.pipeline.batch;

import com.pipemend.pipeline.transform.SalesTransaction;
import com.pipemend.pipeline.validation.Violation;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The single outcome of processing one record: it is loaded or it is quarantined, never both (NFR-06). */
public sealed interface ProcessedRecord {

    record Loaded(SalesTransaction transaction) implements ProcessedRecord {}

    /**
     * @param rawPayload the record as read, keyed by header; {@code {"_rawLine": ...}} for a malformed row
     */
    record Quarantined(
            UUID batchId,
            int sourceRowNumber,
            Map<String, String> rawPayload,
            List<Violation> violations,
            QuarantineReason reason,
            String summary)
            implements ProcessedRecord {}

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
