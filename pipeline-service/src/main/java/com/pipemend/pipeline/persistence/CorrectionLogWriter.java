package com.pipemend.pipeline.persistence;

import com.pipemend.pipeline.policy.AppliedCorrection;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import tools.jackson.databind.json.JsonMapper;

/** Batch insert into correction_log, one row per corrected field (AC-10.3). */
public final class CorrectionLogWriter extends JdbcBatchItemWriter<CorrectionLogWriter.Row> {

    /** A correction and the row it belongs to, which the composite foreign key ties to sales_transaction. */
    public record Row(UUID batchId, int sourceRowNumber, AppliedCorrection correction) {}

    public CorrectionLogWriter(DataSource dataSource, JsonMapper json) {
        setDataSource(dataSource);
        // A safety net for restarts, like every write (ADR-0007, NFR-04, AC-04.4); D176 reverses D169.
        setSql("""
                INSERT INTO correction_log (batch_id, source_row_number, field_name, error_code, operation,
                    operation_params, original_value, corrected_value, confidence, severity, ai_explanation,
                    ai_provider, ai_model, prompt_version, ai_request_id)
                VALUES (:batchId, :sourceRowNumber, :fieldName, :errorCode, :operation,
                    CAST(:operationParams AS jsonb), :originalValue, :correctedValue, :confidence, :severity,
                    :aiExplanation, :aiProvider, :aiModel, :promptVersion, :aiRequestId)
                ON CONFLICT (batch_id, source_row_number, field_name) DO NOTHING
                """);
        setAssertUpdates(false);
        setItemSqlParameterSourceProvider(row -> {
            AppliedCorrection c = row.correction();
            return new MapSqlParameterSource()
                    .addValue("batchId", row.batchId())
                    .addValue("sourceRowNumber", row.sourceRowNumber())
                    .addValue("fieldName", c.fieldName())
                    .addValue("errorCode", c.errorCode().name())
                    .addValue("operation", c.operation().name())
                    .addValue("operationParams", json.writeValueAsString(c.operationParams()))
                    .addValue("originalValue", c.originalValue())
                    .addValue("correctedValue", c.correctedValue())
                    .addValue("confidence", c.confidence())
                    .addValue("severity", c.severity().name())
                    .addValue("aiExplanation", c.aiExplanation())
                    .addValue("aiProvider", c.aiProvider())
                    .addValue("aiModel", c.aiModel())
                    .addValue("promptVersion", c.promptVersion())
                    .addValue("aiRequestId", c.aiRequestId());
        });
        afterPropertiesSet();
    }
}
