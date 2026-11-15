package com.pipemend.pipeline.policy;

import com.pipemend.pipeline.verifiers.VerificationResult;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * quarantine_record.policy_decision (docs/05 section 5, AC-09.3, AC-10.5). stage says where the record stopped; after a
 * failed revalidation it is VERIFICATION with approved true, and quarantine_reason tells the rest.
 */
public record PolicyDecision(boolean approved, Stage stage, List<ViolationDecision> violations) {

    public enum Stage {
        POLICY,
        VERIFICATION
    }

    /** @param verification null when the policy rejected the record, since the verifiers did not run */
    public record ViolationDecision(
            String violationId,
            boolean approved,
            @Nullable RejectionReason rejectionReason,
            @Nullable VerificationResult verification) {}

    public PolicyDecision {
        violations = List.copyOf(violations);
    }
}
