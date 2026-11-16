package com.pipemend.pipeline.batch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.pipemend.pipeline.PostgresTestConfiguration;
import com.pipemend.pipeline.persistence.AiCallLogRepository;
import com.pipemend.pipeline.triage.AiClientProperties;
import com.pipemend.pipeline.triage.AiTriagePort;
import com.pipemend.pipeline.triage.HttpAiTriageClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC-08.6 end to end with a cap of 5, the real client and a real PostgreSQL, against WireMock on 127.0.0.1. dirty-1k has
 * 50 invalid rows, so the cap is reached well before the end of the batch.
 */
@SpringBootTest(properties = "pipemend.ai.max-calls-per-batch=" + AiBudgetTest.CAP)
@Import({PostgresTestConfiguration.class, AiBudgetTest.WireMockClient.class})
class AiBudgetTest {

    static final int CAP = 5;
    private static final int INVALID = 50;
    private static final Path SAMPLES = Path.of("..", "data", "samples");
    private static final String HEADER =
            "Invoice,StockCode,Description,Quantity,InvoiceDate,Price,Customer ID,Country\n";
    private static final String VALID =
            "536365,85123A,WHITE HANGING HEART T-LIGHT HOLDER,6,2010-12-01 08:26:00,2.55,17850,United Kingdom\n";
    private static final String UNKNOWN_COUNTRY =
            "536365,85123A,WHITE HANGING HEART T-LIGHT HOLDER,6,2010-12-01 08:26:00,2.55,17850,Atlantis\n";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(500);

    private static final WireMockServer SERVER = new WireMockServer(
            options().bindAddress("127.0.0.1").dynamicPort().extensions(new IngestionJobTriageTest.AutoFixableEcho()));

    static {
        SERVER.start();
    }

    /** A logical call that fails, each with the ai_call_log outcome it leaves. */
    enum Failure {
        TIMEOUT,
        HTTP_ERROR,
        CIRCUIT_OPEN
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
    JdbcTemplate jdbcTemplate;

    @Autowired
    Inbox inbox;

    @Autowired
    CircuitBreaker aiCircuitBreaker;

    @BeforeEach
    void answerEveryCallWithAValidAnalysis() {
        SERVER.resetAll();
        SERVER.stubFor(post(urlEqualTo("/v1/triage")).willReturn(analysis()));
        aiCircuitBreaker.reset();
    }

    @AfterEach
    void noCacheHits() {
        IngestionJobTriageTest.cacheHit = false;
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop();
    }

    @Test
    void ac_08_6_withTheCapAtFiveExactlyFiveLogicalCallsAreMade() throws Exception {
        UUID batchId = receive(Files.readAllBytes(SAMPLES.resolve("dirty-1k.csv")));

        JobExecution execution = launch(batchId);

        // NFR-03: a spent budget only diverts records to quarantine.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("ingestion_batch", batchId, "status = 'COMPLETED' AND direct_count = 950", "id"))
                .isEqualTo(1);
        SERVER.verify(CAP, postRequestedFor(urlEqualTo("/v1/triage")));
        assertThat(count("ai_call_log", batchId, "outcome = 'SUCCESS'")).isEqualTo(CAP);
        assertThat(count("ai_call_log", batchId, "true")).isEqualTo(CAP);
        assertThat(count("quarantine_record", batchId, "quarantine_reason <> 'AI_BUDGET_EXCEEDED'"))
                .isEqualTo(CAP);
        assertSpentBudgetLeavesNoRow(batchId, INVALID - CAP);
        assertThat(aiCallsCount(batchId)).isEqualTo(CAP);
        assertAiCallsCountIsTheRowsThatSpend(batchId);
    }

