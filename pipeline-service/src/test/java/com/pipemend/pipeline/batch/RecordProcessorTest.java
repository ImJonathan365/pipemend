package com.pipemend.pipeline.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.pipemend.pipeline.batch.ProcessedRecord.QuarantineReason;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.schema.SchemaTestSupport;
import com.pipemend.pipeline.transform.SalesTransaction.LoadOrigin;
import com.pipemend.pipeline.triage.AiTriagePort;
import com.pipemend.pipeline.triage.DisabledAiTriagePort;
import com.pipemend.pipeline.validation.ErrorCode;
import com.pipemend.pipeline.validation.Validator;
import com.pipemend.pipeline.validation.Violation;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordProcessorTest {

    static final UUID BATCH = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static RecordProcessor processor(SchemaDefinition schema, AiTriagePort triage) {
        return RecordTestSupport.processor(BATCH, schema, triage);
    }

    private final RecordProcessor processor = processor(SchemaTestSupport.realSchema(), new DisabledAiTriagePort());

    @Test
    void ac_06_1_aValidRecordIsLoadedDirectWithoutTriage() {
        AiTriagePort failIfCalled = (record, violations) -> {
            throw new AssertionError("a valid record must not reach the AI");
        };

        ProcessedRecord result = processor(SchemaTestSupport.realSchema(), failIfCalled)
                .process(new RawRecord.Parsed(3, RecordTestSupport.validRow()));

        assertThat(result).isInstanceOfSatisfying(ProcessedRecord.Loaded.class, loaded -> {
            assertThat(loaded.transaction().sourceRowNumber()).isEqualTo(3);
            assertThat(loaded.transaction().batchId()).isEqualTo(BATCH);
            assertThat(loaded.transaction().loadOrigin()).isEqualTo(LoadOrigin.DIRECT);
        });
    }

    @Test
    void ac_12_1_anInvalidRecordIsQuarantinedAsAiUnavailableWithAllItsViolations() {
        Map<String, String> row = RecordTestSupport.validRow();
        row.put("InvoiceDate", "25/12/2010 08:26");
        row.put("Price", "2,55");

        ProcessedRecord result = processor.process(new RawRecord.Parsed(4, row));

        assertThat(result).isInstanceOfSatisfying(ProcessedRecord.Quarantined.class, q -> {
            assertThat(q.reason()).isEqualTo(QuarantineReason.AI_UNAVAILABLE);
            assertThat(q.rawPayload()).isEqualTo(row);
            assertThat(q.violations())
                    .extracting(Violation::field, Violation::errorCode)
                    .containsExactly(
                            tuple("invoice_date", ErrorCode.INVALID_FORMAT),
                            tuple("unit_price", ErrorCode.INVALID_FORMAT));
        });
    }

    @Test
    void ac_11_2_theSummaryIsATechnicalDescriptionOfTheViolations() {
        Map<String, String> row = RecordTestSupport.validRow();
        row.put("Quantity", "999999");

        ProcessedRecord.Quarantined q = (ProcessedRecord.Quarantined) processor.process(new RawRecord.Parsed(1, row));

        assertThat(q.summary()).contains("1 violation(s)").contains("quantity OUT_OF_RANGE");
    }

    @Test
    void ac_01_5_aMalformedRowIsQuarantinedWithItsRawLine() {
        ProcessedRecord result =
                processor.process(new RawRecord.Malformed(9, "a,b,c,d,e,f,g,h,i", "Expected 8 columns, found 9"));

        assertThat(result).isInstanceOfSatisfying(ProcessedRecord.Quarantined.class, q -> {
            assertThat(q.reason()).isEqualTo(QuarantineReason.AI_UNAVAILABLE);
            assertThat(q.rawPayload()).isEqualTo(Map.of("_rawLine", "a,b,c,d,e,f,g,h,i"));
            assertThat(q.violations())
                    .extracting(Violation::field, Violation::errorCode, Violation::technicalMessage)
                    .containsExactly(tuple(Validator.RECORD, ErrorCode.MALFORMED_ROW, "Expected 8 columns, found 9"));
        });
    }

    @Test
    void aPriceThatWouldNeedRoundingEndsAsProcessingError() {
        Map<String, String> row = RecordTestSupport.validRow();
        row.put("Price", "2.555");

        ProcessedRecord result = processor(RecordTestSupport.schemaWithPriceScaleThree(), new DisabledAiTriagePort())
                .process(new RawRecord.Parsed(5, row));

        assertThat(result).isInstanceOfSatisfying(ProcessedRecord.Quarantined.class, q -> {
            assertThat(q.reason()).isEqualTo(QuarantineReason.PROCESSING_ERROR);
            assertThat(q.summary()).contains("ArithmeticException");
        });
    }

    @Test
    void anExceptionAfterValidationKeepsTheViolationsItFound() {
        Map<String, String> row = RecordTestSupport.validRow();
        row.put("Country", "Atlantis");
        AiTriagePort broken = (record, violations) -> {
            throw new IllegalStateException("boom");
        };

        ProcessedRecord result =
                processor(SchemaTestSupport.realSchema(), broken).process(new RawRecord.Parsed(6, row));

        assertThat(result).isInstanceOfSatisfying(ProcessedRecord.Quarantined.class, q -> {
            assertThat(q.reason()).isEqualTo(QuarantineReason.PROCESSING_ERROR);
            assertThat(q.violations()).extracting(Violation::errorCode).containsExactly(ErrorCode.INVALID_ENUM_VALUE);
            assertThat(q.summary()).contains("boom").contains("country INVALID_ENUM_VALUE");
        });
    }
}
