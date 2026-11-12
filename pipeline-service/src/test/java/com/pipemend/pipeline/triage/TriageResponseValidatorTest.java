package com.pipemend.pipeline.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipemend.pipeline.triage.TriageResponseValidator.Issue;
import com.pipemend.pipeline.triage.TriageResponseValidator.Result;
import com.pipemend.pipeline.triage.contract.Classification;
import com.pipemend.pipeline.triage.contract.Severity;
import com.pipemend.pipeline.triage.contract.TriageJson;
import com.pipemend.pipeline.triage.contract.TriageRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

/** AC-08.7 and the limits of docs/06 on the 18 response fixtures, or on a private copy of a valid one. */
class TriageResponseValidatorTest {

    private static final String FIXABLE = "triage-response.auto-fixable.json";
    private static final String HUMAN = "triage-response.needs-human-review.json";
    private static final String EMOJI = "😀";

    /** Each fixture with the one reason it exists for, so that none is rejected by accident. */
    private static final Map<String, Issue> REJECTED = Map.ofEntries(
            Map.entry("request-id-mismatch", Issue.REQUEST_ID),
            Map.entry("missing-analysis", Issue.ANALYSES_NOT_THE_VIOLATIONS),
            Map.entry("extra-analysis", Issue.ANALYSES_NOT_THE_VIOLATIONS),
            Map.entry("wrong-order", Issue.ANALYSES_NOT_THE_VIOLATIONS),
            Map.entry("duplicate-violation-id", Issue.ANALYSES_NOT_THE_VIOLATIONS),
            Map.entry("field-mismatch", Issue.FIELD),
            Map.entry("original-value-mismatch", Issue.ORIGINAL_VALUE),
            Map.entry("correction-on-needs-human-review", Issue.CORRECTION_CLASSIFICATION),
            Map.entry("auto-fixable-without-correction", Issue.CORRECTION_CLASSIFICATION),
            Map.entry("record-decision-incoherent", Issue.RECORD_DECISION),
            Map.entry("max-severity-incoherent", Issue.MAX_SEVERITY));

    private static final Set<String> REJECTED_BY_THE_PARSER =
            Set.of("unknown-root-cause", "unknown-operation", "missing-summary");

    private static String fixtureName(String shortName) {
        return "triage-response.invalid." + shortName + ".json";
    }

    private static Result validate(String fixture, String body) {
        return TriageResponseValidator.validate(TriageResponseFixtures.requestOf(fixture), TriageJson.parse(body));
    }

    private static Result changed(String fixture, Consumer<ObjectNode> change) {
        ObjectNode response = TriageResponseFixtures.tree(fixture);
        change.accept(response);
        return validate(fixture, response.toString());
    }

    private static ObjectNode analysis(ObjectNode response, int index) {
        return (ObjectNode) response.get("analyses").get(index);
    }

    private static Result.Invalid onlyIssue(Issue issue) {
        return new Result.Invalid(Set.of(issue));
    }

    @Test
    void everyResponseFixtureIsAccountedFor() {
        Set<String> assigned = new HashSet<>();
        REJECTED.keySet().forEach(name -> assigned.add(fixtureName(name)));
        REJECTED_BY_THE_PARSER.forEach(name -> assigned.add(fixtureName(name)));
        validFixtures().forEach(assigned::add);

        assertThat(TriageResponseFixtures.responses()).hasSize(18);
        assertThat(TriageResponseFixtures.responses().stream()
                        .map(entry -> entry.get("fixture").asString()))
                .containsExactlyInAnyOrderElementsOf(assigned);
    }

    static Stream<String> validFixtures() {
        return TriageResponseFixtures.responses().stream()
                .filter(entry -> entry.get("expected").asString().equals("valid"))
                .map(entry -> entry.get("fixture").asString());
    }

    @ParameterizedTest
    @MethodSource("validFixtures")
    void aValidFixtureIsValid(String fixture) {
        assertThat(validate(fixture, TriageResponseFixtures.text(fixture))).isInstanceOf(Result.Valid.class);
    }

