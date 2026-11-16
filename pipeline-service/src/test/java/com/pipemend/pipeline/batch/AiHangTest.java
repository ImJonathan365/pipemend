package com.pipemend.pipeline.batch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pipemend.pipeline.PostgresTestConfiguration;
import com.pipemend.pipeline.persistence.AiCallLogRepository;
import com.pipemend.pipeline.triage.AiClientProperties;
import com.pipemend.pipeline.triage.AiTriagePort;
import com.pipemend.pipeline.triage.HttpAiTriageClient;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * AC-12.5: an ai-service that accepts connections and never answers, on dirty-1k, with the timeouts reduced as docs/03
 * NFR-07 allows for tests. Read timeouts are never retried, and the circuit breaker opens after its minimum of calls.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, AiHangTest.HangingAiService.class})
class AiHangTest {

    private static final Path SAMPLES = Path.of("..", "data", "samples");
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(300);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(500);
    private static final int MINIMUM_CALLS = 10;
    private static final int HALF_OPEN_CALLS = 3;
    // Reading and loading the 1000 rows, which the bound of docs/03 leaves out (D196).
    private static final Duration PROCESSING = Duration.ofSeconds(10);

    private static final WireMockServer SERVER =
            new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());

    static {
        SERVER.start();
        SERVER.stubFor(post(urlEqualTo("/v1/triage"))
                .willReturn(aResponse().withStatus(200).withFixedDelay((int) READ_TIMEOUT.toMillis() * 3)));
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

    @Test
    void ac_12_5_aHangingAiServiceQuarantinesEveryInvalidRecordWithinTheBoundOfNfr07() throws Exception {
        UUID batchId = receive(Files.readAllBytes(SAMPLES.resolve("dirty-1k.csv")));
        long start = System.nanoTime();

        JobExecution execution = launch(batchId);

        Duration took = Duration.ofNanos(System.nanoTime() - start);
        // docs/03 NFR-07: minimumNumberOfCalls x read timeout, plus one half-open cycle of 3 calls as margin.
        Duration bound = READ_TIMEOUT
                .multipliedBy(MINIMUM_CALLS)
                .plus(READ_TIMEOUT.multipliedBy(HALF_OPEN_CALLS))
                .plus(PROCESSING);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(took).isLessThanOrEqualTo(bound);
        assertThat(jdbc.sql("""
                                SELECT status, read_count, direct_count, quarantined_count, ai_calls_count
                                FROM ingestion_batch WHERE id = :id
                                """).param("id", batchId).query().singleRow())
                .containsEntry("status", "COMPLETED")
                .containsEntry("read_count", 1000)
                .containsEntry("direct_count", 950)
                .containsEntry("quarantined_count", 50)
                // Decision B of the sprint: a call the open circuit refuses counts too (AC-08.6).
                .containsEntry("ai_calls_count", 50);
        assertThat(count("quarantine_record", batchId, "quarantine_reason = 'AI_UNAVAILABLE'"))
                .isEqualTo(50);
        assertThat(count("sales_transaction", batchId, "load_origin <> 'DIRECT'"))
                .isZero();
        // docs/03 l.99: a read timeout is never retried.
        assertThat(count("ai_call_log", batchId, "outcome = 'TIMEOUT' AND attempts = 1"))
                .isEqualTo(MINIMUM_CALLS);
        // AC-12.2: the rest fail fast, without a request.
        assertThat(count(
                        "ai_call_log",
                        batchId,
                        "outcome = 'CIRCUIT_OPEN' AND attempts = 1 AND http_status IS NULL AND latency_ms < 50"))
                .isEqualTo(50 - MINIMUM_CALLS);
        SERVER.verify(MINIMUM_CALLS, postRequestedFor(urlEqualTo("/v1/triage")));
    }

    /** The real client with the reduced timeouts and a circuit breaker of its own. */
    @TestConfiguration(proxyBeanMethods = false)
    static class HangingAiService {

        @Bean
        @Primary
        AiTriagePort hangingTriage(JsonMapper json, AiCallLogRepository callLog) {
            return new HttpAiTriageClient(
                    new AiClientProperties(URI.create(SERVER.baseUrl()), CONNECT_TIMEOUT, READ_TIMEOUT), json, callLog);
        }
    }

    private UUID receive(byte[] content) throws IOException {
        UUID batchId = UUID.randomUUID();
        Files.createDirectories(inbox.directory(batchId));
        Files.write(inbox.directory(batchId).resolve("source.csv"), content);
        jdbc.sql("""
                        INSERT INTO ingestion_batch (id, source_filename, source_type, source_checksum,
                            source_size_bytes, schema_name, schema_version, status)
                        VALUES (:id, 'dirty-1k.csv', 'CSV', :checksum, :size, 'sales_transaction', '1', 'RECEIVED')
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
        await().atMost(Duration.ofSeconds(120))
                .pollInterval(Duration.ofMillis(100))
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
