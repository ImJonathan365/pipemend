package com.pipemend.pipeline.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.pipemend.pipeline.policy.PolicyEvaluation.ViolationDecision;
import com.pipemend.pipeline.triage.contract.Classification;
import com.pipemend.pipeline.triage.contract.Operation;
import com.pipemend.pipeline.triage.contract.RootCause;
import com.pipemend.pipeline.triage.contract.Severity;
import com.pipemend.pipeline.triage.contract.TriageRequest;
import com.pipemend.pipeline.triage.contract.TriageResponse;
import com.pipemend.pipeline.validation.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** AC-09.5: every operation, every error code and every rejection reason, on hand-written analyses. */
class CorrectionPolicyTest {

    private static final PolicyProperties DEFAULTS = new PolicyProperties(0.85, 0.90, CorrectionCatalog.FIELDS);

    private final CorrectionPolicy policy = new CorrectionPolicy(DEFAULTS);

    static Stream<Arguments> ac_09_1_everyOperationIsApprovedOnEveryFieldTheCatalogAllows() {
        return CorrectionCatalog.FIELDS.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(field -> arguments(entry.getKey(), field)));
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource
    void ac_09_1_everyOperationIsApprovedOnEveryFieldTheCatalogAllows(Operation operation, String field) {
        assertThat(reasonOf(policy, ErrorCode.INVALID_FORMAT, field, operation, Severity.LOW, 0.99))
                .isNull();
    }

