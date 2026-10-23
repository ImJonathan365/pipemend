package com.pipemend.pipeline.transform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pipemend.pipeline.schema.SchemaTestSupport;
import com.pipemend.pipeline.transform.SalesTransaction.LoadOrigin;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SalesTransactionTransformerTest {

    private static final UUID BATCH = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final SalesTransactionTransformer transformer =
            new SalesTransactionTransformer(SchemaTestSupport.realSchema());

    static Map<String, String> validFields() {
        Map<String, String> fields = new HashMap<>();
        fields.put("invoice_no", "536365");
        fields.put("stock_code", "85123A");
        fields.put("description", "WHITE HANGING HEART T-LIGHT HOLDER");
        fields.put("quantity", "6");
        fields.put("invoice_date", "2010-12-01 08:26:00");
        fields.put("unit_price", "2.55");
        fields.put("customer_id", "17850");
        fields.put("country", "United Kingdom");
        return fields;
    }

    @Test
    void ac_13_1_aValidRecordBecomesItsCanonicalRow() {
        SalesTransaction row = transformer.transform(BATCH, 7, validFields(), LoadOrigin.DIRECT);

        assertThat(row)
                .isEqualTo(new SalesTransaction(
                        BATCH,
                        7,
                        "536365",
                        false,
                        "85123A",
                        "WHITE HANGING HEART T-LIGHT HOLDER",
                        6,
                        LocalDateTime.of(2010, 12, 1, 8, 26),
                        new BigDecimal("2.55"),
                        new BigDecimal("15.30"),
                        "17850",
                        "United Kingdom",
                        LoadOrigin.DIRECT));
    }

    @Test
    void ac_13_1_aCancellationHasANegativeLineTotal() {
        Map<String, String> fields = validFields();
        fields.put("invoice_no", "C536379");
        fields.put("quantity", "-1");

        SalesTransaction row = transformer.transform(BATCH, 1, fields, LoadOrigin.DIRECT);

        assertThat(row.cancellation()).isTrue();
        assertThat(row.lineTotal()).isEqualTo(new BigDecimal("-2.55"));
    }

    @Test
    void ac_13_1_anEmptyOptionalFieldIsNullButOtherTextIsKept() {
        Map<String, String> fields = validFields();
        fields.put("customer_id", "");
        fields.put("description", "N/A");

        SalesTransaction row = transformer.transform(BATCH, 1, fields, LoadOrigin.DIRECT);

        assertThat(row.customerId()).isNull();
        assertThat(row.description()).isEqualTo("N/A");
    }

    @Test
    void ac_13_1_unitPriceAlwaysHasScaleTwo() {
        Map<String, String> fields = validFields();
        fields.put("unit_price", "3");

        assertThat(transformer.transform(BATCH, 1, fields, LoadOrigin.DIRECT).unitPrice())
                .hasToString("3.00");
    }

    @Test
    void aPriceWithThreeDecimalsIsNeverRounded() {
        Map<String, String> fields = validFields();
        fields.put("unit_price", "2.555");

        assertThatThrownBy(() -> transformer.transform(BATCH, 1, fields, LoadOrigin.DIRECT))
                .isInstanceOf(ArithmeticException.class);
    }
}