    static Stream<Arguments> rejectedFixtures() {
        return REJECTED.entrySet().stream().map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedFixtures")
    void ac_08_7_aFixtureThatBreaksTheContractIsRejectedForItsReason(String name, Issue issue) {
        String fixture = fixtureName(name);

        assertThat(validate(fixture, TriageResponseFixtures.text(fixture))).isEqualTo(onlyIssue(issue));
    }

    @Test
    void ac_08_7_theResultCarriesTheComputedDecisionAndSeverity() {
        assertThat(validate(FIXABLE, TriageResponseFixtures.text(FIXABLE)))
                .isEqualTo(new Result.Valid(Classification.AUTO_FIXABLE, Severity.LOW));
        assertThat(validate(HUMAN, TriageResponseFixtures.text(HUMAN)))
                .isEqualTo(new Result.Valid(Classification.NEEDS_HUMAN_REVIEW, Severity.HIGH));
    }

    @Test
    void ac_08_7_maxSeverityFollowsTheScaleNotTheAlphabet() {
        Result result = changed(FIXABLE, response -> {
            analysis(response, 0).put("severity", "HIGH");
            analysis(response, 1).put("severity", "LOW");
            analysis(response, 2).put("severity", "MEDIUM");
            response.put("maxSeverity", "HIGH");
        });

        assertThat(result).isEqualTo(new Result.Valid(Classification.AUTO_FIXABLE, Severity.HIGH));
    }

    @Test
    void ac_08_7_oneHumanReviewMakesTheWholeRecordOne() {
        Result result = changed(FIXABLE, response -> {
            analysis(response, 1).put("classification", "NEEDS_HUMAN_REVIEW");
            analysis(response, 1).putNull("correction");
            analysis(response, 1).put("suggestedHumanAction", "Check it.");
            response.put("recordDecision", "NEEDS_HUMAN_REVIEW");
        });

        assertThat(result).isEqualTo(new Result.Valid(Classification.NEEDS_HUMAN_REVIEW, Severity.LOW));
    }

    @Test
    void ac_08_7_aNullReceivedValueMatchesANullOriginalValue() {
        TriageRequest sent = TriageResponseFixtures.requestOf(FIXABLE);
        TriageRequest.Violation first = sent.violations().getFirst();
        TriageRequest.Violation withNull = new TriageRequest.Violation(
                first.violationId(),
                first.field(),
                first.sourceHeader(),
                first.errorCode(),
                null,
                first.expected(),
                first.technicalMessage(),
                first.exceptionType());
        TriageRequest request = new TriageRequest(
                sent.requestId(),
                sent.batchId(),
                sent.schema(),
                sent.source(),
                sent.record(),
                java.util.List.of(
                        withNull, sent.violations().get(1), sent.violations().get(2)),
                sent.options());
        ObjectNode response = TriageResponseFixtures.tree(FIXABLE);

        assertThat(TriageResponseValidator.validate(request, TriageJson.parse(response.toString())))
                .isEqualTo(onlyIssue(Issue.ORIGINAL_VALUE));

        ((ObjectNode) analysis(response, 0).get("correction")).putNull("originalValue");
        assertThat(TriageResponseValidator.validate(request, TriageJson.parse(response.toString())))
                .isInstanceOf(Result.Valid.class);
    }

    @Test
    void noAnalysesAtAllIsNeverValid() {
        Result result = changed(FIXABLE, response -> response.putArray("analyses"));

        assertThat(result).isEqualTo(onlyIssue(Issue.ANALYSES_NOT_THE_VIOLATIONS));
    }

    @Test
    void noAnalysesIsNeverValidEvenAgainstARequestWithoutViolations() {
        TriageRequest sent = TriageResponseFixtures.requestOf(FIXABLE);
        TriageRequest empty = new TriageRequest(
                sent.requestId(),
                sent.batchId(),
                sent.schema(),
                sent.source(),
                sent.record(),
                List.of(),
                sent.options());
        ObjectNode response = TriageResponseFixtures.tree(FIXABLE);
        response.put("requestId", sent.requestId().toString());
        response.putArray("analyses");

        assertThat(TriageResponseValidator.validate(empty, TriageJson.parse(response.toString())))
                .isEqualTo(onlyIssue(Issue.ANALYSES_NOT_THE_VIOLATIONS));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.5, 1.0})
    void aConfidenceBetweenZeroAndOneIsValid(double confidence) {
        assertThat(changed(FIXABLE, response -> analysis(response, 0).put("confidence", confidence)))
                .isInstanceOf(Result.Valid.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.01, 1.01, 1.5})
    void aConfidenceOutOfRangeIsRejected(double confidence) {
        assertThat(changed(FIXABLE, response -> analysis(response, 0).put("confidence", confidence)))
                .isEqualTo(onlyIssue(Issue.CONFIDENCE));
    }

