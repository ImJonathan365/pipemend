package com.pipemend.pipeline.verifiers;

import com.pipemend.pipeline.triage.contract.TriageResponse;
import org.jspecify.annotations.Nullable;

/**
 * @param original the receivedValue the pipeline sent, never the originalValue the AI echoed back
 * @param correction what the AI proposed: operation, proposedValue and parameters
 */
public record VerificationInput(
        String field, @Nullable String original, TriageResponse.Correction correction, double confidence) {

    public @Nullable String proposed() {
        return correction.proposedValue();
    }
}
