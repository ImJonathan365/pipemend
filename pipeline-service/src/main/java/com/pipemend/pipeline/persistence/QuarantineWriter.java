package com.pipemend.pipeline.persistence;

import com.pipemend.pipeline.batch.ProcessedRecord;
import com.pipemend.pipeline.validation.Violation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import tools.jackson.databind.json.JsonMapper;

/** Batch insert into quarantine_record (AC-11.1). AI columns stay null until the AI client exists. */
public final class QuarantineWriter extends JdbcBatchItemWriter<ProcessedRecord.Quarantined> {

    public QuarantineWriter(DataSource dataSource, JsonMapper json) {
        setDataSource(dataSource);
        // Same safety net as SalesTransactionWriter: a conflict is left to the NFR-06 invariant to report.
        setSql("""
                INSERT INTO quarantine_record (batch_id, source_row_number, raw_payload, violations, summary,
                    quarantine_reason)
                VALUES (:batchId, :sourceRowNumber, CAST(:rawPayload AS jsonb), CAST(:violations AS jsonb), :summary,
                    :reason)
                ON CONFLICT (batch_id, source_row_number) DO NOTHING
                """);
        setAssertUpdates(false);
        setItemSqlParameterSourceProvider(record -> new MapSqlParameterSource()
                .addValue("batchId", record.batchId())
                .addValue("sourceRowNumber", record.sourceRowNumber())
                .addValue("rawPayload", json.writeValueAsString(record.rawPayload()))
                .addValue("violations", json.writeValueAsString(toJson(record.violations())))
                .addValue("summary", record.summary())
                .addValue("reason", record.reason().name()));
        afterPropertiesSet();
    }

    /** The violation shape of docs/06 A.1, with ids v1, v2... in validation order. */
    static List<Map<String, Object>> toJson(List<Violation> violations) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < violations.size(); i++) {
            Violation v = violations.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("violationId", "v" + (i + 1));
            entry.put("field", v.field());
            entry.put("sourceHeader", v.sourceHeader());
            entry.put("errorCode", v.errorCode().name());
            entry.put("receivedValue", v.receivedValue());
            entry.put("expected", v.expected());
            entry.put("technicalMessage", v.technicalMessage());
            entry.put("exceptionType", v.exceptionType());
            list.add(entry);
        }
        return list;
    }
}
