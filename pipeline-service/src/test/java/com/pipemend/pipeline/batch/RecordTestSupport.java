package com.pipemend.pipeline.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipemend.pipeline.schema.FieldRule.DecimalRule;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.schema.SchemaLoader;
import com.pipemend.pipeline.schema.SchemaTestSupport;
import com.pipemend.pipeline.transform.SalesTransactionTransformer;
import com.pipemend.pipeline.triage.AiTriagePort;
import com.pipemend.pipeline.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class RecordTestSupport {

    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC);

    private RecordTestSupport() {}

    /** A valid record keyed by the real file headers. */
    public static Map<String, String> validRow() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Invoice", "536365");
        row.put("StockCode", "85123A");
        row.put("Description", "WHITE HANGING HEART T-LIGHT HOLDER");
        row.put("Quantity", "6");
        row.put("InvoiceDate", "2010-12-01 08:26:00");
        row.put("Price", "2.55");
        row.put("Customer ID", "17850");
        row.put("Country", "United Kingdom");
        return row;
    }

    public static RecordProcessor processor(UUID batchId, SchemaDefinition schema, AiTriagePort triage) {
        return new RecordProcessor(
                batchId, schema, new Validator(schema, CLOCK), new SalesTransactionTransformer(schema), triage);
    }

    /** The real schema with unit_price at scale 3, so "2.555" passes validation and reaches the transformer. */
    public static SchemaDefinition schemaWithPriceScaleThree() {
        String yaml = SchemaTestSupport.resource("sales_transaction.v1.yaml");
        SchemaDefinition schema =
                SchemaLoader.load(yaml.replaceFirst("scale: 2", "scale: 3"), SchemaTestSupport::resource);
        assertThat(((DecimalRule) schema.field("unit_price").rule()).scale()).isEqualTo(3);
        return schema;
    }
}
