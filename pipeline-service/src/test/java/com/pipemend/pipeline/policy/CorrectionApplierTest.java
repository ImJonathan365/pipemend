package com.pipemend.pipeline.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pipemend.pipeline.policy.CorrectionApplier.Corrected;
import com.pipemend.pipeline.policy.CorrectionApplier.Rejected;
import com.pipemend.pipeline.policy.CorrectionApplier.Rejection;
import com.pipemend.pipeline.policy.PolicyDecision.Stage;
import com.pipemend.pipeline.schema.FieldRule.EnumRule;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.schema.SchemaTestSupport;
import com.pipemend.pipeline.triage.contract.Classification;
import com.pipemend.pipeline.triage.contract.Operation;
import com.pipemend.pipeline.triage.contract.RootCause;
import com.pipemend.pipeline.triage.contract.Severity;
import com.pipemend.pipeline.triage.contract.TriageRequest;
import com.pipemend.pipeline.triage.contract.TriageResponse;
import com.pipemend.pipeline.validation.ErrorCode;
import com.pipemend.pipeline.validation.Validator;
import com.pipemend.pipeline.verifiers.CountryAliases;
import com.pipemend.pipeline.verifiers.VerificationCode;
import com.pipemend.pipeline.verifiers.VerificationResult;
import com.pipemend.pipeline.verifiers.Verifiers;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Steps 3 to 5 of docs/04 section 4 with the real policy, the six real verifiers and the real validator, on
 * hand-written proposals. Nothing is mocked.
 */
class CorrectionApplierTest {

    private static final SchemaDefinition SCHEMA = SchemaTestSupport.realSchema();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID REQUEST_ID = UUID.fromString("0b6c3f5e-7f0f-4a55-9d0e-2a1f0f1b6a10");

    private final CorrectionApplier applier = new CorrectionApplier(
            new CorrectionPolicy(new PolicyProperties(0.85, 0.90, CorrectionCatalog.FIELDS)),
            Verifiers.of(
                    SCHEMA,
                    CountryAliases.load(SchemaTestSupport.resource("country-aliases.v1.yaml"), (EnumRule)
                            SCHEMA.field("country").rule()),
                    0.90),
            new Validator(SCHEMA, CLOCK));

    /** A violation, the value the record holds for it, and the AI proposal. */
    private record Case(
            String field,
            ErrorCode code,
            @Nullable String value,
            Operation operation,
            @Nullable String proposed,
            Map<String, @Nullable Object> parameters,
            double confidence) {}

    @Test
    void ac_10_3_aRecordWhoseProposalsPassEveryStepIsCorrected() {
        List<Case> cases = List.of(
                new Case(
                        "invoice_date",
                        ErrorCode.INVALID_FORMAT,
                        "25/12/2010 08:26",
                        Operation.PARSE_DATE,
                        "2010-12-25 08:26:00",
                        Map.of("sourcePattern", "dd/MM/yyyy HH:mm"),
                        0.97),
                new Case(
                        "unit_price",
                        ErrorCode.INVALID_FORMAT,
                        "\u00A31,25",
                        Operation.PARSE_NUMBER,
                        "1.25",
                        numberParameters(",", null),
                        0.9545),
                new Case(
                        "country",
                        ErrorCode.INVALID_ENUM_VALUE,
                        "UK",
                        Operation.MAP_TO_ENUM,
                        "United Kingdom",
                        Map.of(),
                        0.96));

        Corrected corrected = (Corrected) apply(cases);

        assertThat(corrected.fields())
                .containsEntry("invoice_date", "2010-12-25 08:26:00")
                .containsEntry("unit_price", "1.25")
                .containsEntry("country", "United Kingdom");
        assertThat(corrected.corrections())
                .extracting(AppliedCorrection::fieldName)
                .containsExactly("invoice_date", "unit_price", "country");
        assertThat(corrected.corrections().get(1))
                .isEqualTo(new AppliedCorrection(
                        "unit_price",
                        ErrorCode.INVALID_FORMAT,
                        Operation.PARSE_NUMBER,
                        numberParameters(",", null),
                        "\u00A31,25",
                        "1.25",
                        new BigDecimal("0.955"),
                        Severity.LOW,
                        "explanation of unit_price",
                        "mock",
                        "mock-rules-v1",
                        "triage_v1",
                        REQUEST_ID));
    }

