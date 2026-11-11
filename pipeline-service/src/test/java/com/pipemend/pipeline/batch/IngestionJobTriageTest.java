package com.pipemend.pipeline.batch;

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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The job with the real client, the real validator and a real PostgreSQL, against WireMock on 127.0.0.1. WireMock
 * answers every request with a valid AUTO_FIXABLE analysis of its own violations: without a CorrectionPolicy, none of
 * them may reach sales_transaction (docs/07 l.94, fail closed).
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, IngestionJobTriageTest.WireMockClient.class})
class IngestionJobTriageTest {

    private static final Path SAMPLES = Path.of("..", "data", "samples");
    private static final String HEADER =
            "Invoice,StockCode,Description,Quantity,InvoiceDate,Price,Customer ID,Country\n";
    private static final String UNKNOWN_COUNTRY =
            "536365,85123A,WHITE HANGING HEART T-LIGHT HOLDER,6,2010-12-01 08:26:00,2.55,17850,Atlantis\n";

    private static final WireMockServer SERVER =
            new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().extensions(new AutoFixableEcho()));

    static {
        SERVER.start();
        SERVER.stubFor(post(urlEqualTo("/v1/triage"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")));
    }

    /** Where the answer carries a control character, if anywhere. */
    enum Mutation {
        NONE,
        NUL_IN_SUMMARY,
        NUL_IN_A_PARAMETER,
        NUL_IN_AN_UNKNOWN_KEY,
        SOH_IN_SUMMARY
    }

    private static volatile Mutation mutation = Mutation.NONE;

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

    @AfterEach
    void noMutation() {
        mutation = Mutation.NONE;
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop();
    }

    @Test
    void docs07_everyAutoFixableRecordIsQuarantinedForReviewUntilThePolicyExists() throws Exception {
        UUID batchId = receive(Files.readAllBytes(SAMPLES.resolve("dirty-1k.csv")));

        assertThat(launch(batchId).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(count("sales_transaction", batchId, "load_origin = 'DIRECT'"))
                .isEqualTo(950);
        assertThat(count("sales_transaction", batchId, "load_origin = 'AUTO_CORRECTED'"))
                .isZero();
        assertThat(count("quarantine_record", batchId, "true")).isEqualTo(50);
        assertThat(count(
                        "quarantine_record",
                        batchId,
                        "quarantine_reason = 'AI_REVIEW_REQUIRED' AND ai_analysis IS NOT NULL AND max_severity = 'LOW'"
                                + " AND policy_decision IS NULL"))
                .isEqualTo(50);
        assertThat(jdbc.sql("""
                                SELECT count(*) FROM quarantine_record q JOIN ai_call_log a
                                    ON a.request_id = q.ai_request_id AND a.batch_id = q.batch_id
                                    AND a.source_row_number = q.source_row_number
                                WHERE q.batch_id = :id
                                """).param("id", batchId).query(Integer.class).single())
                .isEqualTo(50);
        assertThat(count("ai_call_log", batchId, "outcome = 'SUCCESS'")).isEqualTo(50);
        assertThat(count("ai_call_log", batchId, "true")).isEqualTo(50);
        assertThat(aiCallsCount(batchId)).isEqualTo(50);
    }

    @ParameterizedTest
    @EnumSource(names = {"NUL_IN_SUMMARY", "NUL_IN_A_PARAMETER", "NUL_IN_AN_UNKNOWN_KEY"})
    void docs06A5_anAnswerWithUPlus0000IsAnInvalidResponse(Mutation nul) throws Exception {
        mutation = nul;
        UUID batchId = receive((HEADER + UNKNOWN_COUNTRY).getBytes(StandardCharsets.UTF_8));

        assertThat(launch(batchId).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(quarantined(batchId))
                .containsEntry("quarantine_reason", "AI_INVALID_RESPONSE")
                .containsEntry("ai_analysis", null)
                .containsEntry("max_severity", null);
        assertThat(count(
                        "ai_call_log", batchId, "outcome = 'INVALID_RESPONSE' AND strpos(error_detail, '\\u0000') > 0"))
                .isEqualTo(1);
    }

    @Test
    void docs06A5_anAnswerWithUPlus0001IsAccepted() throws Exception {
        mutation = Mutation.SOH_IN_SUMMARY;
        UUID batchId = receive((HEADER + UNKNOWN_COUNTRY).getBytes(StandardCharsets.UTF_8));

        assertThat(launch(batchId).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(quarantined(batchId))
                .containsEntry("quarantine_reason", "AI_REVIEW_REQUIRED")
                .containsEntry("max_severity", "LOW");
        assertThat((String) quarantined(batchId).get("summary")).contains("\u0001");
        assertThat(count("ai_call_log", batchId, "outcome = 'SUCCESS'")).isEqualTo(1);
    }

    /**
     * The real client, pointed at WireMock. A property would not do: the closed port of PostgresTestConfiguration comes
     * from a DynamicPropertyRegistrar, which wins over @DynamicPropertySource (measured).
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class WireMockClient {

        @Bean
        @Primary
        AiTriagePort wireMockTriage(AiClientProperties defaults, JsonMapper json, AiCallLogRepository callLog) {
            return new HttpAiTriageClient(
                    new AiClientProperties(
                            URI.create(SERVER.baseUrl()), defaults.connectTimeout(), defaults.readTimeout()),
                    json,
                    callLog);
        }
    }

    /** A valid AUTO_FIXABLE LOW analysis of exactly the violations received, in their order. */
    static final class AutoFixableEcho implements ResponseTransformerV2 {

        private static final JsonMapper JSON = JsonMapper.builder().build();

        @Override
        public Response transform(Response response, ServeEvent serveEvent) {
            JsonNode request = JSON.readTree(serveEvent.getRequest().getBodyAsString());
            ObjectNode answer = JSON.createObjectNode();
            answer.put("requestId", request.get("requestId").asString());
            answer.put("recordDecision", "AUTO_FIXABLE");
            answer.put("maxSeverity", "LOW");
            answer.put(
                    "summary",
                    mutation == Mutation.NUL_IN_SUMMARY
                            ? "a\u0000b"
                            : mutation == Mutation.SOH_IN_SUMMARY ? "a\u0001b" : "Echo");
            ArrayNode analyses = answer.putArray("analyses");
            for (JsonNode violation : request.get("violations")) {
                ObjectNode analysis = analyses.addObject();
                analysis.put("violationId", violation.get("violationId").asString());
                analysis.put("field", violation.get("field").asString());
                analysis.put("rootCause", "FORMAT_MISMATCH");
                analysis.put("explanation", "Echo");
                analysis.put("severity", "LOW");
                analysis.put("classification", "AUTO_FIXABLE");
                analysis.put("confidence", 0.99);
                ObjectNode correction = analysis.putObject("correction");
                correction.put("operation", "TRIM");
                correction.set("originalValue", violation.get("receivedValue"));
                correction.put("proposedValue", "echo");
                ObjectNode parameters = correction.putObject("parameters");
                if (mutation == Mutation.NUL_IN_A_PARAMETER) {
                    parameters.put("note", "a\u0000b");
                }
                analysis.putNull("suggestedHumanAction");
            }
            ObjectNode meta = answer.putObject("meta");
            meta.put("provider", "mock");
            meta.put("model", "echo");
            meta.put("promptVersion", "triage_v1");
            meta.put("latencyMs", 1);
            meta.put("cacheHit", false);
            meta.putNull("inputTokens");
            meta.putNull("outputTokens");
            meta.put("llmCalls", 0);
            if (mutation == Mutation.NUL_IN_AN_UNKNOWN_KEY) {
                answer.put("extra\u0000key", 1);
            }
            return Response.Builder.like(response).but().body(answer.toString()).build();
        }

        @Override
        public String getName() {
            return "auto-fixable-echo";
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

    private Map<String, Object> quarantined(UUID batchId) {
        return jdbc.sql("""
                        SELECT quarantine_reason, ai_analysis::text AS ai_analysis, max_severity, summary
                        FROM quarantine_record WHERE batch_id = :id
                        """).param("id", batchId).query().singleRow();
    }

    private int aiCallsCount(UUID batchId) {
        return jdbc.sql("SELECT ai_calls_count FROM ingestion_batch WHERE id = :id")
                .param("id", batchId)
                .query(Integer.class)
                .single();
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
