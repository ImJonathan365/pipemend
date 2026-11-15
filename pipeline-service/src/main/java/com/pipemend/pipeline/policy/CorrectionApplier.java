package com.pipemend.pipeline.policy;

import com.pipemend.pipeline.policy.PolicyDecision.Stage;
import com.pipemend.pipeline.triage.contract.TriageRequest;
import com.pipemend.pipeline.triage.contract.TriageResponse;
import com.pipemend.pipeline.validation.Validator;
import com.pipemend.pipeline.verifiers.VerificationInput;
import com.pipemend.pipeline.verifiers.VerificationResult;
import com.pipemend.pipeline.verifiers.Verifiers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Steps 3 to 5 of docs/04 section 4 for a record whose analyses are all AUTO_FIXABLE: the policy, then every verifier,
 * then the corrected record through the same validator (FR-09, FR-10). All or nothing: {@link Corrected} is built only
 * in the last line of {@link #apply}, once every step passed. Pure: no I/O.
 */
public final class CorrectionApplier {

    /** quarantine_reason values of docs/02 section 5 that this class decides. */
    public enum Rejection {
        POLICY_REJECTED,
        VERIFICATION_FAILED,
        REVALIDATION_FAILED
    }

    public sealed interface Result permits Corrected, Rejected {}

    /** Nothing is applied: the record goes to quarantine with this reason and decision. */
    public record Rejected(Rejection reason, PolicyDecision decision) implements Result {}

    /** The only way to an AUTO_CORRECTED row. Not a record, so that only this package can build it. */
    public static final class Corrected implements Result {

        private final Map<String, @Nullable String> fields;
        private final List<AppliedCorrection> corrections;

        private Corrected(Map<String, @Nullable String> fields, List<AppliedCorrection> corrections) {
            this.fields = Collections.unmodifiableMap(fields);
            this.corrections = List.copyOf(corrections);
        }

        /** Canonical field name to value, after revalidation; a NULLIFY_TOKEN field is null. */
        public Map<String, @Nullable String> fields() {
            return fields;
        }

        public List<AppliedCorrection> corrections() {
            return corrections;
        }
    }

    private final CorrectionPolicy policy;
    private final Verifiers verifiers;
    private final Validator validator;

    public CorrectionApplier(CorrectionPolicy policy, Verifiers verifiers, Validator validator) {
        this.policy = policy;
        this.verifiers = verifiers;
        this.validator = validator;
    }

    /**
     * @param fields the record as validated, canonical field name to raw value; it is never modified
     * @param sent the violations of the request
     * @param received an answer that passed TriageResponseValidator, with every analysis AUTO_FIXABLE
     */
    public Result apply(Map<String, String> fields, List<TriageRequest.Violation> sent, TriageResponse received) {
        PolicyEvaluation evaluation = policy.evaluate(sent, received);
        if (!evaluation.approved()) {
            List<PolicyDecision.ViolationDecision> decisions = evaluation.violations().stream()
                    .map(decision -> new PolicyDecision.ViolationDecision(
                            decision.violationId(), decision.approved(), decision.rejectionReason(), null))
                    .toList();
            return new Rejected(Rejection.POLICY_REJECTED, new PolicyDecision(false, Stage.POLICY, decisions));
        }

        // Every verifier runs, so that policy_decision has the result of each violation (AC-10.5).
        List<PolicyDecision.ViolationDecision> verified = new ArrayList<>(sent.size());
        boolean allPassed = true;
        for (int i = 0; i < sent.size(); i++) {
            TriageRequest.Violation violation = sent.get(i);
            TriageResponse.Analysis analysis = received.analyses().get(i);
            TriageResponse.Correction correction = Objects.requireNonNull(analysis.correction());
            // The value of the record itself, never what the AI echoed back (D152).
            VerificationResult result = verifiers
                    .forOperation(correction.operation())
                    .verify(new VerificationInput(
                            violation.field(), fields.get(violation.field()), correction, analysis.confidence()));
            allPassed &= result.passed();
            verified.add(new PolicyDecision.ViolationDecision(violation.violationId(), true, null, result));
        }
        if (!allPassed) {
            return new Rejected(Rejection.VERIFICATION_FAILED, new PolicyDecision(false, Stage.VERIFICATION, verified));
        }

        Map<String, @Nullable String> corrected = new HashMap<>(fields);
        for (int i = 0; i < sent.size(); i++) {
            // The field of the pipeline's violation; the validator already checked the AI named the same one.
            corrected.put(
                    sent.get(i).field(), received.analyses().get(i).correction().proposedValue());
        }
        if (!validator.validate(corrected).isEmpty()) {
            return new Rejected(Rejection.REVALIDATION_FAILED, new PolicyDecision(true, Stage.VERIFICATION, verified));
        }

        List<AppliedCorrection> corrections = new ArrayList<>(sent.size());
        TriageResponse.Meta meta = received.meta();
        for (int i = 0; i < sent.size(); i++) {
            TriageRequest.Violation violation = sent.get(i);
            TriageResponse.Analysis analysis = received.analyses().get(i);
            TriageResponse.Correction correction = analysis.correction();
            corrections.add(new AppliedCorrection(
                    violation.field(),
                    violation.errorCode(),
                    correction.operation(),
                    correction.parameters(),
                    fields.get(violation.field()),
                    correction.proposedValue(),
                    // correction_log.confidence is numeric(4,3); rounding here keeps it the same everywhere (NFR-13).
                    BigDecimal.valueOf(analysis.confidence()).setScale(3, RoundingMode.HALF_UP),
                    analysis.severity(),
                    analysis.explanation(),
                    meta.provider(),
                    meta.model(),
                    meta.promptVersion(),
                    received.requestId()));
        }
        return new Corrected(corrected, corrections);
    }
}