    @Test
    void ac_10_3_onlyTheFieldsWithAViolationChange() {
        Map<String, String> fields = withValues(Map.of("country", "UK", "customer_id", "None"));
        Corrected corrected = (Corrected) applier.apply(
                fields,
                List.of(
                        violation("v1", "country", ErrorCode.INVALID_ENUM_VALUE, "UK"),
                        violation("v2", "customer_id", ErrorCode.PATTERN_MISMATCH, "None")),
                response(List.of(
                        analysis("v1", "country", Operation.MAP_TO_ENUM, "UK", "United Kingdom", Map.of(), 0.96),
                        analysis("v2", "customer_id", Operation.NULLIFY_TOKEN, "None", null, Map.of(), 0.99))));

        Map<String, @Nullable String> expected = new HashMap<>(fields);
        expected.put("country", "United Kingdom");
        expected.put("customer_id", null);
        assertThat(corrected.fields()).isEqualTo(expected);
        assertThat(corrected.corrections().get(1).correctedValue()).isNull();
    }

    /** D152: the verifier recomputes from the value of the record, never from the originalValue the AI echoed. */
    @Test
    void ac_10_1_theVerifiedOriginalIsTheValueOfTheRecord() {
        CorrectionApplier.Result result = applier.apply(
                withValues(Map.of("country", "UK")),
                List.of(violation("v1", "country", ErrorCode.INVALID_ENUM_VALUE, "UK")),
                response(List.of(analysis(
                        "v1", "country", Operation.MAP_TO_ENUM, "Austria", "United Kingdom", Map.of(), 0.96))));

        assertThat(result).isInstanceOf(Corrected.class);
        assertThat(((Corrected) result).corrections().getFirst().originalValue())
                .isEqualTo("UK");
    }

    static Stream<List<Case>> ac_09_2_aRejectedRecordNeverCarriesAppliedCorrections() {
        Case goodDate = new Case(
                "invoice_date",
                ErrorCode.INVALID_FORMAT,
                "25/12/2010 08:26",
                Operation.PARSE_DATE,
                "2010-12-25 08:26:00",
                Map.of("sourcePattern", "dd/MM/yyyy HH:mm"),
                0.97);
        return Stream.of(
                // Policy: confidence below the threshold.
                List.of(
                        goodDate,
                        new Case(
                                "country",
                                ErrorCode.INVALID_ENUM_VALUE,
                                "UK",
                                Operation.MAP_TO_ENUM,
                                "United Kingdom",
                                Map.of(),
                                0.5)),
                // Verification: AC-10.6 "Austria" -> "Australia".
                List.of(
                        goodDate,
                        new Case(
                                "country",
                                ErrorCode.INVALID_ENUM_VALUE,
                                "Austria",
                                Operation.MAP_TO_ENUM,
                                "Australia",
                                Map.of(),
                                0.99)),
                // Revalidation: AC-10.6, a proposed value that is not canonical.
                List.of(
                        goodDate,
                        new Case(
                                "unit_price",
                                ErrorCode.INVALID_FORMAT,
                                "\u00A31,2",
                                Operation.PARSE_NUMBER,
                                "1.200",
                                numberParameters(",", null),
                                0.97)));
    }

