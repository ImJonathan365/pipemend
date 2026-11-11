package com.pipemend.pipeline.persistence;

import com.pipemend.pipeline.batch.ProcessedRecord;
import com.pipemend.pipeline.triage.TriageRequestBuilder;
import javax.sql.DataSource;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import tools.jackson.databind.json.JsonMapper;

/** Batch insert into quarantine_record (AC-11.1). policy_decision stays null until the CorrectionPolicy exists. */
public final class QuarantineWriter extends JdbcBatchItemWriter<ProcessedRecord.Quarantined> {

    public QuarantineWriter(DataSource dataSource, JsonMapper json) {
        setDataSource(dataSource);
        // Same safety net as SalesTransactionWriter: a conflict is left to the NFR-06 invariant to report.
        setSql("""
                INSERT INTO quarantine_record (batch_id, source_row_number, raw_payload, violations, summary,
                    quarantine_reason, ai_analysis, max_severity, ai_request_id)
                VALUES (:batchId, :sourceRowNumber, CAST(:rawPayload AS jsonb), CAST(:violations AS jsonb), :summary,
                    :reason, CAST(:aiAnalysis AS jsonb), :maxSeverity, :aiRequestId)
                ON CONFLICT (batch_id, source_row_number) DO NOTHING
                """);
        setAssertUpdates(false);
        setItemSqlParameterSourceProvider(record -> new MapSqlParameterSource()
                .addValue("batchId", record.batchId())
                .addValue("sourceRowNumber", record.sourceRowNumber())
                .addValue("rawPayload", json.writeValueAsString(record.rawPayload()))
                .addValue("violations", json.writeValueAsString(TriageRequestBuilder.withIds(record.violations())))
                .addValue("summary", record.summary())
                .addValue("reason", record.reason().name())
                .addValue(
                        "aiAnalysis",
                        record.analysis() == null ? null : record.analysis().body())
                .addValue(
                        "maxSeverity",
                        record.analysis() == null
                                ? null
                                : record.analysis().maxSeverity().name())
                .addValue("aiRequestId", record.aiRequestId()));
        afterPropertiesSet();
    }
}
