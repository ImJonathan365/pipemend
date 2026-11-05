package com.pipemend.pipeline.triage;

import com.pipemend.pipeline.triage.contract.Classification;
import com.pipemend.pipeline.triage.contract.Operation;
import com.pipemend.pipeline.triage.contract.Severity;
import com.pipemend.pipeline.triage.contract.TriageRequest;
import com.pipemend.pipeline.triage.contract.TriageResponse;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The semantic check of an answer of ai-service (AC-08.7), plus what docs/06 fixes and AC-08.7 does not name. Pure: no
 * Spring and no I/O. Whoever uses the answer takes recordDecision and maxSeverity from {@link Result.Valid}, computed
 * here, and never from the response.
 */
public final class TriageResponseValidator {

    static final int SUMMARY_MAX_LENGTH = 1000;
    static final int EXPLANATION_MAX_LENGTH = 800;

    /** What failed, without any value of the row. Internal: this is not an enum of the contract. */
    public enum Issue {
        REQUEST_ID,
        ANALYSES_NOT_THE_VIOLATIONS,
        FIELD,
        ORIGINAL_VALUE,
        CORRECTION_CLASSIFICATION,
        RECORD_DECISION,
        MAX_SEVERITY,
        CONFIDENCE,
        HUMAN_ACTION,
        PROPOSED_VALUE,
        TEXT_TOO_LONG,
        NEGATIVE_META
    }

    public sealed interface Result {

        record Valid(Classification recordDecision, Severity maxSeverity) implements Result {}

        record Invalid(Set<Issue> issues) implements Result {}
    }

    private TriageResponseValidator() {}

    public static Result validate(TriageRequest sent, TriageResponse received) {
        Set<Issue> issues = EnumSet.noneOf(Issue.class);
        List<TriageResponse.Analysis> analyses = received.analyses();
        if (!received.requestId().equals(sent.requestId())) {
            issues.add(Issue.REQUEST_ID);
        }
        if (length(received.summary()) > SUMMARY_MAX_LENGTH) {
            issues.add(Issue.TEXT_TOO_LONG);
        }
        checkMeta(received.meta(), issues);
        analyses.forEach(analysis -> checkAnalysis(analysis, issues));
        checkAgainstTheViolations(sent.violations(), analyses, issues);

        if (analyses.isEmpty()) {
            issues.add(Issue.ANALYSES_NOT_THE_VIOLATIONS);
            return new Result.Invalid(issues);
        }
        Classification decision =
                analyses.stream().allMatch(analysis -> analysis.classification() == Classification.AUTO_FIXABLE)
                        ? Classification.AUTO_FIXABLE
                        : Classification.NEEDS_HUMAN_REVIEW;
        Severity highest = analyses.stream()
                .map(TriageResponse.Analysis::severity)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        if (received.recordDecision() != decision) {
            issues.add(Issue.RECORD_DECISION);
        }
        if (received.maxSeverity() != highest) {
            issues.add(Issue.MAX_SEVERITY);
        }
        return issues.isEmpty() ? new Result.Valid(decision, highest) : new Result.Invalid(issues);
    }

    private static void checkAgainstTheViolations(
            List<TriageRequest.Violation> violations, List<TriageResponse.Analysis> analyses, Set<Issue> issues) {
        List<String> sentIds =
                violations.stream().map(TriageRequest.Violation::violationId).toList();
        List<String> receivedIds =
                analyses.stream().map(TriageResponse.Analysis::violationId).toList();
        if (!receivedIds.equals(sentIds)) {
            // Exactly the ids sent, in the same order and without repeats; with any other list nothing pairs up.
            issues.add(Issue.ANALYSES_NOT_THE_VIOLATIONS);
            return;
        }
        for (int i = 0; i < violations.size(); i++) {
            TriageRequest.Violation violation = violations.get(i);
            TriageResponse.Analysis analysis = analyses.get(i);
            if (!analysis.field().equals(violation.field())) {
                issues.add(Issue.FIELD);
            }
            if (analysis.correction() != null
                    && !Objects.equals(analysis.correction().originalValue(), violation.receivedValue())) {
                issues.add(Issue.ORIGINAL_VALUE);
            }
        }
    }

    private static void checkAnalysis(TriageResponse.Analysis analysis, Set<Issue> issues) {
        boolean fixable = analysis.classification() == Classification.AUTO_FIXABLE;
        TriageResponse.Correction correction = analysis.correction();
        if (fixable != (correction != null)) {
            issues.add(Issue.CORRECTION_CLASSIFICATION);
        }
        if (correction != null
                && correction.proposedValue() == null
                && correction.operation() != Operation.NULLIFY_TOKEN) {
            issues.add(Issue.PROPOSED_VALUE);
        }
        // Written this way so that NaN fails too; 1e400 reaches here as Infinity.
        if (!(analysis.confidence() >= 0 && analysis.confidence() <= 1)) {
            issues.add(Issue.CONFIDENCE);
        }
        String action = analysis.suggestedHumanAction();
        if (!fixable && (action == null || action.isBlank())) {
            issues.add(Issue.HUMAN_ACTION);
        }
        if (length(analysis.explanation()) > EXPLANATION_MAX_LENGTH) {
            issues.add(Issue.TEXT_TOO_LONG);
        }
    }

    private static void checkMeta(TriageResponse.Meta meta, Set<Issue> issues) {
        boolean negative = meta.latencyMs() < 0
                || meta.llmCalls() < 0
                || (meta.inputTokens() != null && meta.inputTokens() < 0)
                || (meta.outputTokens() != null && meta.outputTokens() < 0);
        if (negative) {
            issues.add(Issue.NEGATIVE_META);
        }
    }

    /** In code points, as ai-service counts them. */
    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }
}
