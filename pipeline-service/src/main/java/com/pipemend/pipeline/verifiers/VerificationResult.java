package com.pipemend.pipeline.verifiers;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The verification part of policy_decision (docs/05 section 5). The detail is a fixed text and never quotes a value of
 * the record.
 */
public record VerificationResult(
        boolean passed,
        @Nullable VerificationCode code,
        @Nullable String detail) {

    private static final VerificationResult PASSED = new VerificationResult(true, null, null);

    public VerificationResult {
        if (passed != (code == null)) {
            throw new IllegalArgumentException("a code goes with a failed verification and only with it");
        }
    }

    public static VerificationResult success() {
        return PASSED;
    }

    public static VerificationResult failure(VerificationCode code, String detail) {
        return new VerificationResult(false, Objects.requireNonNull(code), Objects.requireNonNull(detail));
    }
}
