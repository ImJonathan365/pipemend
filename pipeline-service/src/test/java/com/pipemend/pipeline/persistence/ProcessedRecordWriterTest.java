package com.pipemend.pipeline.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pipemend.pipeline.PostgresTestConfiguration;
import com.pipemend.pipeline.batch.ProcessedRecord;
import com.pipemend.pipeline.batch.ProcessedRecord.QuarantineReason;
import com.pipemend.pipeline.batch.RawRecord;
import com.pipemend.pipeline.batch.RecordTestSupport;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.triage.DisabledAiTriagePort;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The processor and the writers against a real PostgreSQL, with the real validator. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ProcessedRecordWriterTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    SchemaDefinition schema;

    @Autowired
    PlatformTransactionManager transactions;

    private ProcessedRecordWriter writer;
    private UUID batchId;

    @BeforeEach
    void setUp() {
        writer = new ProcessedRecordWriter(
                new SalesTransactionWriter(dataSource), new QuarantineWriter(dataSource, json));
        batchId = insertBatch();
    }

    @Test
    void ac_13_1_aValidRecordIsLoadedWithItsCanonicalTypes() throws Exception {
        write(process(schema, new RawRecord.Parsed(1, RecordTestSupport.validRow())));

        assertThat(jdbc.sql("SELECT * FROM sales_transaction WHERE batch_id = :id")
                        .param("id", batchId)
                        .query()
                        .singleRow())
                .containsEntry("source_row_number", 1)
                .containsEntry("invoice_no", "536365")
                .containsEntry("is_cancellation", false)
                .containsEntry("stock_code", "85123A")
                .containsEntry("quantity", 6)
                .containsEntry("invoice_date", Timestamp.valueOf(LocalDateTime.of(2010, 12, 1, 8, 26)))
                .containsEntry("unit_price", new BigDecimal("2.55"))
                .containsEntry("line_total", new BigDecimal("15.30"))
                .containsEntry("customer_id", "17850")
                .containsEntry("country", "United Kingdom")
                .containsEntry("load_origin", "DIRECT");
        assertThat(count("quarantine_record")).isZero();
    }

    @Test
    void ac_11_1_anInvalidRecordIsQuarantinedWithEveryColumnAndNothingIsLoaded() throws Exception {
        Map<String, String> raw = RecordTestSupport.validRow();
        raw.put("Price", "2,55");

        write(process(schema, new RawRecord.Parsed(2, raw)));

        Map<String, Object> row = jdbc.sql("""
                        SELECT source_row_number, raw_payload::text AS raw, violations::text AS violations, summary,
                            quarantine_reason, status, ai_analysis, max_severity, policy_decision, ai_request_id
                        FROM quarantine_record WHERE batch_id = :id
                        """).param("id", batchId).query().singleRow();
        assertThat(row)
                .containsEntry("source_row_number", 2)
                .containsEntry("quarantine_reason", "AI_UNAVAILABLE")
                .containsEntry("status", "PENDING_REVIEW")
                .containsEntry("ai_analysis", null)
                .containsEntry("max_severity", null)
                .containsEntry("policy_decision", null)
                .containsEntry("ai_request_id", null);
        assertThat(json.readValue((String) row.get("raw"), new TypeReference<Map<String, String>>() {}))
                .isEqualTo(raw);
        List<Map<String, Object>> violations =
                json.readValue((String) row.get("violations"), new TypeReference<List<Map<String, Object>>>() {});
        // docs/06 A.1: the violation keys, plus the id the pipeline assigns.
        assertThat(violations)
                .singleElement()
                .satisfies(violation -> assertThat(violation)
                        .containsOnlyKeys(
                                "violationId",
                                "field",
                                "sourceHeader",
                                "errorCode",
                                "receivedValue",
                                "expected",
                                "technicalMessage",
                                "exceptionType")
                        .containsEntry("violationId", "v1")
                        .containsEntry("field", "unit_price")
                        .containsEntry("sourceHeader", "Price")
                        .containsEntry("errorCode", "INVALID_FORMAT")
                        .containsEntry("receivedValue", "2,55"));
        assertThat(count("sales_transaction")).isZero();
    }

    @Test
    void ac_11_2_aMalformedRowKeepsItsRawLineAndATechnicalSummary() throws Exception {
        write(process(schema, new RawRecord.Malformed(3, "1,2,3", "Expected 8 columns, found 3")));

        Map<String, Object> row = jdbc.sql(
                        "SELECT raw_payload::text AS raw, summary FROM quarantine_record WHERE batch_id = :id")
                .param("id", batchId)
                .query()
                .singleRow();
        assertThat(json.readValue((String) row.get("raw"), new TypeReference<Map<String, String>>() {}))
                .isEqualTo(Map.of("_rawLine", "1,2,3"));
        assertThat((String) row.get("summary")).contains("_record MALFORMED_ROW (Expected 8 columns, found 3)");
    }

    @Test
    void eachOutcomeReachesOnlyItsOwnTable() throws Exception {
        Map<String, String> invalid = RecordTestSupport.validRow();
        invalid.put("Country", "Atlantis");

        write(
                process(schema, new RawRecord.Parsed(1, RecordTestSupport.validRow())),
                process(schema, new RawRecord.Parsed(2, invalid)),
                process(schema, new RawRecord.Malformed(3, "x", "Expected 8 columns, found 1")));

        assertThat(rowNumbers("sales_transaction")).containsExactly(1);
        assertThat(rowNumbers("quarantine_record")).containsExactly(2, 3);
    }

    @Test
    void aPriceThatWouldNeedRoundingNeverReachesTheCleanTable() throws Exception {
        Map<String, String> raw = RecordTestSupport.validRow();
        raw.put("Price", "2.555");

        write(process(RecordTestSupport.schemaWithPriceScaleThree(), new RawRecord.Parsed(1, raw)));

        assertThat(count("sales_transaction")).isZero();
        assertThat(jdbc.sql("SELECT quarantine_reason FROM quarantine_record WHERE batch_id = :id")
                        .param("id", batchId)
                        .query(String.class)
                        .single())
                .isEqualTo("PROCESSING_ERROR");
    }

    @Test
    void aDatabaseErrorInAWriterFailsTheWholeChunkInsteadOfQuarantining() {
        ProcessedRecord valid = process(schema, new RawRecord.Parsed(1, RecordTestSupport.validRow()));
        // Its batch does not exist, so the foreign key fails after the clean row of the same chunk was written.
        ProcessedRecord orphan = new ProcessedRecord.Quarantined(
                UUID.randomUUID(), 2, Map.of("_rawLine", "x"), List.of(), QuarantineReason.AI_UNAVAILABLE, "x");

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    try {
                        writer.write(new Chunk<>(valid, orphan));
                    } catch (Exception e) {
                        throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
                    }
                }))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("sales_transaction")).isZero();
        assertThat(count("quarantine_record")).isZero();
    }

    private ProcessedRecord process(SchemaDefinition schema, RawRecord record) {
        return RecordTestSupport.processor(batchId, schema, new DisabledAiTriagePort())
                .process(record);
    }

    private void write(ProcessedRecord... records) throws Exception {
        writer.write(new Chunk<>(records));
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE batch_id = :id")
                .param("id", batchId)
                .query(Integer.class)
                .single();
    }

    private List<Integer> rowNumbers(String table) {
        return jdbc.sql("SELECT source_row_number FROM " + table + " WHERE batch_id = :id ORDER BY 1")
                .param("id", batchId)
                .query(Integer.class)
                .list();
    }

    private UUID insertBatch() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO ingestion_batch (id, source_filename, source_type, source_checksum,
                            source_size_bytes, schema_name, schema_version, status)
                        VALUES (:id, 'sample.csv', 'CSV', :checksum, 1, 'sales_transaction', '1', 'RUNNING')
                        """)
                .param("id", id)
                .param("checksum", id.toString().replace("-", "").repeat(2))
                .update();
        return id;
    }
}