    @Test
    void ac_08_6_cacheHitsSpendNoBudget() throws Exception {
        IngestionJobTriageTest.cacheHit = true;
        UUID batchId = receive(Files.readAllBytes(SAMPLES.resolve("dirty-1k.csv")));

        assertThat(launch(batchId).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        SERVER.verify(INVALID, postRequestedFor(urlEqualTo("/v1/triage")));
        assertThat(count("ai_call_log", batchId, "outcome = 'CACHE_HIT'")).isEqualTo(INVALID);
        assertThat(count("quarantine_record", batchId, "quarantine_reason = 'AI_BUDGET_EXCEEDED'"))
                .isZero();
        assertThat(aiCallsCount(batchId)).isZero();
        assertAiCallsCountIsTheRowsThatSpend(batchId);
    }

    @ParameterizedTest
    @EnumSource
    void ac_08_6_failedCallsSpendBudget(Failure failure) throws Exception {
        int requests = CAP;
        switch (failure) {
            case TIMEOUT ->
                SERVER.stubFor(post(urlEqualTo("/v1/triage"))
                        .willReturn(analysis().withFixedDelay((int) READ_TIMEOUT.toMillis() * 3)));
            case HTTP_ERROR ->
                SERVER.stubFor(post(urlEqualTo("/v1/triage")).willReturn(problem(500, "INTERNAL_ERROR")));
            case CIRCUIT_OPEN -> {
                aiCircuitBreaker.transitionToForcedOpenState();
                requests = 0;
            }
        }
        UUID batchId = receive(Files.readAllBytes(SAMPLES.resolve("dirty-1k.csv")));

        assertThat(launch(batchId).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        SERVER.verify(requests, postRequestedFor(urlEqualTo("/v1/triage")));
        assertThat(count("ai_call_log", batchId, "outcome = '" + failure + "'")).isEqualTo(CAP);
        assertThat(count("ai_call_log", batchId, "true")).isEqualTo(CAP);
        // docs/02 l.171: the budget is checked first, so once it is spent the reason is no longer AI_UNAVAILABLE.
        assertThat(count("quarantine_record", batchId, "quarantine_reason = 'AI_UNAVAILABLE'"))
                .isEqualTo(CAP);
        assertSpentBudgetLeavesNoRow(batchId, INVALID - CAP);
        assertThat(aiCallsCount(batchId)).isEqualTo(CAP);
        assertAiCallsCountIsTheRowsThatSpend(batchId);
    }

    @Test
    void ac_08_6_a429LlmBudgetExhaustedEndsTheBudgetOfTheBatch() throws Exception {
        SERVER.resetAll();
        SERVER.stubFor(post(urlEqualTo("/v1/triage"))
                .inScenario("budget")
                .whenScenarioStateIs(STARTED)
                .willReturn(analysis())
                .willSetStateTo("second"));
        SERVER.stubFor(post(urlEqualTo("/v1/triage"))
                .inScenario("budget")
                .whenScenarioStateIs("second")
                .willReturn(analysis())
                .willSetStateTo("spent"));
        SERVER.stubFor(post(urlEqualTo("/v1/triage"))
                .inScenario("budget")
                .whenScenarioStateIs("spent")
                .willReturn(problem(429, "LLM_BUDGET_EXHAUSTED")));
        UUID batchId = receive(Files.readAllBytes(SAMPLES.resolve("dirty-1k.csv")));

        assertThat(launch(batchId).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        SERVER.verify(3, postRequestedFor(urlEqualTo("/v1/triage")));
        assertThat(count("ai_call_log", batchId, "outcome = 'SUCCESS'")).isEqualTo(2);
        assertThat(count("ai_call_log", batchId, "outcome = 'HTTP_ERROR' AND http_status = 429"))
                .isEqualTo(1);
        // The record that got the 429 keeps its row; the ones after it are never sent.
        assertThat(count("quarantine_record", batchId, "quarantine_reason = 'AI_BUDGET_EXCEEDED'"))
                .isEqualTo(INVALID - 2);
        assertThat(jdbc.sql("""
                                SELECT count(*) FROM quarantine_record q JOIN ai_call_log a
                                    ON a.request_id = q.ai_request_id AND a.http_status = 429
                                WHERE q.batch_id = :id AND q.quarantine_reason = 'AI_BUDGET_EXCEEDED'
                                """).param("id", batchId).query(Integer.class).single())
                .isEqualTo(1);
        assertSpentBudgetLeavesNoRow(batchId, INVALID - 3);
        assertThat(aiCallsCount(batchId)).isEqualTo(3);
        assertAiCallsCountIsTheRowsThatSpend(batchId);
    }

    @Test
    void ac_08_6_aRestartGetsNoNewBudget() throws Exception {
        // Three invalid rows in the first chunk and two in the second, which fails at row 150: five calls are made.
        StringBuilder csv = new StringBuilder(HEADER);
        for (int row = 1; row <= 250; row++) {
            csv.append(row == 10 || row == 20 || row == 30 || row == 120 || row == 130 ? UNKNOWN_COUNTRY : VALID);
        }
        UUID batchId = receive(csv.toString().getBytes(StandardCharsets.UTF_8));
        String trigger = createTrigger(batchId);
        JobExecution failed;
        try {
            failed = launch(batchId);
        } finally {
            dropTrigger(trigger);
        }
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(count("ai_call_log", batchId, "true")).isEqualTo(CAP);

        JobExecution restarted = awaitEnd(jobOperator.restart(failed));

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        SERVER.verify(CAP, postRequestedFor(urlEqualTo("/v1/triage")));
        assertThat(count("ai_call_log", batchId, "true")).isEqualTo(CAP);
        assertThat(jdbc.sql("""
                                SELECT source_row_number FROM quarantine_record
                                WHERE batch_id = :id AND quarantine_reason = 'AI_BUDGET_EXCEEDED'
                                    AND ai_request_id IS NULL
                                ORDER BY 1
                                """).param("id", batchId).query(Integer.class).list())
                .containsExactly(120, 130);
        // Their calls of the failed execution keep their rows, which is what spent the budget (AC-08.6).
        assertThat(count("ai_call_log", batchId, "source_row_number IN (120, 130)"))
                .isEqualTo(2);
        assertThat(count("quarantine_record", batchId, "true")).isEqualTo(5);
        assertThat(count("sales_transaction", batchId, "true")).isEqualTo(245);
        assertThat(aiCallsCount(batchId)).isEqualTo(CAP);
        assertAiCallsCountIsTheRowsThatSpend(batchId);
    }

    /** The real client with a short read timeout, behind the circuit breaker of the context. */
    @TestConfiguration(proxyBeanMethods = false)
    static class WireMockClient {

        @Bean
        @Primary
        AiTriagePort wireMockTriage(
                AiClientProperties defaults, JsonMapper json, AiCallLogRepository callLog, CircuitBreaker breaker) {
            return new HttpAiTriageClient(
                    new AiClientProperties(URI.create(SERVER.baseUrl()), defaults.connectTimeout(), READ_TIMEOUT),
                    json,
                    callLog,
                    breaker);
        }
    }

    private static ResponseDefinitionBuilder analysis() {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json");
    }

    private static ResponseDefinitionBuilder problem(int status, String code) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":" + status + ",\"code\":\"" + code + "\"}");
    }

    /** AC-08.5: a record the spent budget kept from the AI has no request id and no ai_call_log row. */
    private void assertSpentBudgetLeavesNoRow(UUID batchId, int expected) {
        assertThat(jdbc.sql("""
                                SELECT count(*) FROM quarantine_record q
                                WHERE q.batch_id = :id AND q.quarantine_reason = 'AI_BUDGET_EXCEEDED'
                                    AND q.ai_request_id IS NULL
                                    AND NOT EXISTS (SELECT 1 FROM ai_call_log a
                                        WHERE a.batch_id = q.batch_id AND a.source_row_number = q.source_row_number)
                                """).param("id", batchId).query(Integer.class).single())
                .isEqualTo(expected);
    }

    private void assertAiCallsCountIsTheRowsThatSpend(UUID batchId) {
        assertThat(aiCallsCount(batchId)).isEqualTo(count("ai_call_log", batchId, AiCallLogRepository.SPENDS_BUDGET));
    }

    private String createTrigger(UUID batchId) {
        String name = "test_" + batchId.toString().replace("-", "");
        jdbcTemplate.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.batch_id = '" + batchId + "' AND NEW.source_row_number = 150 THEN "
                + "RAISE EXCEPTION 'test failure at row 150'; END IF; RETURN NEW; END $$");
        jdbcTemplate.execute("CREATE TRIGGER " + name
                + " BEFORE INSERT ON sales_transaction FOR EACH ROW EXECUTE FUNCTION " + name + "()");
        return name;
    }

