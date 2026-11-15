package com.pipemend.pipeline.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.pipemend.pipeline.PostgresTestConfiguration;
import com.pipemend.pipeline.batch.Inbox;
import com.pipemend.pipeline.batch.IngestionJobConfiguration;
import com.pipemend.pipeline.batch.RawRecord;
import com.pipemend.pipeline.batch.RawRecordReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * AC-20.3: the whole pipeline with the mock provider on dirty-1k, checked against its labels and nothing else. Every
 * mismatch is collected first, so a failure lists all of them; each one is fixed only by a rule of /docs, never by
 * adjusting this test, the mock, the labels or the sample to the output.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, AiServiceContainer.class})
class Ac203MockEvaluationTest {

    private static final Path SAMPLES = Path.of("..", "data", "samples");
    private static final int ROWS = 1000;

    /** One entry of dirty-1k.labels.csv (docs/10 section 3). */
    private record Label(String defectType, @Nullable String cleanValue, String outcome, String operation) {}

    private record Correction(String operation, @Nullable String correctedValue) {}

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

    @Test
    void ac_20_3_everyRowOfDirty1kEndsWithItsLabelledOutcomeAndEveryCorrectionMatchesItsCleanValue() throws Exception {
        Map<Integer, Map<String, Label>> labels = labels();
        UUID batchId = receive(Files.readAllBytes(SAMPLES.resolve("dirty-1k.csv")));

        assertThat(launch(batchId).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(jdbc.sql("""
                                SELECT status, read_count, direct_count + auto_corrected_count + quarantined_count
                                    AS accounted
                                FROM ingestion_batch WHERE id = :id
                                """).param("id", batchId).query().singleRow())
                .containsEntry("status", "COMPLETED")
                .containsEntry("read_count", ROWS)
                .containsEntry("accounted", ROWS);

        Map<Integer, String> loaded = new HashMap<>();
        jdbc.sql("SELECT source_row_number, load_origin FROM sales_transaction WHERE batch_id = :id")
                .param("id", batchId)
                .query((rs, n) -> loaded.put(rs.getInt(1), rs.getString(2)))
                .list();
        Map<Integer, String> quarantined = new HashMap<>();
        jdbc.sql("SELECT source_row_number, quarantine_reason FROM quarantine_record WHERE batch_id = :id")
                .param("id", batchId)
                .query((rs, n) -> quarantined.put(rs.getInt(1), rs.getString(2)))
                .list();
        Map<Integer, Map<String, Correction>> corrections = new HashMap<>();
        jdbc.sql("""
                        SELECT source_row_number, field_name, operation, corrected_value FROM correction_log
                        WHERE batch_id = :id
                        """)
                .param("id", batchId)
                .query((rs, n) -> corrections
                        .computeIfAbsent(rs.getInt(1), row -> new TreeMap<>())
                        .put(rs.getString(2), new Correction(rs.getString(3), rs.getString(4))))
                .list();

        List<String> mismatches = new ArrayList<>();
        for (int row = 1; row <= ROWS; row++) {
            Map<String, Label> expected = labels.getOrDefault(row, Map.of());
            Set<String> expectedOutcomes =
                    expected.values().stream().map(Label::outcome).collect(Collectors.toCollection(TreeSet::new));
            // docs/10 section 3: every entry of a row carries the same expected_outcome.
            if (expectedOutcomes.size() > 1) {
                mismatches.add("row %d: labels disagree on expected_outcome %s".formatted(row, expectedOutcomes));
            }
            String expectedOutcome =
                    expected.isEmpty() ? "DIRECT" : expectedOutcomes.iterator().next();
            String actual = outcome(loaded.get(row), quarantined.get(row));
            Map<String, Correction> applied = corrections.getOrDefault(row, Map.of());
            if (!actual.equals(expectedOutcome)) {
                mismatches.add("row %d: expected %s, got %s; quarantine reason %s; defects %s; corrections %s"
                        .formatted(row, expectedOutcome, actual, quarantined.get(row), defects(expected), applied));
            }
            if (!"AUTO_CORRECTED".equals(actual)) {
                if (!applied.isEmpty()) {
                    mismatches.add("row %d: %s with corrections %s".formatted(row, actual, applied));
                }
                continue;
            }
            for (String field : expected.keySet()) {
                if (!applied.containsKey(field)) {
                    Label label = expected.get(field);
                    mismatches.add("row %d field %s: labelled %s (%s, clean %s) but not corrected"
                            .formatted(row, field, label.defectType(), label.operation(), label.cleanValue()));
                }
            }
            for (Map.Entry<String, Correction> entry : applied.entrySet()) {
                Label label = expected.get(entry.getKey());
                Correction correction = entry.getValue();
                if (label == null) {
                    mismatches.add("row %d field %s: corrected by %s to %s but not labelled"
                            .formatted(row, entry.getKey(), correction.operation(), correction.correctedValue()));
                } else if (!Objects.equals(correction.correctedValue(), label.cleanValue())) {
                    mismatches.add("row %d field %s: %s expected %s, got %s by %s"
                            .formatted(
                                    row,
                                    entry.getKey(),
                                    label.defectType(),
                                    label.cleanValue(),
                                    correction.correctedValue(),
                                    correction.operation()));
                }
            }
        }
        assertThat(mismatches).as("AC-20.3 mismatches").isEmpty();
    }

    private static String outcome(@Nullable String loadOrigin, @Nullable String quarantineReason) {
        if (loadOrigin != null && quarantineReason != null) {
            return "BOTH";
        }
        if (loadOrigin != null) {
            return loadOrigin;
        }
        return quarantineReason != null ? "QUARANTINED" : "NOT WRITTEN";
    }

    private static List<String> defects(Map<String, Label> expected) {
        return expected.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue().defectType())
                .toList();
    }

    /** Rows by number, then labels by field. docs/10 line 60: NULLIFY_TOKEN expects null, written as "". */
    private static Map<Integer, Map<String, Label>> labels() throws Exception {
        RawRecordReader reader = new RawRecordReader(new FileSystemResource(SAMPLES.resolve("dirty-1k.labels.csv")));
        reader.open(new ExecutionContext());
        Map<Integer, Map<String, Label>> labels = new TreeMap<>();
        for (RawRecord record = reader.read(); record != null; record = reader.read()) {
            Map<String, String> columns = ((RawRecord.Parsed) record).valuesByHeader();
            String operation = columns.get("expected_operation");
            String clean = "NULLIFY_TOKEN".equals(operation)
                            && columns.get("clean_value").isEmpty()
                    ? null
                    : columns.get("clean_value");
            labels.computeIfAbsent(Integer.valueOf(columns.get("source_row_number")), row -> new TreeMap<>())
                    .put(
                            columns.get("field"),
                            new Label(columns.get("defect_type"), clean, columns.get("expected_outcome"), operation));
        }
        reader.close();
        return labels;
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
                .until(() -> !jobRepository.getJobExecution(started.getId()).isRunning());
        return jobRepository.getJobExecution(started.getId());
    }

    private static String checksum(UUID batchId) {
        return batchId.toString().replace("-", "").repeat(2);
    }
}
