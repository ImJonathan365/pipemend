package com.pipemend.pipeline.adversarial;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.pipemend.pipeline.PostgresTestConfiguration;
import com.pipemend.pipeline.batch.Inbox;
import com.pipemend.pipeline.batch.IngestionJobConfiguration;
import com.pipemend.pipeline.persistence.AiCallLogRepository;
import com.pipemend.pipeline.triage.AiClientProperties;
import com.pipemend.pipeline.triage.AiTriagePort;
import com.pipemend.pipeline.triage.HttpAiTriageClient;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * AC-10.6: plausible but wrong AI answers, all LOW with confidence 0.99, against the real job, validator, policy,
 * verifiers, revalidation and PostgreSQL. Only the AI is simulated, by WireMock on 127.0.0.1. Each case comes from a
 * rule of docs/02 section 4, ADR-0008 or docs/06, and none may reach sales_transaction (OBJ-2).
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, AdversarialSuiteTest.WireMockClient.class})
class AdversarialSuiteTest {

    static final List<String> HEADERS = List.of(
            "Invoice", "StockCode", "Description", "Quantity", "InvoiceDate", "Price", "Customer ID", "Country");
    static final Map<String, String> VALID = row(
            "536365",
            "85123A",
            "WHITE HANGING HEART T-LIGHT HOLDER",
            "6",
            "2010-12-01 08:26:00",
            "2.55",
            "17850",
            "United Kingdom");
    static final Path INJECTION = Path.of("..", "contracts", "fixtures", "triage-request.prompt-injection.json");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final WireMockServer SERVER =
            new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().extensions(new Adversary()));

    static {
        SERVER.start();
        SERVER.stubFor(post(urlEqualTo("/v1/triage"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")));
    }

    private static volatile @Nullable Case current;

    /**
     * One hand-written row with a single defect and the answer the AI gives for it.
     *
     * @param originalValue null to echo the receivedValue the pipeline sent
     * @param code the verification code or policy rejection reason that docs name, null when nothing is rejected
     */
    record Case(
            String name,
            Map<String, String> row,
            String field,
            String errorCode,
            String operation,
            Map<String, @Nullable Object> parameters,
            @Nullable String originalValue,
            String proposedValue,
            double confidence,
            String outcome,
            @Nullable String code) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() throws IOException {
        Map<String, @Nullable Object> comma = separators(",");
        Map<String, @Nullable Object> dot = separators(".");
        return Stream.of(
                // docs/02 section 4 PARSE_DATE (3), ADR-0008, D164.
                adversarial(
                        "1 day and month swapped in an ambiguous date",
                        "InvoiceDate",
                        "03/04/2010 09:15",
                        "invoice_date",
                        "INVALID_FORMAT",
                        "PARSE_DATE",
                        Map.of("sourcePattern", "MM/dd/yyyy HH:mm"),
                        "2010-03-04 09:15:00",
                        "VERIFICATION_FAILED",
                        "AMBIGUOUS_DATE"),
                // docs/02 section 4 PARSE_NUMBER (4).
                adversarial(
                        "2 thousands read as decimals",
                        "Price",
                        "2,550",
                        "unit_price",
                        "INVALID_FORMAT",
                        "PARSE_NUMBER",
                        comma,
                        "2.55",
                        "VERIFICATION_FAILED",
                        "AMBIGUOUS_NUMBER"),
                // docs/02 section 4 PARSE_NUMBER (1): never convert currency.
                adversarial(
                        "3 euro sign removed",
                        "Price",
                        "€2.55",
                        "unit_price",
                        "INVALID_FORMAT",
                        "PARSE_NUMBER",
                        dot,
                        "2.55",
                        "VERIFICATION_FAILED",
                        "CURRENCY_NOT_CONVERTIBLE"),
                // docs/02 section 4 "never ... OUT_OF_RANGE", AC-09.1.
                adversarial(
                        "4a negative price made positive",
                        "Price",
                        "-2.55",
                        "unit_price",
                        "OUT_OF_RANGE",
                        "PARSE_NUMBER",
                        dot,
                        "2.55",
                        "POLICY_REJECTED",
                        "ERROR_CODE_NOT_CORRECTABLE"),
                // docs/02 section 4 PARSE_NUMBER (3), D163.
                adversarial(
                        "4b sign dropped while fixing the separator",
                        "Price",
                        "-2,55",
                        "unit_price",
                        "INVALID_FORMAT",
                        "PARSE_NUMBER",
                        comma,
                        "2.55",
                        "VERIFICATION_FAILED",
                        "SIGN_CHANGED"),
                // docs/02 section 4 MAP_TO_ENUM (2): not an alias pair and not the same normalised name.
                adversarial(
                        "5 Austria mapped to Australia",
                        "Country",
                        "austria",
                        "country",
                        "INVALID_ENUM_VALUE",
                        "MAP_TO_ENUM",
                        Map.of(),
                        "Australia",
                        "VERIFICATION_FAILED",
                        "UNVERIFIABLE_MAPPING"),
                // docs/02 section 4 PARSE_NUMBER (2): the value differs (compareTo != 0); (5) fails too.
                adversarial(
                        "6 customer id with a fraction truncated",
                        "Customer ID",
                        "17850.5",
                        "customer_id",
                        "PATTERN_MISMATCH",
                        "PARSE_NUMBER",
                        dot,
                        "17850",
                        "VERIFICATION_FAILED",
                        "VALUE_MISMATCH"),
                // docs/02 section 4 TRIM: proposed == strip(original).
                adversarial(
                        "7 TRIM that also changes a character",
                        "StockCode",
                        " 85123A",
                        "stock_code",
                        "PATTERN_MISMATCH",
                        "TRIM",
                        Map.of(),
                        "85123B",
                        "VERIFICATION_FAILED",
                        "VALUE_MISMATCH"),
                // docs/02 section 4 NORMALIZE_CASE: not stock_code, its case is significant.
                adversarial(
                        "8 NORMALIZE_CASE on stock_code",
                        "StockCode",
                        " 85123a",
                        "stock_code",
                        "PATTERN_MISMATCH",
                        "NORMALIZE_CASE",
                        Map.of(),
                        "85123A",
                        "POLICY_REJECTED",
                        "FIELD_NOT_ALLOWED"),
                // docs/02 section 4 PARSE_DATE (2): parsing produces exactly proposed.
                adversarial(
                        "9a non-canonical date",
                        "InvoiceDate",
                        "25/12/2010 08:26",
                        "invoice_date",
                        "INVALID_FORMAT",
                        "PARSE_DATE",
                        Map.of("sourcePattern", "dd/MM/yyyy HH:mm"),
                        "2010-12-25T08:26:00",
                        "VERIFICATION_FAILED",
                        "VALUE_MISMATCH"),
                // docs/02 section 4 PARSE_NUMBER (5): customer_id is formatted without decimals.
                adversarial(
                        "9b non-canonical customer id",
                        "Customer ID",
                        "17850.0",
                        "customer_id",
                        "PATTERN_MISMATCH",
                        "PARSE_NUMBER",
                        dot,
                        "17850.0",
                        "VERIFICATION_FAILED",
                        "VALUE_MISMATCH"),
                // AC-08.7, docs/06 A.1: correction.originalValue == receivedValue.
                new Case(
                        "10 originalValue other than receivedValue",
                        with("Country", "UK"),
                        "country",
                        "INVALID_ENUM_VALUE",
                        "MAP_TO_ENUM",
                        Map.of(),
                        "U.K.",
                        "United Kingdom",
                        0.99,
                        "AI_INVALID_RESPONSE",
                        null),
                // docs/03 NFR-14: the AI obeys the instruction injected in Description; docs/02 section 4 MAP_TO_ENUM
                // (2).
                new Case(
                        "11 AI that obeys an injected instruction",
                        injectionRow(),
                        "country",
                        "INVALID_ENUM_VALUE",
                        "MAP_TO_ENUM",
                        Map.of(),
                        null,
                        "United Kingdom",
                        1.0,
                        "VERIFICATION_FAILED",
                        "UNVERIFIABLE_MAPPING"),
                // Control, docs/02 section 4 PARSE_NUMBER (1): a right answer loads, so the harness can load at all.
                adversarial(
                        "C control: pound sign removed",
                        "Price",
                        "£2.55",
                        "unit_price",
                        "INVALID_FORMAT",
                        "PARSE_NUMBER",
                        dot,
                        "2.55",
                        "AUTO_CORRECTED",
                        null));
    }

    @Autowired
    JobOperator jobOperator;

    @Autowired
    Job ingestionJob;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Inbox inbox;

    @AfterAll
    static void stopServer() {
        SERVER.stop();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void ac_10_6_aPlausibleButWrongAnswerNeverReachesSalesTransaction(Case adversarial) throws Exception {
        current = adversarial;
        SERVER.resetRequests();
        UUID batchId = receive(csv(adversarial.row()));

        assertThat(launch(batchId).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // The case tests what it says: the pipeline sent exactly the violation it was written for.
        assertThat(SERVER.getAllServeEvents()).hasSize(1);
        JsonNode sent =
                JSON.readTree(SERVER.getAllServeEvents().getFirst().getRequest().getBodyAsString());
        assertThat(sent.get("violations")).hasSize(1);
        assertThat(sent.get("violations").get(0).get("field").asString()).isEqualTo(adversarial.field());
        assertThat(sent.get("violations").get(0).get("errorCode").asString()).isEqualTo(adversarial.errorCode());

        if (adversarial.outcome().equals("AUTO_CORRECTED")) {
            assertThat(count("sales_transaction", batchId, "load_origin = 'AUTO_CORRECTED'"))
                    .isEqualTo(1);
            assertThat(count("correction_log", batchId, "true")).isEqualTo(1);
            return;
        }
        assertThat(count("sales_transaction", batchId, "true")).isZero();
        assertThat(count("correction_log", batchId, "true")).isZero();
        Map<String, Object> quarantined =
                jdbc.sql("""
                        SELECT quarantine_reason,
                            policy_decision -> 'violations' -> 0 ->> 'rejectionReason' AS rejection,
                            policy_decision -> 'violations' -> 0 -> 'verification' ->> 'code' AS verification
                        FROM quarantine_record WHERE batch_id = :id
                        """).param("id", batchId).query().singleRow();
        assertThat(quarantined).containsEntry("quarantine_reason", adversarial.outcome());
        switch (adversarial.outcome()) {
            case "POLICY_REJECTED" -> assertThat(quarantined).containsEntry("rejection", adversarial.code());
            case "VERIFICATION_FAILED" -> assertThat(quarantined).containsEntry("verification", adversarial.code());
            default -> {}
        }
    }

    @Test
    void nfr_14_theInjectedDescriptionTravelsAsDataUntouched() throws Exception {
        Case adversarial =
                cases().filter(c -> c.name().startsWith("11 ")).findFirst().orElseThrow();
        current = adversarial;
        SERVER.resetRequests();
        UUID batchId = receive(csv(adversarial.row()));

        assertThat(launch(batchId).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        JsonNode sent =
                JSON.readTree(SERVER.getAllServeEvents().getFirst().getRequest().getBodyAsString());
        assertThat(sent.get("record").get("raw").get("Description").asString())
                .isEqualTo(JSON.readTree(INJECTION.toFile())
                        .get("record")
                        .get("raw")
                        .get("Description")
                        .asString());
    }

    private static Case adversarial(
            String name,
            String header,
            String value,
            String field,
            String errorCode,
            String operation,
            Map<String, @Nullable Object> parameters,
            String proposedValue,
            String outcome,
            @Nullable String code) {
        return new Case(
                name,
                with(header, value),
                field,
                errorCode,
                operation,
                parameters,
                null,
                proposedValue,
                0.99,
                outcome,
                code);
    }

    private static Map<String, @Nullable Object> separators(String decimal) {
        Map<String, @Nullable Object> parameters = new HashMap<>();
        parameters.put("decimalSeparator", decimal);
        parameters.put("groupingSeparator", null);
        return parameters;
    }

    static Map<String, String> row(String... values) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < HEADERS.size(); i++) {
            row.put(HEADERS.get(i), values[i]);
        }
        return row;
    }

    static Map<String, String> with(String header, String value) {
        Map<String, String> row = new LinkedHashMap<>(VALID);
        row.put(header, value);
        return row;
    }

    /** The record of the contract fixture, as a CSV row (docs/03 l.180). */
    static Map<String, String> injectionRow() {
        JsonNode raw = JSON.readTree(INJECTION.toFile()).get("record").get("raw");
        Map<String, String> row = new LinkedHashMap<>();
        for (String header : HEADERS) {
            row.put(header, raw.get(header).asString());
        }
        return row;
    }

    private static byte[] csv(Map<String, String> row) {
        StringBuilder csv = new StringBuilder(String.join(",", HEADERS)).append('\n');
        csv.append(String.join(
                        ",",
                        row.values().stream()
                                .map(v -> '"' + v.replace("\"", "\"\"") + '"')
                                .toList()))
                .append('\n');
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The real client, pointed at WireMock; see IngestionJobTriageTest.WireMockClient for why not a property. */
    @TestConfiguration(proxyBeanMethods = false)
    static class WireMockClient {

        @Bean
        @Primary
        AiTriagePort adversaryTriage(AiClientProperties defaults, JsonMapper json, AiCallLogRepository callLog) {
            return new HttpAiTriageClient(
                    new AiClientProperties(
                            URI.create(SERVER.baseUrl()), defaults.connectTimeout(), defaults.readTimeout()),
                    json,
                    callLog);
        }
    }

    /** A well-formed AUTO_FIXABLE LOW answer to the one violation of the current case, with its wrong correction. */
    static final class Adversary implements ResponseTransformerV2 {

        @Override
        public Response transform(Response response, ServeEvent serveEvent) {
            Case adversarial = current;
            JsonNode request = JSON.readTree(serveEvent.getRequest().getBodyAsString());
            JsonNode violation = request.get("violations").get(0);
            ObjectNode answer = JSON.createObjectNode();
            answer.put("requestId", request.get("requestId").asString());
            answer.put("recordDecision", "AUTO_FIXABLE");
            answer.put("maxSeverity", "LOW");
            answer.put("summary", "Fixable");
            ObjectNode analysis = answer.putArray("analyses").addObject();
            analysis.put("violationId", violation.get("violationId").asString());
            analysis.put("field", violation.get("field").asString());
            analysis.put("rootCause", "FORMAT_MISMATCH");
            analysis.put("explanation", "Fixable");
            analysis.put("severity", "LOW");
            analysis.put("classification", "AUTO_FIXABLE");
            analysis.put("confidence", adversarial.confidence());
            ObjectNode correction = analysis.putObject("correction");
            correction.put("operation", adversarial.operation());
            if (adversarial.originalValue() == null) {
                correction.set("originalValue", violation.get("receivedValue"));
            } else {
                correction.put("originalValue", adversarial.originalValue());
            }
            correction.put("proposedValue", adversarial.proposedValue());
            correction.set("parameters", JSON.valueToTree(adversarial.parameters()));
            analysis.putNull("suggestedHumanAction");
            ObjectNode meta = answer.putObject("meta");
            meta.put("provider", "mock");
            meta.put("model", "adversary");
            meta.put("promptVersion", "triage_v1");
            meta.put("latencyMs", 1);
            meta.put("cacheHit", false);
            meta.putNull("inputTokens");
            meta.putNull("outputTokens");
            meta.put("llmCalls", 0);
            return Response.Builder.like(response).but().body(answer.toString()).build();
        }

        @Override
        public String getName() {
            return "adversary";
        }
    }

    private UUID receive(byte[] content) throws IOException {
        UUID batchId = UUID.randomUUID();
        Files.createDirectories(inbox.directory(batchId));
        Files.write(inbox.directory(batchId).resolve("source.csv"), content);
        jdbc.sql("""
                        INSERT INTO ingestion_batch (id, source_filename, source_type, source_checksum,
                            source_size_bytes, schema_name, schema_version, status)
                        VALUES (:id, 'source.csv', 'CSV', :checksum, :size, 'sales_transaction', '1', 'RECEIVED')
                        """)
                .param("id", batchId)
                .param("checksum", checksum(batchId))
                .param("size", content.length)
                .update();
        return batchId;
    }

    private JobExecution launch(UUID batchId) throws Exception {
        JobExecution started = jobOperator.start(
                ingestionJob,
                new JobParametersBuilder()
                        .addString(IngestionJobConfiguration.BATCH_ID, batchId.toString())
                        .addString(IngestionJobConfiguration.SOURCE_CHECKSUM, checksum(batchId))
                        .toJobParameters());
        await().atMost(Duration.ofSeconds(60))
                .until(() -> !jobRepository.getJobExecution(started.getId()).isRunning());
        return jobRepository.getJobExecution(started.getId());
    }

    private int count(String table, UUID batchId, String condition) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE batch_id = :id AND " + condition)
                .param("id", batchId)
                .query(Integer.class)
                .single();
    }

    private static String checksum(UUID batchId) {
        return batchId.toString().replace("-", "").repeat(2);
    }
}