    @Test
    void aConfidenceThatOverflowsADoubleIsRejected() {
        String body =
                TriageResponseFixtures.text(FIXABLE).replaceFirst("\"confidence\": 0.97", "\"confidence\": 1e400");

        assertThat(body).contains("1e400");
        assertThat(validate(FIXABLE, body)).isEqualTo(onlyIssue(Issue.CONFIDENCE));
    }

    /** ai-service only rejects the empty string; this is stricter on purpose: fail closed. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t\n"})
    void aBlankHumanActionIsRejected(String action) {
        assertThat(changed(HUMAN, response -> analysis(response, 0).put("suggestedHumanAction", action)))
                .isEqualTo(onlyIssue(Issue.HUMAN_ACTION));
    }

    @Test
    void aNullHumanActionIsRejected() {
        assertThat(changed(HUMAN, response -> analysis(response, 0).putNull("suggestedHumanAction")))
                .isEqualTo(onlyIssue(Issue.HUMAN_ACTION));
    }

    @Test
    void aFixableAnalysisNeedsNoHumanAction() {
        assertThat(changed(FIXABLE, response -> analysis(response, 0).putNull("suggestedHumanAction")))
                .isInstanceOf(Result.Valid.class);
    }

    @Test
    void aNullProposalIsRejectedOutsideNullifyToken() {
        Result result = changed(
                FIXABLE, response -> ((ObjectNode) analysis(response, 0).get("correction")).putNull("proposedValue"));

        assertThat(result).isEqualTo(onlyIssue(Issue.PROPOSED_VALUE));
    }

    @Test
    void aNullProposalIsValidWithNullifyToken() {
        Result result = changed(FIXABLE, response -> {
            ObjectNode correction = (ObjectNode) analysis(response, 0).get("correction");
            correction.put("operation", "NULLIFY_TOKEN");
            correction.putNull("proposedValue");
        });

        assertThat(result).isInstanceOf(Result.Valid.class);
    }

    /** Emoji are two chars each: only counting code points lets the maximum pass and the next one fail. */
    @ParameterizedTest
    @ValueSource(strings = {"x", EMOJI})
    void theSummaryLimitCountsCodePoints(String unit) {
        assertThat(changed(FIXABLE, response -> response.put("summary", unit.repeat(1000))))
                .isInstanceOf(Result.Valid.class);
        assertThat(changed(FIXABLE, response -> response.put("summary", unit.repeat(1001))))
                .isEqualTo(onlyIssue(Issue.TEXT_TOO_LONG));
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", EMOJI})
    void theExplanationLimitCountsCodePoints(String unit) {
        assertThat(changed(FIXABLE, response -> analysis(response, 2).put("explanation", unit.repeat(800))))
                .isInstanceOf(Result.Valid.class);
        assertThat(changed(FIXABLE, response -> analysis(response, 2).put("explanation", unit.repeat(801))))
                .isEqualTo(onlyIssue(Issue.TEXT_TOO_LONG));
    }

    @ParameterizedTest
    @ValueSource(strings = {"latencyMs", "llmCalls", "inputTokens", "outputTokens"})
    void aNegativeValueInMetaIsRejectedAndZeroIsNot(String key) {
        assertThat(changed(FIXABLE, response -> ((ObjectNode) response.get("meta")).put(key, -1)))
                .isEqualTo(onlyIssue(Issue.NEGATIVE_META));
        assertThat(changed(FIXABLE, response -> ((ObjectNode) response.get("meta")).put(key, 0)))
                .isInstanceOf(Result.Valid.class);
    }

    @Test
    void everyIssueIsReportedNotOnlyTheFirst() {
        Result result = changed(FIXABLE, response -> {
            response.put("requestId", "00000000-0000-0000-0000-000000000000");
            response.put("summary", "x".repeat(1001));
            analysis(response, 0).put("confidence", 2);
        });

        assertThat(result)
                .isEqualTo(new Result.Invalid(Set.of(Issue.REQUEST_ID, Issue.TEXT_TOO_LONG, Issue.CONFIDENCE)));
    }
}
