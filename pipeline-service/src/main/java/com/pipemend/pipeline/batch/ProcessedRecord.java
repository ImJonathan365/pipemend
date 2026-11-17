package com.pipemend.pipeline.batch;

import com.pipemend.pipeline.policy.AppliedCorrection;
import com.pipemend.pipeline.policy.CorrectionApplier;
import com.pipemend.pipeline.policy.PolicyDecision;
import com.pipemend.pipeline.transform.SalesTransaction;
import com.pipemend.pipeline.transform.SalesTransaction.LoadOrigin;
import com.pipemend.pipeline.triage.contract.Severity;
import com.pipemend.pipeline.validation.Violation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The single outcome of processing one record: it is loaded or it is quarantined, never both (NFR-06). */
public sealed interface ProcessedRecord {

    /**
     * Not a record, so that an AUTO_CORRECTED row can only come from a {@link CorrectionApplier.Corrected}, the result
     * of the policy, every verifier and revalidation (D171).
     */
    final class Loaded implements ProcessedRecord {

        private final SalesTransaction transaction;
        private final List<AppliedCorrection> corrections;

        private Loaded(SalesTransaction transaction, List<AppliedCorrection> corrections, LoadOrigin expected) {
            if (transaction.loadOrigin() != expected) {
                throw new IllegalArgumentException(
                        "Expected load_origin " + expected + ", got " + transaction.loadOrigin());
            }
            this.transaction = transaction;
            this.corrections = corrections;
        }

        public static Loaded direct(SalesTransaction transaction) {
            return new Loaded(transaction, List.of(), LoadOrigin.DIRECT);
        }

        /** @param transaction transformed from {@code corrected.fields()} */
        public static Loaded autoCorrected(SalesTransaction transaction, CorrectionApplier.Corrected corrected) {
            return new Loaded(transaction, corrected.corrections(), LoadOrigin.AUTO_CORRECTED);
        }

        public SalesTransaction transaction() {
            return transaction;
        }

        /** One per corrected field (AC-10.3); empty for a DIRECT row. */
        public List<AppliedCorrection> corrections() {
            return corrections;
        }
    }

    /**
     * @param rawPayload the record as read, keyed by header; {@code {"_rawLine": ...}} for a malformed row
     * @param analysis only from an answer that passed the validator (docs/05 section 3.3)
     * @param aiRequestId set on every attempted call to ai-service, also a failed one
     * @param policyDecision null unless the policy evaluated the record (docs/05 section 3.3)
     */
    record Quarantined(
            UUID batchId,
            int sourceRowNumber,
            Map<String, String> rawPayload,
            List<Violation> violations,
            QuarantineReason reason,
            String summary,
            @Nullable AiAnalysis analysis,
            @Nullable UUID aiRequestId,
            @Nullable PolicyDecision policyDecision)
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