    // SCHEMA_DRIFT never travels in a triage request (docs/06 A.1).
    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = "SCHEMA_DRIFT", mode = EnumSource.Mode.EXCLUDE)
    void ac_09_1_onlyTheListedErrorCodesAreNotCorrectable(ErrorCode code) {
        RejectionReason expected =
                CorrectionCatalog.NOT_CORRECTABLE.contains(code) ? RejectionReason.ERROR_CODE_NOT_CORRECTABLE : null;

        assertThat(reasonOf(policy, code, "country", Operation.TRIM, Severity.LOW, 0.99))
                .isEqualTo(expected);
    }

    @Test
    void ac_09_1_theNotCorrectableCodesAreThoseOfAc091() {
        assertThat(CorrectionCatalog.NOT_CORRECTABLE)
                .containsExactlyInAnyOrder(
                        ErrorCode.MISSING_REQUIRED_FIELD,
                        ErrorCode.OUT_OF_RANGE,
                        ErrorCode.MAX_LENGTH_EXCEEDED,
                        ErrorCode.BUSINESS_RULE_VIOLATION,
                        ErrorCode.MALFORMED_ROW);
    }

    @Test
    void ac_09_3_anOperationOutsideTheConfiguredAllowlistIsRejected() {
        CorrectionPolicy narrowed = new CorrectionPolicy(
                new PolicyProperties(0.85, 0.90, Map.of(Operation.TRIM, CorrectionCatalog.FIELDS.get(Operation.TRIM))));

        assertThat(reasonOf(
                        narrowed, ErrorCode.INVALID_FORMAT, "invoice_date", Operation.PARSE_DATE, Severity.LOW, 0.99))
                .isEqualTo(RejectionReason.OPERATION_NOT_ALLOWED);
        assertThat(reasonOf(narrowed, ErrorCode.PATTERN_MISMATCH, "country", Operation.TRIM, Severity.LOW, 0.99))
                .isNull();
    }

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource({
        "TRIM, description",
        "NORMALIZE_CASE, stock_code",
        "PARSE_DATE, unit_price",
        "PARSE_NUMBER, invoice_date",
        "MAP_TO_ENUM, invoice_no",
        "NULLIFY_TOKEN, description"
    })
    void ac_09_3_aFieldTheOperationDoesNotAllowIsRejected(Operation operation, String field) {
        assertThat(reasonOf(policy, ErrorCode.INVALID_FORMAT, field, operation, Severity.LOW, 0.99))
                .isEqualTo(RejectionReason.FIELD_NOT_ALLOWED);
    }

    @ParameterizedTest
    @EnumSource(value = Severity.class, names = "LOW", mode = EnumSource.Mode.EXCLUDE)
    void ac_09_3_anySeverityAboveLowIsRejected(Severity severity) {
        assertThat(reasonOf(policy, ErrorCode.INVALID_FORMAT, "country", Operation.TRIM, severity, 0.99))
                .isEqualTo(RejectionReason.SEVERITY_TOO_HIGH);
    }

    @ParameterizedTest(name = "{0} with {1}")
    @CsvSource({
        "TRIM, 0.85, true",
        "TRIM, 0.8499, false",
        "TRIM, 0.87, true",
        "MAP_TO_ENUM, 0.90, true",
        "MAP_TO_ENUM, 0.8999, false",
        "MAP_TO_ENUM, 0.87, false"
    })
    void ac_09_1_theConfidenceMustReachTheThresholdOfItsOperation(
            Operation operation, double confidence, boolean approved) {
        assertThat(reasonOf(policy, ErrorCode.INVALID_FORMAT, "country", operation, Severity.LOW, confidence))
                .isEqualTo(approved ? null : RejectionReason.CONFIDENCE_BELOW_THRESHOLD);
    }

    @Test
    void ac_09_1_mapToEnumUsesOnlyItsOwnThresholdAlsoWhenItIsTheLowerOne() {
        CorrectionPolicy lowerEnum = new CorrectionPolicy(new PolicyProperties(0.85, 0.80, CorrectionCatalog.FIELDS));

        assertThat(reasonOf(
                        lowerEnum, ErrorCode.INVALID_ENUM_VALUE, "country", Operation.MAP_TO_ENUM, Severity.LOW, 0.82))
                .isNull();
        assertThat(reasonOf(lowerEnum, ErrorCode.PATTERN_MISMATCH, "country", Operation.TRIM, Severity.LOW, 0.82))
                .isEqualTo(RejectionReason.CONFIDENCE_BELOW_THRESHOLD);
    }

    @Test
    void ac_09_2_oneRejectedViolationRejectsTheWholeRecord() {
        List<TriageRequest.Violation> sent = List.of(
                violation("v1", "invoice_date", ErrorCode.INVALID_FORMAT),
                violation("v2", "unit_price", ErrorCode.INVALID_FORMAT),
                violation("v3", "country", ErrorCode.INVALID_ENUM_VALUE));
        TriageResponse received = response(
                Classification.AUTO_FIXABLE,
                List.of(
                        analysis("v1", "invoice_date", Operation.PARSE_DATE, Severity.LOW, 0.97),
                        analysis("v2", "unit_price", Operation.PARSE_NUMBER, Severity.LOW, 0.50),
                        analysis("v3", "country", Operation.MAP_TO_ENUM, Severity.LOW, 0.96)));

        PolicyEvaluation evaluation = policy.evaluate(sent, received);

        assertThat(evaluation.approved()).isFalse();
        assertThat(evaluation.violations())
                .containsExactly(
                        new ViolationDecision("v1", null),
                        new ViolationDecision("v2", RejectionReason.CONFIDENCE_BELOW_THRESHOLD),
                        new ViolationDecision("v3", null));
    }

    @Test
    void ac_09_1_aRecordIsApprovedOnlyWhenEveryViolationIs() {
        List<TriageRequest.Violation> sent = List.of(
                violation("v1", "invoice_date", ErrorCode.INVALID_FORMAT),
                violation("v2", "country", ErrorCode.INVALID_ENUM_VALUE));
        TriageResponse received = response(
                Classification.AUTO_FIXABLE,
                List.of(
                        analysis("v1", "invoice_date", Operation.PARSE_DATE, Severity.LOW, 0.97),
                        analysis("v2", "country", Operation.MAP_TO_ENUM, Severity.LOW, 0.96)));

        assertThat(policy.evaluate(sent, received).approved()).isTrue();
    }

    @Test
    void ac_09_1_theReceivedRecordDecisionIsNeverRead() {
        List<TriageRequest.Violation> sent = List.of(violation("v1", "country", ErrorCode.PATTERN_MISMATCH));
        TriageResponse saysReview = response(
                Classification.NEEDS_HUMAN_REVIEW,
                List.of(analysis("v1", "country", Operation.TRIM, Severity.LOW, 0.99)));

        assertThat(policy.evaluate(sent, saysReview).approved()).isTrue();
    }

    static Stream<Arguments> ac_09_3_theReasonIsTheFirstConditionThatFails() {
        CorrectionPolicy noTrim = new CorrectionPolicy(new PolicyProperties(
                0.85, 0.90, Map.of(Operation.PARSE_DATE, CorrectionCatalog.FIELDS.get(Operation.PARSE_DATE))));
        return Stream.of(
                arguments(noTrim, ErrorCode.OUT_OF_RANGE, "description", RejectionReason.ERROR_CODE_NOT_CORRECTABLE),
                arguments(noTrim, ErrorCode.INVALID_FORMAT, "description", RejectionReason.OPERATION_NOT_ALLOWED),
                arguments(
                        new CorrectionPolicy(DEFAULTS),
                        ErrorCode.INVALID_FORMAT,
                        "description",
                        RejectionReason.FIELD_NOT_ALLOWED));
    }

    /** Every case also has a HIGH severity and a confidence of 0.1, which only count once the rest passes. */
    @ParameterizedTest(name = "{3}")
    @MethodSource
    void ac_09_3_theReasonIsTheFirstConditionThatFails(
            CorrectionPolicy policy, ErrorCode code, String field, RejectionReason expected) {
        assertThat(reasonOf(policy, code, field, Operation.TRIM, Severity.HIGH, 0.1))
                .isEqualTo(expected);
    }

    @Test
    void ac_09_3_severityIsCheckedBeforeConfidence() {
        assertThat(reasonOf(policy, ErrorCode.INVALID_FORMAT, "country", Operation.TRIM, Severity.HIGH, 0.1))
                .isEqualTo(RejectionReason.SEVERITY_TOO_HIGH);
    }

    @Test
    void aNeedsHumanReviewAnalysisNeverReachesThePolicy() {
        List<TriageRequest.Violation> sent = List.of(
                violation("v1", "country", ErrorCode.PATTERN_MISMATCH),
                violation("v2", "quantity", ErrorCode.OUT_OF_RANGE));
        TriageResponse received = response(
                Classification.NEEDS_HUMAN_REVIEW,
                List.of(
                        analysis("v1", "country", Operation.TRIM, Severity.LOW, 0.99),
                        new TriageResponse.Analysis(
                                "v2",
                                "quantity",
                                RootCause.IMPLAUSIBLE_VALUE,
                                "Out of range",
                                Severity.HIGH,
                                Classification.NEEDS_HUMAN_REVIEW,
                                0.99,
                                null,
                                "Check the quantity")));

        assertThatThrownBy(() -> policy.evaluate(sent, received))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("The correction policy only evaluates AUTO_FIXABLE analyses");
    }

    @Test
    void analysesThatDoNotPairWithTheViolationsAreABugOfTheCaller() {
        List<TriageRequest.Violation> sent = List.of(violation("v1", "country", ErrorCode.PATTERN_MISMATCH));
        TriageResponse received = response(
                Classification.AUTO_FIXABLE, List.of(analysis("v9", "country", Operation.TRIM, Severity.LOW, 0.99)));

        assertThatThrownBy(() -> policy.evaluate(sent, received)).isExactlyInstanceOf(IllegalStateException.class);
    }

    private static @Nullable RejectionReason reasonOf(
            CorrectionPolicy policy,
            ErrorCode code,
            String field,
            Operation operation,
            Severity severity,
            double confidence) {
        PolicyEvaluation evaluation = policy.evaluate(
                List.of(violation("v1", field, code)),
                response(Classification.AUTO_FIXABLE, List.of(analysis("v1", field, operation, severity, confidence))));
        assertThat(evaluation.approved())
                .isEqualTo(evaluation.violations().getFirst().approved());
        return evaluation.violations().getFirst().rejectionReason();
    }

    private static TriageRequest.Violation violation(String id, String field, ErrorCode code) {
        return new TriageRequest.Violation(id, field, null, code, "x", Map.of(), "technical message", null);
    }

    private static TriageResponse.Analysis analysis(
            String id, String field, Operation operation, Severity severity, double confidence) {
        return new TriageResponse.Analysis(
                id,
                field,
                RootCause.FORMAT_MISMATCH,
                "explanation",
                severity,
                Classification.AUTO_FIXABLE,
                confidence,
                new TriageResponse.Correction(operation, "x", "y", Map.of()),
                null);
    }

    private static TriageResponse response(Classification decision, List<TriageResponse.Analysis> analyses) {
        return new TriageResponse(
                UUID.randomUUID(),
                decision,
                Severity.LOW,
                "summary",
                analyses,
                new TriageResponse.Meta("mock", "m", "triage_v1", 1, false, null, null, 0));
    }
}