    @ParameterizedTest
    @MethodSource
    void ac_09_2_aRejectedRecordNeverCarriesAppliedCorrections(List<Case> cases) {
        Map<String, String> fields = Map.copyOf(withValues(values(cases)));

        CorrectionApplier.Result result = apply(fields, cases);

        assertThat(result).isInstanceOf(Rejected.class);
        assertThat(fields).isEqualTo(withValues(values(cases)));
        // A rejection carries a reason and a decision, and no field or correction at all.
        assertThat(Arrays.stream(Rejected.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("reason", "decision");
    }

    @Test
    void ac_09_3_aPolicyRejectionStopsBeforeTheVerifiers() {
        Rejected rejected = (Rejected) apply(List.of(new Case(
                "country",
                ErrorCode.INVALID_ENUM_VALUE,
                "UK",
                Operation.MAP_TO_ENUM,
                "United Kingdom",
                Map.of(),
                0.5)));

        assertThat(rejected.reason()).isEqualTo(Rejection.POLICY_REJECTED);
        assertThat(rejected.decision())
                .isEqualTo(new PolicyDecision(
                        false,
                        Stage.POLICY,
                        List.of(new PolicyDecision.ViolationDecision(
                                "v1", false, RejectionReason.CONFIDENCE_BELOW_THRESHOLD, null))));
    }

    @Test
    void ac_10_5_everyVerifierRunsAndOneFailureRejectsTheRecord() {
        Rejected rejected = (Rejected) apply(List.of(
                new Case(
                        "country",
                        ErrorCode.INVALID_ENUM_VALUE,
                        "Austria",
                        Operation.MAP_TO_ENUM,
                        "Australia",
                        Map.of(),
                        0.99),
                new Case(
                        "invoice_date",
                        ErrorCode.INVALID_FORMAT,
                        "25/12/2010 08:26",
                        Operation.PARSE_DATE,
                        "2010-12-25 08:26:00",
                        Map.of("sourcePattern", "dd/MM/yyyy HH:mm"),
                        0.97)));

        assertThat(rejected.reason()).isEqualTo(Rejection.VERIFICATION_FAILED);
        assertThat(rejected.decision().approved()).isFalse();
        assertThat(rejected.decision().stage()).isEqualTo(Stage.VERIFICATION);
        assertThat(rejected.decision().violations())
                .allSatisfy(decision -> assertThat(decision.approved()).isTrue())
                .extracting(decision -> decision.verification().code())
                .containsExactly(VerificationCode.UNVERIFIABLE_MAPPING, null);
    }

    @Test
    void ac_10_2_aVerifiedValueThatFailsRevalidationIsRejectedWithEveryVerificationPassed() {
        Rejected rejected = (Rejected) apply(List.of(new Case(
                "unit_price",
                ErrorCode.INVALID_FORMAT,
                "\u00A31,2",
                Operation.PARSE_NUMBER,
                "1.200",
                numberParameters(",", null),
                0.97)));

        assertThat(rejected.reason()).isEqualTo(Rejection.REVALIDATION_FAILED);
        assertThat(rejected.decision())
                .isEqualTo(new PolicyDecision(
                        true,
                        Stage.VERIFICATION,
                        List.of(new PolicyDecision.ViolationDecision("v1", true, null, VerificationResult.success()))));
    }

    @Test
    void aNeedsHumanReviewAnalysisNeverReachesTheApplier() {
        TriageResponse review = new TriageResponse(
                REQUEST_ID,
                Classification.NEEDS_HUMAN_REVIEW,
                Severity.HIGH,
                "summary",
                List.of(new TriageResponse.Analysis(
                        "v1",
                        "quantity",
                        RootCause.IMPLAUSIBLE_VALUE,
                        "x",
                        Severity.HIGH,
                        Classification.NEEDS_HUMAN_REVIEW,
                        0.9,
                        null,
                        "Check it")),
                meta());

        assertThatThrownBy(() -> applier.apply(
                        withValues(Map.of()),
                        List.of(violation("v1", "quantity", ErrorCode.OUT_OF_RANGE, "999999")),
                        review))
                .isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyTheApplierCanBuildACorrectedResult() {
        for (Constructor<?> constructor : Corrected.class.getDeclaredConstructors()) {
            assertThat(Modifier.isPublic(constructor.getModifiers())).isFalse();
            assertThat(Modifier.isProtected(constructor.getModifiers())).isFalse();
        }
    }

    @Test
    void policyDecisionHasTheShapeOfDocs05Section5() {
        Rejected rejected = (Rejected) apply(List.of(new Case(
                "country",
                ErrorCode.INVALID_ENUM_VALUE,
                "Austria",
                Operation.MAP_TO_ENUM,
                "Australia",
                Map.of(),
                0.99)));

        JsonNode json = JsonMapper.builder().build().valueToTree(rejected.decision());

        assertThat(json.propertyNames()).containsExactly("approved", "stage", "violations");
        assertThat(json.get("stage").asString()).isEqualTo("VERIFICATION");
        JsonNode violation = json.get("violations").get(0);
        assertThat(violation.propertyNames())
                .containsExactly("violationId", "approved", "rejectionReason", "verification");
        assertThat(violation.get("verification").propertyNames()).containsExactly("passed", "code", "detail");
        assertThat(violation.get("verification").get("code").asString()).isEqualTo("UNVERIFIABLE_MAPPING");
    }

    private CorrectionApplier.Result apply(List<Case> cases) {
        return apply(withValues(values(cases)), cases);
    }

    private CorrectionApplier.Result apply(Map<String, String> fields, List<Case> cases) {
        List<TriageRequest.Violation> sent = new ArrayList<>();
        List<TriageResponse.Analysis> analyses = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            String id = "v" + (i + 1);
            sent.add(violation(id, c.field(), c.code(), c.value()));
            analyses.add(
                    analysis(id, c.field(), c.operation(), c.value(), c.proposed(), c.parameters(), c.confidence()));
        }
        return applier.apply(fields, sent, response(analyses));
    }

    private static Map<String, String> values(List<Case> cases) {
        Map<String, String> values = new HashMap<>();
        cases.forEach(c -> values.put(c.field(), c.value()));
        return values;
    }

    /** A valid record in canonical field names, with some values replaced. */
    private static Map<String, String> withValues(Map<String, String> replaced) {
        Map<String, String> fields = new HashMap<>(Map.of(
                "invoice_no", "536365",
                "stock_code", "85123A",
                "description", "WHITE HANGING HEART T-LIGHT HOLDER",
                "quantity", "6",
                "invoice_date", "2010-12-01 08:26:00",
                "unit_price", "2.55",
                "customer_id", "17850",
                "country", "United Kingdom"));
        fields.putAll(replaced);
        return fields;
    }

    private static Map<String, @Nullable Object> numberParameters(String decimal, @Nullable String grouping) {
        Map<String, @Nullable Object> parameters = new HashMap<>();
        parameters.put("decimalSeparator", decimal);
        parameters.put("groupingSeparator", grouping);
        return parameters;
    }

    private static TriageRequest.Violation violation(String id, String field, ErrorCode code, @Nullable String value) {
        return new TriageRequest.Violation(id, field, null, code, value, Map.of(), "technical message", null);
    }

    private static TriageResponse.Analysis analysis(
            String id,
            String field,
            Operation operation,
            @Nullable String original,
            @Nullable String proposed,
            Map<String, @Nullable Object> parameters,
            double confidence) {
        return new TriageResponse.Analysis(
                id,
                field,
                RootCause.FORMAT_MISMATCH,
                "explanation of " + field,
                Severity.LOW,
                Classification.AUTO_FIXABLE,
                confidence,
                new TriageResponse.Correction(operation, original, proposed, parameters),
                null);
    }

    private static TriageResponse response(List<TriageResponse.Analysis> analyses) {
        return new TriageResponse(REQUEST_ID, Classification.AUTO_FIXABLE, Severity.LOW, "summary", analyses, meta());
    }

    private static TriageResponse.Meta meta() {
        return new TriageResponse.Meta("mock", "mock-rules-v1", "triage_v1", 3, false, null, null, 0);
    }
}
