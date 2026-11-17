package com.pipemend.pipeline.policy;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** The decision of the policy for each violation of one record, in the order of the request. */
public record PolicyEvaluation(List<ViolationDecision> violations) {

    public PolicyEvaluation {
        violations = List.copyOf(violations);
    }

    /** All or nothing (AC-09.2): one rejected violation rejects the whole record. */
    public boolean approved() {
        return violations.stream().allMatch(ViolationDecision::approved);
    }

    /** @param rejectionReason null when the violation is approved */
    public record ViolationDecision(
            String violationId, @Nullable RejectionReason rejectionReason) {

        public boolean approved() {
            return rejectionReason == null;
        }
    }
}
