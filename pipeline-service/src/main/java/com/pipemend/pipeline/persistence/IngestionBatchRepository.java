package com.pipemend.pipeline.persistence;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Status and counters of ingestion_batch (docs/05 section 3.1), the source of truth for the API. */
@Repository
public class IngestionBatchRepository {

    private final JdbcClient jdbc;
    private final Clock clock;

    public IngestionBatchRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Also on a restart, which starts from a FAILED batch. */
    public void markRunning(UUID batchId, long jobExecutionId) {
        updateOne(
                batchId,
                jdbc.sql("""
                        UPDATE ingestion_batch SET status = 'RUNNING', job_execution_id = :execution,
                            started_at = coalesce(started_at, :now), failure_reason = NULL, finished_at = NULL
                        WHERE id = :id
                        """)
                        .param("id", batchId)
                        .param("execution", jobExecutionId)
                        .param("now", now())
                        .update());
    }

    public void saveReadCount(UUID batchId, int readCount) {
        updateOne(
                batchId,
                jdbc.sql("UPDATE ingestion_batch SET read_count = :count WHERE id = :id")
                        .param("id", batchId)
                        .param("count", readCount)
                        .update());
    }

    public void saveSchemaMapping(UUID batchId, String mappingJson) {
        updateOne(
                batchId,
                jdbc.sql("UPDATE ingestion_batch SET schema_mapping = CAST(:mapping AS jsonb) WHERE id = :id")
                        .param("id", batchId)
                        .param("mapping", mappingJson)
                        .update());
    }

    /** @param driftReportJson only for SCHEMA_DRIFT_UNRESOLVED */
    public void reject(UUID batchId, String failureReason, @Nullable String driftReportJson) {
        updateOne(
                batchId,
                jdbc.sql("""
                        UPDATE ingestion_batch SET status = 'REJECTED', failure_reason = :reason,
                            schema_drift_report = CAST(:report AS jsonb), finished_at = :now
                        WHERE id = :id
                        """)
                        .param("id", batchId)
                        .param("reason", failureReason)
                        .param("report", driftReportJson)
                        .param("now", now())
                        .update());
    }

    public void fail(UUID batchId, String failureReason) {
        updateOne(
                batchId,
                jdbc.sql("""
                        UPDATE ingestion_batch SET status = 'FAILED', failure_reason = :reason, finished_at = :now
                        WHERE id = :id
                        """)
                        .param("id", batchId)
                        .param("reason", failureReason)
                        .param("now", now())
                        .update());
    }

    public void complete(UUID batchId, Counts counts) {
        updateOne(
                batchId,
                jdbc.sql("""
                        UPDATE ingestion_batch SET status = 'COMPLETED', direct_count = :direct,
                            auto_corrected_count = :autoCorrected, quarantined_count = :quarantined, finished_at = :now
                        WHERE id = :id
                        """)
                        .param("id", batchId)
                        .param("direct", counts.direct())
                        .param("autoCorrected", counts.autoCorrected())
                        .param("quarantined", counts.quarantined())
                        .param("now", now())
                        .update());
    }

    /** A new batch in RECEIVED. A second active batch with the same checksum fails with a DuplicateKeyException. */
    public OffsetDateTime insert(
            UUID batchId,
            String sourceFilename,
            String checksum,
            long sizeBytes,
            String schemaName,
            int schemaVersion) {
        OffsetDateTime receivedAt = now();
        jdbc.sql("""
                        INSERT INTO ingestion_batch (id, source_filename, source_type, source_checksum,
                            source_size_bytes, schema_name, schema_version, status, received_at)
                        VALUES (:id, :filename, 'CSV', :checksum, :size, :schemaName, :schemaVersion, 'RECEIVED', :now)
                        """)
                .param("id", batchId)
                .param("filename", sourceFilename)
                .param("checksum", checksum)
                .param("size", sizeBytes)
                .param("schemaName", schemaName)
                .param("schemaVersion", String.valueOf(schemaVersion))
                .param("now", receivedAt)
                .update();
        return receivedAt;
    }

    public String status(UUID batchId) {
        return jdbc.sql("SELECT status FROM ingestion_batch WHERE id = :id")
                .param("id", batchId)
                .query(String.class)
                .single();
    }

    /** Counts read from the tables, never from the step (NFR-06, docs/05 section 6). */
    public Counts counts(UUID batchId) {
        return jdbc.sql("""
                        WITH written AS (
                            SELECT source_row_number AS n FROM sales_transaction WHERE batch_id = :id
                            UNION ALL
                            SELECT source_row_number FROM quarantine_record WHERE batch_id = :id)
                        SELECT b.read_count,
                            (SELECT count(*) FROM sales_transaction
                                WHERE batch_id = :id AND load_origin = 'DIRECT') AS direct,
                            (SELECT count(*) FROM sales_transaction
                                WHERE batch_id = :id AND load_origin = 'AUTO_CORRECTED') AS auto_corrected,
                            (SELECT count(*) FROM quarantine_record WHERE batch_id = :id) AS quarantined,
                            (SELECT count(*) FROM sales_transaction s JOIN quarantine_record q
                                ON q.batch_id = s.batch_id AND q.source_row_number = s.source_row_number
                                WHERE s.batch_id = :id) AS in_both,
                            (SELECT count(DISTINCT n) FROM written) AS distinct_rows,
                            (SELECT coalesce(max(n), 0) FROM written) AS max_row
                        FROM ingestion_batch b WHERE b.id = :id
                        """)
                .param("id", batchId)
                .query((rs, row) -> new Counts(
                        rs.getInt("read_count"),
                        rs.getInt("direct"),
                        rs.getInt("auto_corrected"),
                        rs.getInt("quarantined"),
                        rs.getInt("in_both"),
                        rs.getInt("distinct_rows"),
                        rs.getInt("max_row")))
                .single();
    }

    // A status change that touches no row would leave the API showing a stale state without any error.
    private static void updateOne(UUID batchId, int updated) {
        if (updated != 1) {
            throw new IllegalStateException("expected to update batch " + batchId + ", updated " + updated + " rows");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public record Counts(
            int read, int direct, int autoCorrected, int quarantined, int inBoth, int distinctRows, int maxRow) {

        /** The three checks of NFR-06: the sum, no row in both tables, and no gaps in 1..read. */
        public boolean invariantHolds() {
            return read == direct + autoCorrected + quarantined
                    && inBoth == 0
                    && distinctRows == read
                    && maxRow == read;
        }
    }
}
