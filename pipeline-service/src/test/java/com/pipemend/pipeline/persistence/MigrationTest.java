package com.pipemend.pipeline.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import com.pipemend.pipeline.PostgresTestConfiguration;
import com.pipemend.pipeline.validation.ErrorCode;
import com.pipemend.pipeline.validation.Validator;
import com.pipemend.pipeline.validation.Violation;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** Flyway V1 and V2 against a real PostgreSQL 18, and the CHECK constraints of docs/05 section 4 against the validator. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class MigrationTest {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Validator validator;

    @Test
    void flywayAppliesTheBatchSchemaAndTheCoreTables() {
        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion().getVersion(), MigrationInfo::getState)
                .containsExactly(tuple("1", MigrationState.SUCCESS), tuple("2", MigrationState.SUCCESS));
    }

    @Test
    void theBatchMetadataTablesAndSequencesExist() {
        assertThat(tables())
                .contains(
                        "batch_job_instance",
                        "batch_job_execution",
                        "batch_job_execution_params",
                        "batch_step_execution",
                        "batch_step_execution_context",
                        "batch_job_execution_context");
        assertThat(jdbc.sql(
                                "SELECT sequence_name FROM information_schema.sequences WHERE sequence_schema = current_schema()")
                        .query(String.class)
                        .list())
                .contains("batch_step_execution_seq", "batch_job_execution_seq", "batch_job_instance_seq");
    }

    @Test
    void theFiveCoreTablesExist() {
        assertThat(tables())
                .contains("ingestion_batch", "sales_transaction", "quarantine_record", "correction_log", "ai_call_log");
    }

    @Test
    void ac_13_4_aValidRecordIsAcceptedByTheValidatorAndTheDatabase() {
        assertBothAccept(UnaryOperator.identity());
    }

    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("quantity 100000", with("quantity", "100000")),
                Arguments.of("quantity -100000", with("invoice_no", "C536365", "quantity", "-100000")),
                Arguments.of("unit_price 0", with("unit_price", "0.00")),
                Arguments.of("unit_price 100000", with("unit_price", "100000.00")),
                Arguments.of("first instant of 2009", with("invoice_date", "2009-01-01 00:00:00")),
                Arguments.of("A prefix with positive quantity", with("invoice_no", "A506401")),
                Arguments.of("no customer_id", with("customer_id", null)));
    }

    @ParameterizedTest(name = "AC-13.4 {0}")
    @MethodSource("boundaries")
    void ac_13_4_inclusiveBoundariesAreAcceptedOnBothSides(String name, UnaryOperator<Map<String, String>> change) {
        assertBothAccept(change);
    }

    // Column-level CHECKs get PostgreSQL's default name, sales_transaction_<column>_check; only ck_sales_* are named.
    static Stream<Arguments> checksMirroredByTheValidator() {
        return Stream.of(
                Arguments.of(
                        "sales_transaction_invoice_no_check",
                        with("invoice_no", "1234567"),
                        "invoice_no",
                        ErrorCode.PATTERN_MISMATCH),
                Arguments.of(
                        "sales_transaction_quantity_check", with("quantity", "0"), "quantity", ErrorCode.OUT_OF_RANGE),
                Arguments.of(
                        "sales_transaction_quantity_check",
                        with("quantity", "100001"),
                        "quantity",
                        ErrorCode.OUT_OF_RANGE),
                Arguments.of(
                        "sales_transaction_quantity_check",
                        with("invoice_no", "C536365", "quantity", "-100001"),
                        "quantity",
                        ErrorCode.OUT_OF_RANGE),
                Arguments.of(
                        "sales_transaction_invoice_date_check",
                        with("invoice_date", "2008-12-31 23:59:59"),
                        "invoice_date",
                        ErrorCode.OUT_OF_RANGE),
                Arguments.of(
                        "sales_transaction_unit_price_check",
                        with("unit_price", "-0.01"),
                        "unit_price",
                        ErrorCode.OUT_OF_RANGE),
                Arguments.of(
                        "sales_transaction_unit_price_check",
                        with("unit_price", "100000.01"),
                        "unit_price",
                        ErrorCode.OUT_OF_RANGE),
                Arguments.of(
                        "sales_transaction_customer_id_check",
                        with("customer_id", "1234"),
                        "customer_id",
                        ErrorCode.PATTERN_MISMATCH),
                Arguments.of(
                        "ck_sales_br_sign",
                        with("invoice_no", "C536379", "quantity", "5"),
                        Validator.RECORD,
                        ErrorCode.BUSINESS_RULE_VIOLATION),
                Arguments.of(
                        "ck_sales_br_sign",
                        with("quantity", "-5"),
                        Validator.RECORD,
                        ErrorCode.BUSINESS_RULE_VIOLATION));
    }

    @ParameterizedTest(name = "AC-13.4 {0}: {3} on {2}")
    @MethodSource("checksMirroredByTheValidator")
    void ac_13_4_everyCheckThatMirrorsTheSchemaFailsInTheDatabaseAndInTheValidator(
            String constraint, UnaryOperator<Map<String, String>> change, String field, ErrorCode code) {
        Map<String, String> raw = change.apply(validRaw());

        assertThat(validator.validate(raw))
                .extracting(Violation::field, Violation::errorCode)
                .containsExactly(tuple(field, code));
        assertRejected(() -> insertSale(SaleRow.from(raw, insertBatch("COMPLETED"))), CHECK_VIOLATION, constraint);
    }

    // Columns computed by the pipeline, not read from the file: the validator has no counterpart to check.
    static Stream<Arguments> checksOnPipelineColumns() {
        return Stream.of(
                Arguments.of("sales_transaction_source_row_number_check", (UnaryOperator<SaleRow>)
                        row -> row.withSourceRowNumber(0)),
                Arguments.of("sales_transaction_load_origin_check", (UnaryOperator<SaleRow>)
                        row -> row.withLoadOrigin("MANUAL")),
                Arguments.of("ck_sales_cancellation", (UnaryOperator<SaleRow>)
                        row -> row.withCancellation(!row.isCancellation())),
                Arguments.of("ck_sales_line_total", (UnaryOperator<SaleRow>)
                        row -> row.withLineTotal(row.lineTotal().add(new BigDecimal("0.01")))));
    }

    @ParameterizedTest(name = "AC-13.4 {0}")
    @MethodSource("checksOnPipelineColumns")
    void ac_13_4_checksOnPipelineColumnsFailInTheDatabase(String constraint, UnaryOperator<SaleRow> change) {
        SaleRow row = change.apply(SaleRow.from(validRaw(), insertBatch("COMPLETED")));

        assertRejected(() -> insertSale(row), CHECK_VIOLATION, constraint);
    }

    @Test
    void ac_04_3b_twoActiveBatchesCannotShareAChecksum() {
        insertBatch("COMPLETED", "a".repeat(64));

        assertRejected(() -> insertBatch("RECEIVED", "a".repeat(64)), UNIQUE_VIOLATION, "uq_batch_checksum_active");
    }

    @Test
    void ac_04_3b_aRejectedBatchDoesNotBlockItsChecksum() {
        insertBatch("REJECTED", "b".repeat(64));
        insertBatch("REJECTED", "b".repeat(64));
        insertBatch("RUNNING", "b".repeat(64));

        assertThat(jdbc.sql("SELECT count(*) FROM ingestion_batch WHERE source_checksum = :checksum")
                        .param("checksum", "b".repeat(64))
                        .query(Integer.class)
                        .single())
                .isEqualTo(3);
    }

    private void assertBothAccept(UnaryOperator<Map<String, String>> change) {
        Map<String, String> raw = change.apply(validRaw());

        assertThat(validator.validate(raw)).isEmpty();
        assertThat(insertSale(SaleRow.from(raw, insertBatch("COMPLETED")))).isEqualTo(1);
    }

    private static void assertRejected(Runnable statement, String sqlState, String constraint) {
        Throwable thrown = catchThrowable(statement::run);

        assertThat(thrown).isNotNull();
        Throwable root = NestedExceptionUtils.getRootCause(thrown);
        assertThat(root).isInstanceOf(SQLException.class);
        assertThat(((SQLException) root).getSQLState()).isEqualTo(sqlState);
        assertThat(root.getMessage()).contains('"' + constraint + '"');
    }

    private List<String> tables() {
        return jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()")
                .query(String.class)
                .list();
    }

    private UUID insertBatch(String status) {
        return insertBatch(status, UUID.randomUUID().toString().replace("-", "").repeat(2));
    }

    private UUID insertBatch(String status, String checksum) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO ingestion_batch (id, source_filename, source_type, source_checksum,
                            source_size_bytes, schema_name, schema_version, status)
                        VALUES (:id, 'sample.csv', 'CSV', :checksum, 1, 'sales_transaction', '1', :status)
                        """)
                .param("id", id)
                .param("checksum", checksum)
                .param("status", status)
                .update();
        return id;
    }

    private int insertSale(SaleRow row) {
        return jdbc.sql("""
                        INSERT INTO sales_transaction (batch_id, source_row_number, invoice_no, is_cancellation,
                            stock_code, description, quantity, invoice_date, unit_price, line_total, customer_id,
                            country, load_origin)
                        VALUES (:batchId, :sourceRowNumber, :invoiceNo, :cancellation, :stockCode, :description,
                            :quantity, :invoiceDate, :unitPrice, :lineTotal, :customerId, :country, :loadOrigin)
                        """)
                .param("batchId", row.batchId())
                .param("sourceRowNumber", row.sourceRowNumber())
                .param("invoiceNo", row.invoiceNo())
                .param("cancellation", row.isCancellation())
                .param("stockCode", row.stockCode())
                .param("description", row.description())
                .param("quantity", row.quantity())
                .param("invoiceDate", row.invoiceDate())
                .param("unitPrice", row.unitPrice())
                .param("lineTotal", row.lineTotal())
                .param("customerId", row.customerId())
                .param("country", row.country())
                .param("loadOrigin", row.loadOrigin())
                .update();
    }

    private static Map<String, String> validRaw() {
        Map<String, String> raw = new HashMap<>();
        raw.put("invoice_no", "536365");
        raw.put("stock_code", "85123A");
        raw.put("description", "WHITE HANGING HEART T-LIGHT HOLDER");
        raw.put("quantity", "6");
        raw.put("invoice_date", "2010-12-01 08:26:00");
        raw.put("unit_price", "2.55");
        raw.put("customer_id", "17850");
        raw.put("country", "United Kingdom");
        return raw;
    }

    private static UnaryOperator<Map<String, String>> with(String... fieldsAndValues) {
        return raw -> {
            for (int i = 0; i < fieldsAndValues.length; i += 2) {
                raw.put(fieldsAndValues[i], fieldsAndValues[i + 1]);
            }
            return raw;
        };
    }

    /** The same raw values typed the way the loader will type them, so both sides see one case. */
    record SaleRow(
            UUID batchId,
            int sourceRowNumber,
            String invoiceNo,
            boolean isCancellation,
            String stockCode,
            String description,
            int quantity,
            LocalDateTime invoiceDate,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            String customerId,
            String country,
            String loadOrigin) {

        static SaleRow from(Map<String, String> raw, UUID batchId) {
            int quantity = Integer.parseInt(raw.get("quantity"));
            BigDecimal unitPrice = new BigDecimal(raw.get("unit_price"));
            return new SaleRow(
                    batchId,
                    1,
                    raw.get("invoice_no"),
                    raw.get("invoice_no").startsWith("C"),
                    raw.get("stock_code"),
                    raw.get("description"),
                    quantity,
                    LocalDateTime.parse(raw.get("invoice_date"), DATE_TIME),
                    unitPrice,
                    unitPrice.multiply(BigDecimal.valueOf(quantity)),
                    raw.get("customer_id"),
                    raw.get("country"),
                    "DIRECT");
        }

        SaleRow withSourceRowNumber(int value) {
            return new SaleRow(
                    batchId,
                    value,
                    invoiceNo,
                    isCancellation,
                    stockCode,
                    description,
                    quantity,
                    invoiceDate,
                    unitPrice,
                    lineTotal,
                    customerId,
                    country,
                    loadOrigin);
        }

        SaleRow withLoadOrigin(String value) {
            return new SaleRow(
                    batchId,
                    sourceRowNumber,
                    invoiceNo,
                    isCancellation,
                    stockCode,
                    description,
                    quantity,
                    invoiceDate,
                    unitPrice,
                    lineTotal,
                    customerId,
                    country,
                    value);
        }

        SaleRow withCancellation(boolean value) {
            return new SaleRow(
                    batchId,
                    sourceRowNumber,
                    invoiceNo,
                    value,
                    stockCode,
                    description,
                    quantity,
                    invoiceDate,
                    unitPrice,
                    lineTotal,
                    customerId,
                    country,
                    loadOrigin);
        }

        SaleRow withLineTotal(BigDecimal value) {
            return new SaleRow(
                    batchId,
                    sourceRowNumber,
                    invoiceNo,
                    isCancellation,
                    stockCode,
                    description,
                    quantity,
                    invoiceDate,
                    unitPrice,
                    value,
                    customerId,
                    country,
                    loadOrigin);
        }
    }
}