    private void dropTrigger(String name) {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + name + " ON sales_transaction");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + name + "()");
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
        return awaitEnd(jobOperator.start(
                ingestionJob,
                new JobParametersBuilder()
                        .addString(IngestionJobConfiguration.BATCH_ID, batchId.toString())
                        .addString(IngestionJobConfiguration.SOURCE_CHECKSUM, checksum(batchId))
                        .toJobParameters()));
    }

    private JobExecution awaitEnd(JobExecution started) {
        await().atMost(Duration.ofSeconds(60))
                .until(() -> !jobRepository.getJobExecution(started.getId()).isRunning());
        return jobRepository.getJobExecution(started.getId());
    }

    private int aiCallsCount(UUID batchId) {
        return jdbc.sql("SELECT ai_calls_count FROM ingestion_batch WHERE id = :id")
                .param("id", batchId)
                .query(Integer.class)
                .single();
    }

    private int count(String table, UUID batchId, String condition) {
        return count(table, batchId, condition, "batch_id");
    }

    private int count(String table, UUID batchId, String condition, String idColumn) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + idColumn + " = :id AND " + condition)
                .param("id", batchId)
                .query(Integer.class)
                .single();
    }

    private static String checksum(UUID batchId) {
        return batchId.toString().replace("-", "").repeat(2);
    }
}
