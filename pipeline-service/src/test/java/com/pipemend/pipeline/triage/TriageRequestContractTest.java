package com.pipemend.pipeline.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipemend.pipeline.batch.RawRecord;
import com.pipemend.pipeline.batch.RecordProcessor;
import com.pipemend.pipeline.batch.RecordTestSupport;
import com.pipemend.pipeline.schema.FieldRule;
import com.pipemend.pipeline.schema.FieldSpec;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.schema.SchemaTestSupport;
import com.pipemend.pipeline.triage.contract.TriageRequest;
import com.pipemend.pipeline.validation.ErrorCode;
import com.pipemend.pipeline.validation.Validator;
import com.pipemend.pipeline.validation.Violation;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The request against contracts/: the fixtures both ways and the spec that ai-service validates with. */
class TriageRequestContractTest {

    private static final SchemaDefinition SCHEMA = SchemaTestSupport.realSchema();
    private static final UUID REQUEST = UUID.fromString("0b6c3f5e-7f0f-4a55-9d0e-2a1f0f1b6a10");
    private static final Validator VALIDATOR = new Validator(SCHEMA, RecordTestSupport.CLOCK);

    private static JsonMapper json;
    private static OpenApiShape spec;

    @BeforeAll
    static void setUp() throws Exception {
        // The JsonMapper that Boot configures, because that is the one the application serializes with.
        AtomicReference<JsonMapper> mapper = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> mapper.set(context.getBean(JsonMapper.class)));
        json = mapper.get();
        spec = new OpenApiShape();
    }

    private static JsonNode fixture(String name) throws Exception {
        return JsonMapper.builder()
                .build()
                .readTree(Files.readString(
                        OpenApiShape.CONTRACTS.resolve("fixtures").resolve(name)));
    }

    /** The request fixtures the index marks as valid; no fixture is written or regenerated here. */
    static Stream<String> validRequestFixtures() throws Exception {
        return StreamSupport.stream(fixture("index.json").spliterator(), false)
                .filter(entry -> entry.get("kind").asString().equals("request"))
                .filter(entry -> entry.get("expected").asString().equals("valid"))
                .map(entry -> entry.get("fixture").asString());
    }

    @Test
    void thereAreFourValidRequestFixtures() throws Exception {
        assertThat(validRequestFixtures()).hasSize(4);
    }

    @ParameterizedTest
    @MethodSource("validRequestFixtures")
    void aRequestFixtureIsReadAndWrittenBackIdentical(String name) throws Exception {
        JsonNode fixture = fixture(name);

        TriageRequest request = json.treeToValue(fixture, TriageRequest.class);

        JsonNode written = json.valueToTree(request);

        assertThat(written).isEqualTo(fixture);
    }

    @ParameterizedTest
    @MethodSource("validRequestFixtures")
    void aRequestFixtureMatchesTheSpec(String name) throws Exception {
        assertThat(spec.errors(fixture(name), "TriageRequest")).isEmpty();
    }

    @Test
    void theShapeCheckRejectsWhatPydanticRejects() throws Exception {
        assertThat(spec.errors(fixture("triage-request.invalid.unknown-field.json"), "TriageRequest"))
                .isNotEmpty();
        assertThat(spec.errors(tree(Map.of("expectedColumns", "8")), "Expected"))
                .isNotEmpty();
        assertThat(spec.errors(tree(Map.of("nope", 1)), "Expected")).isNotEmpty();
        assertThat(spec.errors(tree(Map.of("allowedValues", List.of(1))), "Expected"))
                .isNotEmpty();
        assertThat(spec.errors(tree(Map.of("scale", "2")), "Expected")).isNotEmpty();
    }

    private static JsonNode tree(Object value) {
        return json.valueToTree(value);
    }

    /** A value that breaks the rule of each field of the real YAML, so a new rule type cannot go unchecked. */
    private static String breaking(FieldSpec field) {
        return switch (field.rule()) {
            case FieldRule.StringRule rule -> rule.pattern() != null ? "!!" : "x".repeat(rule.maxLength() + 1);
            case FieldRule.IntegerRule rule -> "abc";
            case FieldRule.DecimalRule rule -> "abc";
            case FieldRule.DateTimeRule rule -> "not a date";
            case FieldRule.EnumRule rule -> "Atlantis";
        };
    }

    private static Map<String, String> validValues() {
        Map<String, String> values = new HashMap<>();
        values.put("invoice_no", "536365");
        values.put("stock_code", "85123A");
        values.put("description", "WHITE HANGING HEART");
        values.put("quantity", "6");
        values.put("invoice_date", "2010-12-01 08:26:00");
        values.put("unit_price", "2.55");
        values.put("customer_id", "17850");
        values.put("country", "United Kingdom");
        return values;
    }

    private static Violation violationOf(String field, String value) {
        Map<String, String> values = validValues();
        values.put(field, value);
        return VALIDATOR.validate(values).getFirst();
    }

    static Stream<FieldSpec> fieldsOfTheRealSchema() {
        return SCHEMA.fields().stream();
    }

    @ParameterizedTest
    @MethodSource("fieldsOfTheRealSchema")
    void ac_07_1_theExpectedOfEveryFieldMatchesTheSpec(FieldSpec field) {
        Violation violation = violationOf(field.name(), breaking(field));

        assertThat(violation.field()).isEqualTo(field.name());
        JsonNode expected = tree(violation.expected());
        assertThat(expected.isEmpty()).isFalse();
        assertThat(spec.errors(expected, "Expected")).isEmpty();
    }

    @Test
    void theCheckCoversEveryRuleType() {
        Set<Class<?>> covered = new HashSet<>();
        SCHEMA.fields().forEach(field -> covered.add(field.rule().getClass()));

        assertThat(covered)
                .containsExactlyInAnyOrder(
                        FieldRule.StringRule.class,
                        FieldRule.IntegerRule.class,
                        FieldRule.DecimalRule.class,
                        FieldRule.DateTimeRule.class,
                        FieldRule.EnumRule.class);
    }

    @Test
    void ac_07_3_theJsonTypesOfExpectedAreTheOnesOfTheSpec() {
        JsonNode decimal = tree(violationOf("unit_price", "abc").expected());
        assertThat(decimal.get("type").isString()).isTrue();
        assertThat(decimal.get("min").isString()).isTrue();
        assertThat(decimal.get("max").isString()).isTrue();
        assertThat(decimal.get("scale").isIntegralNumber()).isTrue();

        JsonNode text = tree(violationOf("description", "x".repeat(256)).expected());
        assertThat(text.get("maxLength").isIntegralNumber()).isTrue();

        JsonNode enumeration = tree(violationOf("country", "Atlantis").expected());
        assertThat(enumeration.get("allowedValues").isArray()).isTrue();
        assertThat(enumeration.get("allowedValues").size()).isBetween(1, 100);
        assertThat(enumeration.get("allowedValues").get(0).isString()).isTrue();
    }

    @Test
    void aBusinessRuleViolationMatchesTheSpec() {
        Map<String, String> values = validValues();
        values.put("quantity", "-1");

        Violation violation = VALIDATOR.validate(values).getFirst();

        assertThat(violation.errorCode()).isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION);
        assertThat(spec.errors(tree(violation.expected()), "Expected")).isEmpty();
    }

    private static TriageRequest captured(RawRecord record) {
        List<TriageRequest> requests = new ArrayList<>();
        RecordProcessor processor = RecordTestSupport.processor(REQUEST, SCHEMA, request -> {
            requests.add(request);
            return new TriageOutcome.Unavailable("captured");
        });
        processor.process(record);
        return requests.getFirst();
    }

    @Test
    void ac_07_4_aMalformedRowWithACountEmitsBothColumnsAsNumbers() {
        TriageRequest request = captured(new RawRecord.Malformed(3, "a,b,c", "Expected 8 columns, found 3", 8, 3));

        JsonNode expected = tree(request.violations().getFirst().expected());
        assertThat(expected.propertyNames()).containsExactlyInAnyOrder("expectedColumns", "receivedColumns");
        assertThat(expected.get("expectedColumns").isIntegralNumber()).isTrue();
        assertThat(expected.get("receivedColumns").isIntegralNumber()).isTrue();
        assertThat(spec.errors(expected, "Expected")).isEmpty();
    }

    @Test
    void ac_07_4_aStrayQuoteEmitsReceivedColumnsAsAnExplicitNull() {
        TriageRequest request = captured(new RawRecord.Malformed(3, "1,5\" TILE", "A stray quote", 8, null));

        JsonNode expected = tree(request.violations().getFirst().expected());
        assertThat(expected.has("receivedColumns")).isTrue();
        assertThat(expected.get("receivedColumns").isNull()).isTrue();
        assertThat(spec.errors(expected, "Expected")).isEmpty();
    }

    static Stream<RawRecord> records() {
        Map<String, String> everyFieldBroken = new HashMap<>();
        Map<String, String> row = RecordTestSupport.validRow();
        for (FieldSpec field : SCHEMA.fields()) {
            everyFieldBroken.put(field.sourceHeader(), breaking(field));
        }
        row.put("Price", "");
        Map<String, String> business = RecordTestSupport.validRow();
        business.put("Quantity", "-1");
        return Stream.of(
                new RawRecord.Parsed(1, everyFieldBroken),
                new RawRecord.Parsed(2, row),
                new RawRecord.Parsed(3, business),
                new RawRecord.Malformed(4, "a,b", "Expected 8 columns, found 2", 8, 2),
                new RawRecord.Malformed(5, "x".repeat(5000), "A stray quote", 8, null));
    }

    @ParameterizedTest
    @MethodSource("records")
    void whatTheBuilderEmitsIsAcceptedByTheSpec(RawRecord record) {
        JsonNode request = tree(captured(record));

        assertThat(spec.errors(request, "TriageRequest")).isEmpty();
        // These are required although they can be null: leaving them out would be a 422.
        request.get("violations")
                .forEach(violation -> assertThat(violation.has("receivedValue")).isTrue());
    }

    @Test
    void ac_07_2_aMessageAtTheLimitIsAcceptedByTheSpec() {
        Map<String, String> row = RecordTestSupport.validRow();
        row.put("Quantity", "😀".repeat(2000));

        JsonNode request = tree(captured(new RawRecord.Parsed(1, row)));

        assertThat(spec.errors(request, "TriageRequest")).isEmpty();
    }
}
