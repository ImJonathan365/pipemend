package com.pipemend.pipeline.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pipemend.pipeline.triage.contract.Classification;
import com.pipemend.pipeline.triage.contract.Operation;
import com.pipemend.pipeline.triage.contract.RootCause;
import com.pipemend.pipeline.triage.contract.Severity;
import com.pipemend.pipeline.triage.contract.TriageJson;
import com.pipemend.pipeline.triage.contract.TriageJson.InvalidTriageJsonException;
import com.pipemend.pipeline.triage.contract.TriageResponse;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The deserialization rules of docs/06 and docs/04 section 6.1, measured on the real parser. */
class TriageJsonTest {

    private static final String VALID = "triage-response.auto-fixable.json";
    private static final String HUMAN = "triage-response.needs-human-review.json";

    private static OpenApiShape spec;

    @BeforeAll
    static void setUp() throws Exception {
        spec = new OpenApiShape();
    }

    private static ObjectNode meta(ObjectNode response) {
        return (ObjectNode) response.get("meta");
    }

    private static ObjectNode firstAnalysis(ObjectNode response) {
        return (ObjectNode) response.get("analyses").get(0);
    }

    private static ObjectNode correction(ObjectNode response) {
        return (ObjectNode) firstAnalysis(response).get("correction");
    }

    private static String changed(String fixture, Consumer<ObjectNode> change) {
        ObjectNode response = TriageResponseFixtures.tree(fixture);
        change.accept(response);
        return response.toString();
    }

    private static void assertRejected(String body) {
        assertThatThrownBy(() -> TriageJson.parse(body)).isInstanceOf(InvalidTriageJsonException.class);
    }

    static Stream<String> validResponses() {
        return TriageResponseFixtures.responses().stream()
                .filter(entry -> entry.get("expected").asString().equals("valid"))
                .map(entry -> entry.get("fixture").asString());
    }

