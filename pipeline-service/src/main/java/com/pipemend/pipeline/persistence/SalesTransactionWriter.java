package com.pipemend.pipeline.persistence;

import com.pipemend.pipeline.transform.SalesTransaction;
import javax.sql.DataSource;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** Batch insert into sales_transaction (AC-13.2). */
public final class SalesTransactionWriter extends JdbcBatchItemWriter<SalesTransaction> {

    public SalesTransactionWriter(DataSource dataSource) {
        setDataSource(dataSource);
        // ON CONFLICT is only a safety net for restarts (AC-04.4): a conflicting row is not inserted, and the
        // count invariant of NFR-06 is what reports it.
        setSql("""
                INSERT INTO sales_transaction (batch_id, source_row_number, invoice_no, is_cancellation, stock_code,
                    description, quantity, invoice_date, unit_price, line_total, customer_id, country, load_origin)
                VALUES (:batchId, :sourceRowNumber, :invoiceNo, :cancellation, :stockCode, :description, :quantity,
                    :invoiceDate, :unitPrice, :lineTotal, :customerId, :country, :loadOrigin)
                ON CONFLICT (batch_id, source_row_number) DO NOTHING
                """);
        setAssertUpdates(false);
        setItemSqlParameterSourceProvider(row -> new MapSqlParameterSource()
                .addValue("batchId", row.batchId())
                .addValue("sourceRowNumber", row.sourceRowNumber())
                .addValue("invoiceNo", row.invoiceNo())
                .addValue("cancellation", row.cancellation())
                .addValue("stockCode", row.stockCode())
                .addValue("description", row.description())
                .addValue("quantity", row.quantity())
                .addValue("invoiceDate", row.invoiceDate())
                .addValue("unitPrice", row.unitPrice())
                .addValue("lineTotal", row.lineTotal())
                .addValue("customerId", row.customerId())
                .addValue("country", row.country())
                .addValue("loadOrigin", row.loadOrigin().name()));
        afterPropertiesSet();
    }
}
