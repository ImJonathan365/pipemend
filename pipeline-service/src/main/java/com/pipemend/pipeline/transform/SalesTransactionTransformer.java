package com.pipemend.pipeline.transform;

import com.pipemend.pipeline.schema.FieldRule.DateTimeRule;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.transform.SalesTransaction.LoadOrigin;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Turns a record that passed validation into its canonical row (AC-13.1). It trusts the validator for the format of
 * every value and fails loudly if that trust is broken, never by rounding or truncating.
 */
public final class SalesTransactionTransformer {

    private final DateTimeFormatter invoiceDate;

    public SalesTransactionTransformer(SchemaDefinition schema) {
        this.invoiceDate = ((DateTimeRule) schema.field("invoice_date").rule()).formatter();
    }

    /** @param fields canonical field name to raw value, already valid */
    public SalesTransaction transform(
            UUID batchId, int sourceRowNumber, Map<String, String> fields, LoadOrigin loadOrigin) {
        String invoiceNo = fields.get("invoice_no");
        int quantity = Integer.parseInt(fields.get("quantity"));
        // numeric(12,2) would round 2.555 to 2.56 without complaint; UNNECESSARY throws instead.
        BigDecimal unitPrice = new BigDecimal(fields.get("unit_price")).setScale(2, RoundingMode.UNNECESSARY);
        return new SalesTransaction(
                batchId,
                sourceRowNumber,
                invoiceNo,
                invoiceNo.startsWith("C"),
                fields.get("stock_code"),
                optional(fields.get("description")),
                quantity,
                LocalDateTime.parse(fields.get("invoice_date"), invoiceDate),
                unitPrice,
                unitPrice.multiply(BigDecimal.valueOf(quantity)),
                optional(fields.get("customer_id")),
                fields.get("country"),
                loadOrigin);
    }

    // Only the empty string is null in an optional field; any other null token was validated as text.
    private static @Nullable String optional(@Nullable String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
