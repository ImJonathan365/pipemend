package com.pipemend.pipeline.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.pipemend.pipeline.PostgresTestConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The ingestion job end to end against a real PostgreSQL, launched asynchronously with the JobOperator. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class IngestionJobTest {

    private static final Path SAMPLES = Path.of("..", "data", "samples");

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
    JsonMapper json;

    @Autowired
    Inbox inbox;

    @Test
    void ac_13_1_cleanSampleLoadsEveryRowDirectAndPersistsTheBatchMetadata() throws Exception {
        UUID batchId = receive(sample("clean-1k.csv"));

        JobExecution execution = launch(batchId);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(batch(batchId))
                .containsEntry("status", "COMPLETED")
                .containsEntry("failure_reason", null)
                .containsEntry("read_count", 1000)
                .containsEntry("direct_count", 1000)
                .containsEntry("quarantined_count", 0)
                .containsEntry("job_execution_id", execution.getId());
        assertThat(count("sales_transaction", batchId, "load_origin = 'DIRECT'"))
                .isEqualTo(1000);
        assertThat(json.readValue((String) batch(batchId).get("schema_mapping"), Map.class))
                .containsEntry("strategy", "IDENTITY");
        // docs/04 section 3.1: the job repository is the JDBC one, not the in-memory one.
        assertThat(jdbc.sql("SELECT status FROM batch_job_execution WHERE job_execution_id = :id")
                        .param("id", execution.getId())
                        .query(String.class)
                        .single())
                .isEqualTo("COMPLETED");
    }

    @Test
    void ac_12_1_dirtySampleLoadsTheValidRowsAndQuarantinesTheRestAsAiUnavailable() throws Exception {
        UUID batchId = receive(sample("dirty-1k.csv"));

        assertThat(launch(batchId).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(batch(batchId))
                .containsEntry("status", "COMPLETED")
                .containsEntry("read_count", 1000)
                .containsEntry("direct_count", 950)
                .containsEntry("quarantined_count", 50);
        assertThat(count("quarantine_record", batchId, "quarantine_reason = 'AI_UNAVAILABLE'"))
                .isEqualTo(50);
        assertThat(jdbc.sql("""
                                SELECT source_row_number FROM quarantine_record
                                WHERE batch_id = :id AND raw_payload ->> '_rawLine' IS NOT NULL ORDER BY 1
                                """).param("id", batchId).query(Integer.class).list())
                .containsExactly(175, 228);
    }

    @Test
    void ac_01_8_aRepeatedHeaderRejectsTheBatchWithoutProcessingAnyRow() throws Exception {
        UUID batchId = receive(bytes("Invoice,Invoice\n1,2\n"));

        assertRejected(batchId, launch(batchId), "INVALID_FILE_FORMAT");
    }

    @Test
    void ac_01_7_invalidUtf8AtTheEndRejectsTheBatchBeforeAnyRowIsProcessed() throws Exception {
        UUID batchId = receive(concat(firstLines(150), new byte[] {(byte) 0xC3, 0x28, '\n'}));

        assertRejected(batchId, launch(batchId), "INVALID_FILE_FORMAT");
    }

    @Test
    void anUnclosedQuoteInTheLastRowRejectsTheBatchBeforeAnyRowIsProcessed() throws Exception {
        UUID batchId = receive(concat(firstLines(150), bytes("C536379,\"never closed,1\n")));

        assertRejected(batchId, launch(batchId), "INVALID_FILE_FORMAT");
    }

    @Test
    void ac_05_1_headersThatAreNotIdenticalRejectTheBatchAsSchemaDrift() throws Exception {
        UUID batchId = receive(sample("drift-legacy-headers.csv"));

        assertRejected(batchId, launch(batchId), "SCHEMA_DRIFT_UNRESOLVED");
        Map<String, Object> report = json.readValue(
                (String) batch(batchId).get("schema_drift_report"), new TypeReference<Map<String, Object>>() {});
        assertThat(report).containsOnlyKeys("receivedHeaders", "expectedHeaders", "reason");
        assertThat(report.get("receivedHeaders").toString()).contains("InvoiceNo", "UnitPrice", "CustomerID");
    }

    @Test
    void nfr_06_aRowDroppedByTheUniqueConstraintFailsTheBatchWithInvariantViolation() throws Exception {
        UUID batchId = receive(firstLines(150));
        // Simulates a numbering bug: row 6 arrives as row 5, so ON CONFLICT DO NOTHING silently drops it.
        String trigger = createTrigger(
                batchId, "IF NEW.source_row_number = 6 THEN NEW.source_row_number := 5; END IF; RETURN NEW;");
        JobExecution execution;
        try {
            execution = launch(batchId);
        } finally {
            dropTrigger(trigger);
        }

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(batch(batchId))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "INVARIANT_VIOLATION");
        assertThat(count("sales_transaction", batchId, "true")).isEqualTo(149);
    }

    @Test
    void ac_04_4_aRestartAfterAFailedChunkResumesWithoutDuplicates() throws Exception {
        UUID batchId = receive(sample("clean-1k.csv"));
        JobExecution failed = launchFailingAtRow250(batchId);

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(batch(batchId))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "INFRASTRUCTURE_ERROR");
        assertThat(count("sales_transaction", batchId, "true")).isEqualTo(200);

        JobExecution restarted = awaitEnd(jobOperator.restart(failed));

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(batch(batchId))
                .containsEntry("status", "COMPLETED")
                .containsEntry("failure_reason", null)
                .containsEntry("direct_count", 1000)
                .containsEntry("job_execution_id", restarted.getId());
        assertThat(jdbc.sql("SELECT count(DISTINCT source_row_number) FROM sales_transaction WHERE batch_id = :id")
                        .param("id", batchId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1000);
        // The reader resumed after the 200 committed rows instead of reading the file again.
        assertThat(step(restarted, "processRecordsStep").getReadCount()).isEqualTo(800);
    }

    @Test
    void anUnreadableFileFoundWhileProcessingFailsTheBatchAndNeverRejectsIt() throws Exception {
        UUID batchId = receive(sample("clean-1k.csv"));
        JobExecution failed = launchFailingAtRow250(batchId);
        // The file changes on disk between the intake and the restart: the reader meets invalid UTF-8 mid-step.
        Path source = inbox.directory(batchId).resolve("source.csv");
        Files.write(source, concat(firstLines(300), new byte[] {(byte) 0xC3, 0x28, '\n'}));

        JobExecution restarted = awaitEnd(jobOperator.restart(failed));

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(restarted.getExitStatus().getExitCode()).isNotEqualTo("REJECTED");
        assertThat(batch(batchId))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "INFRASTRUCTURE_ERROR");
    }

    private JobExecution launchFailingAtRow250(UUID batchId) throws Exception {
        String trigger = createTrigger(
                batchId,
                "IF NEW.source_row_number = 250 THEN RAISE EXCEPTION 'test failure at row 250'; END IF; RETURN NEW;");
        try {
            return launch(batchId);
        } finally {
            dropTrigger(trigger);
        }
    }

    private void assertRejected(UUID batchId, JobExecution execution, String reason) {
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("REJECTED");
        assertThat(execution.getStepExecutions())
                .extracting(StepExecution::getStepName)
                .doesNotContain("processRecordsStep");
        assertThat(batch(batchId)).containsEntry("status", "REJECTED").containsEntry("failure_reason", reason);
        assertThat(count("sales_transaction", batchId, "true")).isZero();
        assertThat(count("quarantine_record", batchId, "true")).isZero();
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
        await().atMost(Duration.ofMinutes(2))
                .until(() -> !jobRepository.getJobExecution(started.getId()).isRunning());
        return jobRepository.getJobExecution(started.getId());
    }

    private String createTrigger(UUID batchId, String body) {
        String name = "test_" + batchId.toString().replace("-", "");
        jdbcTemplate.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.batch_id <> '" + batchId + "' THEN RETURN NEW; END IF; " + body + " END $$");
        jdbcTemplate.execute("CREATE TRIGGER " + name
                + " BEFORE INSERT ON sales_transaction FOR EACH ROW EXECUTE FUNCTION " + name + "()");
        return name;
    }

    private void dropTrigger(String name) {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + name + " ON sales_transaction");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + name + "()");
    }

    private Map<String, Object> batch(UUID batchId) {
        return jdbc.sql("""
                        SELECT status, failure_reason, read_count, direct_count, quarantined_count, job_execution_id,
                            schema_mapping::text AS schema_mapping, schema_drift_report::text AS schema_drift_report
                        FROM ingestion_batch WHERE id = :id
                        """).param("id", batchId).query().singleRow();
    }

    private int count(String table, UUID batchId, String condition) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE batch_id = :id AND " + condition)
                .param("id", batchId)
                .query(Integer.class)
                .single();
    }

    private static StepExecution step(JobExecution execution, String name) {
        return execution.getStepExecutions().stream()
                .filter(step -> step.getStepName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /** Unique per batch: the active-checksum index would otherwise reject a sample used by two tests. */
    private static String checksum(UUID batchId) {
        return batchId.toString().replace("-", "").repeat(2);
    }

    private static byte[] sample(String name) throws IOException {
        return Files.readAllBytes(SAMPLES.resolve(name));
    }

    /** The header and the first {@code rows} data rows of clean-1k, all valid. */
    private static byte[] firstLines(int rows) throws IOException {
        List<String> lines = Arrays.asList(new String(sample("clean-1k.csv"), StandardCharsets.UTF_8).split("\n"));
        return bytes(String.join("\n", lines.subList(0, rows + 1)) + "\n");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] all = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
