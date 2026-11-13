package com.pipemend.pipeline.policy;

import com.pipemend.pipeline.policy.PolicyEvaluation.ViolationDecision;
import com.pipemend.pipeline.triage.contract.Classification;
import com.pipemend.pipeline.triage.contract.Operation;
import com.pipemend.pipeline.triage.contract.Severity;
import com.pipemend.pipeline.triage.contract.TriageRequest;
import com.pipemend.pipeline.triage.contract.TriageResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Decides whether the corrections proposed for one record may be applied (FR-09, ADR-0003). Pure: no Spring and no
 * I/O. It reads every analysis and never recordDecision (AC-09.1). It only judges what was proposed; recomputing the
 * values is the job of the verifiers (FR-10).
 */
public final class CorrectionPolicy {

    private final PolicyProperties properties;

    public CorrectionPolicy(PolicyProperties properties) {
        this.properties = properties;
    }

    /**
     * @param sent the violations of the request, which carry the error codes
     * @param received an answer that already passed TriageResponseValidator, so its analyses pair one to one with
     *     {@code sent}
     * @throws IllegalStateException if an analysis is NEEDS_HUMAN_REVIEW: those records go to AI_REVIEW_REQUIRED before
     *     the policy (docs/04 section 4), so reaching here is a bug of the caller
     */
    public PolicyEvaluation evaluate(List<TriageRequest.Violation> sent, TriageResponse received) {
        List<TriageResponse.Analysis> analyses = received.analyses();
        if (analyses.stream().anyMatch(analysis -> analysis.classification() != Classification.AUTO_FIXABLE)) {
            throw new IllegalStateException("The correction policy only evaluates AUTO_FIXABLE analyses");
        }
        if (analyses.size() != sent.size()) {
            throw new IllegalStateException("The analyses do not pair with the violations sent");
        }
        List<ViolationDecision> decisions = new ArrayList<>(sent.size());
        for (int i = 0; i < sent.size(); i++) {
            TriageRequest.Violation violation = sent.get(i);
            TriageResponse.Analysis analysis = analyses.get(i);
            if (!analysis.violationId().equals(violation.violationId())) {
                throw new IllegalStateException("The analyses do not pair with the violations sent");
            }
            decisions.add(new ViolationDecision(violation.violationId(), rejectionOf(violation, analysis)));
        }
        return new PolicyEvaluation(decisions);
    }

    /** The first condition of AC-09.1 that fails, from the most structural to what depends on the AI. */
    private @Nullable RejectionReason rejectionOf(TriageRequest.Violation violation, TriageResponse.Analysis analysis) {
        // AUTO_FIXABLE always carries a correction: TriageResponseValidator rejects it otherwise.
        Operation operation = analysis.correction().operation();
        if (CorrectionCatalog.NOT_CORRECTABLE.contains(violation.errorCode())) {
            return RejectionReason.ERROR_CODE_NOT_CORRECTABLE;
        }
        Set<String> fields = properties.allowlist().get(operation);
        if (fields == null) {
            return RejectionReason.OPERATION_NOT_ALLOWED;
        }
        if (!fields.contains(analysis.field())) {
            return RejectionReason.FIELD_NOT_ALLOWED;
        }
        if (analysis.severity() != Severity.LOW) {
            return RejectionReason.SEVERITY_TOO_HIGH;
        }
        // MAP_TO_ENUM uses only its own threshold, even when it is configured below the general one (AC-09.1).
        double threshold =
                operation == Operation.MAP_TO_ENUM ? properties.enumMinConfidence() : properties.minConfidence();
        if (!(analysis.confidence() >= threshold)) {
            return RejectionReason.CONFIDENCE_BELOW_THRESHOLD;
        }
        return null;
    }
}