    @ParameterizedTest
    @MethodSource("validResponses")
    void aValidFixtureIsRead(String fixture) {
        assertThat(TriageJson.parse(TriageResponseFixtures.text(fixture)).analyses())
                .isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "triage-response.invalid.unknown-root-cause.json",
                "triage-response.invalid.unknown-operation.json",
                "triage-response.invalid.missing-summary.json"
            })
    void ac_12_3_anUnknownEnumOrAMissingFieldNeverReachesTheValidator(String fixture) {
        assertRejected(TriageResponseFixtures.text(fixture));
    }

    /** A key that admits null, the node that holds it, and a fixture where that node exists. */
    static Stream<Arguments> keysThatAdmitNull() {
        return Stream.of(
                Arguments.of("correction", "analysis", HUMAN),
                Arguments.of("suggestedHumanAction", "analysis", VALID),
                Arguments.of("originalValue", "correction", VALID),
                Arguments.of("proposedValue", "correction", VALID),
                Arguments.of("inputTokens", "meta", VALID),
                Arguments.of("outputTokens", "meta", VALID));
    }

    private static ObjectNode holder(ObjectNode response, String holder) {
        return switch (holder) {
            case "analysis" -> firstAnalysis(response);
            case "correction" -> correction(response);
            default -> meta(response);
        };
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keysThatAdmitNull")
    void anAbsentKeyFailsAlthoughItAdmitsNull(String key, String holder, String fixture) {
        assertRejected(changed(fixture, response -> holder(response, holder).remove(key)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keysThatAdmitNull")
    void anExplicitNullPassesWhereTheSpecAllowsIt(String key, String holder, String fixture) {
        String body = changed(fixture, response -> holder(response, holder).putNull(key));

        assertThatCode(() -> TriageJson.parse(body)).doesNotThrowAnyException();
    }

    static Stream<Arguments> keysThatDoNotAdmitNull() {
        return Stream.of(
                Arguments.of("requestId", "response"),
                Arguments.of("recordDecision", "response"),
                Arguments.of("maxSeverity", "response"),
                Arguments.of("summary", "response"),
                Arguments.of("analyses", "response"),
                Arguments.of("meta", "response"),
                Arguments.of("violationId", "analysis"),
                Arguments.of("field", "analysis"),
                Arguments.of("rootCause", "analysis"),
                Arguments.of("explanation", "analysis"),
                Arguments.of("severity", "analysis"),
                Arguments.of("classification", "analysis"),
                Arguments.of("confidence", "analysis"),
                Arguments.of("operation", "correction"),
                Arguments.of("parameters", "correction"),
                Arguments.of("provider", "meta"),
                Arguments.of("model", "meta"),
                Arguments.of("promptVersion", "meta"),
                Arguments.of("latencyMs", "meta"),
                Arguments.of("cacheHit", "meta"),
                Arguments.of("llmCalls", "meta"));
    }

    private static ObjectNode holderOrResponse(ObjectNode response, String holder) {
        return holder.equals("response") ? response : holder(response, holder);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keysThatDoNotAdmitNull")
    void aRequiredKeyWithoutNullFailsAbsentAndNull(String key, String holder) {
        assertRejected(
                changed(VALID, response -> holderOrResponse(response, holder).remove(key)));
        assertRejected(
                changed(VALID, response -> holderOrResponse(response, holder).putNull(key)));
    }

    @Test
    void aNullAnalysisInTheListFails() {
        assertRejected(changed(VALID, response -> response.withArray("analyses").insertNull(0)));
    }

    @Test
    void unknownFieldsAreIgnoredAtEveryLevel() {
        String body = changed(VALID, response -> {
            response.put("newField", 1);
            firstAnalysis(response).put("newField", "x");
            correction(response).putObject("newField").put("a", 1);
            meta(response).put("newField", true);
        });

        assertThat(TriageJson.parse(body).analyses()).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {" {}", " x", " 1", "}", " []", " null"})
    void contentAfterTheJsonFails(String trailing) {
        assertRejected(TriageResponseFixtures.text(VALID) + trailing);
    }

    @Test
    void whitespaceAfterTheJsonIsNotContent() {
        assertThat(TriageJson.parse(TriageResponseFixtures.text(VALID) + "\n  \n"))
                .isNotNull();
    }

    @Test
    void aRepeatedKeyFails() {
        String body = TriageResponseFixtures.text(VALID).replaceFirst("\\{", "{\"summary\":\"other\",");

        assertRejected(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "", "   ", "[]", "<html>", "{", "1", "\"text\""})
    void whatIsNotAnObjectFails(String body) {
        assertRejected(body);
    }

    static Stream<Arguments> wrongTypes() {
        return Stream.of(
                Arguments.of("an enum as a number", (Consumer<ObjectNode>) r -> r.put("maxSeverity", 0)),
                Arguments.of("an unknown enum", (Consumer<ObjectNode>) r -> r.put("maxSeverity", "SEVERE")),
                Arguments.of("an enum in lower case", (Consumer<ObjectNode>) r -> r.put("maxSeverity", "low")),
                Arguments.of("a text as a number", (Consumer<ObjectNode>) r -> r.put("summary", 5)),
                Arguments.of("a text as a boolean", (Consumer<ObjectNode>) r -> r.put("summary", true)),
                Arguments.of("a text as an object", (Consumer<ObjectNode>) r -> r.putObject("summary")),
                Arguments.of("a bad uuid", (Consumer<ObjectNode>) r -> r.put("requestId", "nope")),
                Arguments.of("an empty uuid", (Consumer<ObjectNode>) r -> r.put("requestId", "")),
                Arguments.of("analyses as a text", (Consumer<ObjectNode>) r -> r.put("analyses", "")),
                Arguments.of("meta as a text", (Consumer<ObjectNode>) r -> r.put("meta", "")),
                Arguments.of("confidence as a text", (Consumer<ObjectNode>)
                        r -> firstAnalysis(r).put("confidence", "0.97")),
                Arguments.of("confidence as a boolean", (Consumer<ObjectNode>)
                        r -> firstAnalysis(r).put("confidence", true)),
                Arguments.of("correction as a text", (Consumer<ObjectNode>)
                        r -> firstAnalysis(r).put("correction", "")),
                Arguments.of("parameters as a text", (Consumer<ObjectNode>)
                        r -> correction(r).put("parameters", "")),
                Arguments.of("latency as a text", (Consumer<ObjectNode>) r -> meta(r).put("latencyMs", "3")),
                Arguments.of("latency as a decimal", (Consumer<ObjectNode>) r -> meta(r).put("latencyMs", 3.5)),
                Arguments.of("cacheHit as a text", (Consumer<ObjectNode>) r -> meta(r).put("cacheHit", "false")),
                Arguments.of("cacheHit as a number", (Consumer<ObjectNode>) r -> meta(r).put("cacheHit", 0)),
                Arguments.of("tokens as a text", (Consumer<ObjectNode>) r -> meta(r).put("inputTokens", "1")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongTypes")
    void aWrongTypeIsNeverCoerced(String name, Consumer<ObjectNode> change) {
        assertRejected(changed(VALID, change));
    }

    @Test
    void aWholeNumberIsAValidConfidenceAsInJson() {
        String body = changed(VALID, response -> firstAnalysis(response).put("confidence", 1));

        assertThat(TriageJson.parse(body).analyses().getFirst().confidence()).isEqualTo(1.0);
    }

    @Test
    void aUuidInCapitalsIsTheSameUuid() {
        TriageResponse lower = TriageJson.parse(TriageResponseFixtures.text(VALID));
        String body = changed(
                VALID,
                response ->
                        response.put("requestId", lower.requestId().toString().toUpperCase()));

        assertThat(TriageJson.parse(body).requestId()).isEqualTo(lower.requestId());
    }

    @Test
    void theErrorSaysNothingOfWhatWasReceived() {
        String secret = "VALUE-OF-THE-ROW-536520";
        String body = changed(VALID, response -> response.put("maxSeverity", secret));

        assertThatThrownBy(() -> TriageJson.parse(body))
                .isInstanceOf(InvalidTriageJsonException.class)
                .hasNoCause()
                .hasMessageNotContaining(secret);
        assertThatThrownBy(() -> TriageJson.parse(secret)).hasMessageNotContaining(secret);
    }

    private static List<String> specEnum(String name) {
        return StreamSupport.stream(spec.schema(name).get("enum").spliterator(), false)
                .map(JsonNode::asString)
                .toList();
    }

    private static List<String> names(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    }

    /** In order, not as a set: the order of Severity is what maxSeverity is computed from. */
    @Test
    void theEnumsAreTheOnesOfTheSpecInTheSameOrder() {
        assertThat(names(Severity.class)).containsExactlyElementsOf(specEnum("Severity"));
        assertThat(names(Classification.class)).containsExactlyElementsOf(specEnum("Classification"));
        assertThat(names(RootCause.class)).containsExactlyElementsOf(specEnum("RootCause"));
        assertThat(names(Operation.class)).containsExactlyElementsOf(specEnum("Operation"));
    }

    static Stream<Arguments> records() {
        return Stream.of(
                Arguments.of(TriageResponse.class, "TriageResponse"),
                Arguments.of(TriageResponse.Analysis.class, "Analysis"),
                Arguments.of(TriageResponse.Correction.class, "Correction"),
                Arguments.of(TriageResponse.Meta.class, "Meta"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("records")
    void everyComponentIsRequiredAndIsOneOfTheSpec(Class<?> type, String schema) {
        List<String> required = new ArrayList<>();
        spec.schema(schema).get("required").forEach(key -> required.add(key.asString()));
        List<String> properties = new ArrayList<>();
        spec.schema(schema).get("properties").propertyNames().forEach(properties::add);
        List<String> marked = Arrays.stream(type.getRecordComponents())
                .filter(component -> isRequired(type, component))
                .map(RecordComponent::getName)
                .toList();

        assertThat(marked).containsExactlyInAnyOrderElementsOf(required);
        assertThat(marked).containsExactlyInAnyOrderElementsOf(properties);
    }

    /** Read from the field: @JsonProperty does not target record components, so it does not stay on them. */
    private static boolean isRequired(Class<?> type, RecordComponent component) {
        try {
            JsonProperty annotation = type.getDeclaredField(component.getName()).getAnnotation(JsonProperty.class);
            return annotation != null && annotation.required();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = TriageJson.class)
    static class ScansTheContractPackage {}

    @Test
    void theStrictMapperIsNotABeanAndLeavesTheOneOfBootAsItWas() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(ScansTheContractPackage.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TriageJson.class);
                    assertThat(context).hasSingleBean(JsonMapper.class);
                    JsonMapper boot = context.getBean(JsonMapper.class);
                    TriageJson.parse(TriageResponseFixtures.text(VALID));

                    // Lax where TriageJson is strict, so a change in the bean of Boot would show here.
                    assertThat(boot.readValue("0", Severity.class)).isEqualTo(Severity.LOW);
                    assertThat(boot.readValue("\"0.97\"", Double.class)).isEqualTo(0.97);
                });
    }

    @Test
    void theStrictMapperCannotBeReachedFromOutside() {
        for (Field field : TriageJson.class.getDeclaredFields()) {
            if (field.getType() == JsonMapper.class) {
                assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
            }
        }
        for (Method method : TriageJson.class.getDeclaredMethods()) {
            assertThat(method.getReturnType()).isNotEqualTo(JsonMapper.class);
        }
    }
}
