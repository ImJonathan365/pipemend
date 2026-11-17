package com.pipemend.pipeline.verifiers;

/**
 * Recomputes one proposed correction with deterministic code and rejects it unless the result is identical, or when the
 * input admits more than one reasonable reading (FR-10, ADR-0008). The confidence of the AI never replaces it.
 */
public interface Verifier {

    VerificationResult verify(VerificationInput input);
}
