package com.pipemend.pipeline.verifiers;

import com.pipemend.pipeline.validation.Whitespace;

/** TRIM (docs/02 section 4): proposed == strip(original), not empty and not the original itself. */
public final class TrimVerifier implements Verifier {

    @Override
    public VerificationResult verify(VerificationInput input) {
        String original = input.original();
        String proposed = input.proposed();
        if (original == null || proposed == null) {
            return VerificationResult.failure(VerificationCode.VALUE_MISMATCH, "TRIM needs a value to strip");
        }
        if (proposed.isEmpty() || proposed.equals(original) || !proposed.equals(Whitespace.strip(original))) {
            return VerificationResult.failure(
                    VerificationCode.VALUE_MISMATCH, "The proposed value is not the original without its outer spaces");
        }
        return VerificationResult.success();
    }
}
