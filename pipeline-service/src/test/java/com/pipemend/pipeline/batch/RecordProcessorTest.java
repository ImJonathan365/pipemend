package com.pipemend.pipeline.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.pipemend.pipeline.batch.ProcessedRecord.QuarantineReason;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.schema.SchemaTestSupport;
import com.pipemend.pipeline.transform.SalesTransaction.LoadOrigin;
import com.pipemend.pipeline.triage.AiTriagePort;
import com.pipemend.pipeline.triage.TriageOutcome;
import com.pipemend.pipeline.triage.TriageResponseValidator;
import com.pipemend.pipeline.triage.contract.Classification;
import com.pipemend.pipeline.triage.contract.Severity;
import com.pipemend.pipeline.triage.contract.TriageJson;
import com.pipemend.pipeline.triage.contract.TriageRequest;
import com.pipemend.pipeline.triage.contract.TriageResponse;
import com.pipemend.pipeline.validation.ErrorCode;
import com.pipemend.pipeline.validation.Validator;
import com.pipemend.pipeline.validation.Violation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;

class RecordProcessorTest {

    static final UUID BATCH = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final AiTriagePort AI_DOWN = request -> new TriageOutcome.Unavailable("ai-service is unreachable");

    private static RecordProcessor processor(SchemaDefinition schema, AiTriagePort triage) {
        return RecordTestSupport.processor(BATCH, schema, triage);
    }

    private final RecordProcessor processor = processor(SchemaTestSupport.realSchema(), AI_DOWN);

    @Test
    void ac_06_1_aValidRecordIsLoadedDirectWithoutTriage() {
        AiTriagePort failIfCalled = request -> {
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
                processor.process(new RawRecord.Malformed(9, "a,b,c,d,e,f,g,h,i", "Expected 8 columns, found 9", 8, 9));

        assertThat(result).isInstanceOfSatisfying(ProcessedRecord.Quarantined.class, q -> {
            assertThat(q.reason()).isEqualTo(QuarantineReason.AI_UNAVAILABLE);
            assertThat(q.rawPayload()).isEqualTo(Map.of("_rawLine", "a,b,c,d,e,f,g,h,i"));
            assertThat(q.violations())
                    .extracting(Violation::field, Violation::errorCode, Violation::technicalMessage)
                    .containsExactly(tuple(Validator.RECORD, ErrorCode.MALFORMED_ROW, "Expected 8 columns, found 9"));
            assertThat(q.violations().getFirst().expected())
                    .containsOnly(entry("expectedColumns", 8), entry("receivedColumns", 9));
        });
    }

    @Test
    void ac_07_4_aStrayQuoteLeavesReceivedColumnsPresentAndNull() {
        ProcessedRecord.Quarantined q = (ProcessedRecord.Quarantined)
                processor.process(new RawRecord.Malformed(9, "1,5\" TILE", "A stray quote", 8, null));

        assertThat(q.violations().getFirst().expected())
                .containsOnlyKeys("expectedColumns", "receivedColumns")
                .containsEntry("expectedColumns", 8)
                .containsEntry("receivedColumns", null);
    }

    @Test
    void ac_07_1_thePortReceivesTheRequestOfTheRecordWithAnIdOfItsOwn() {
        Map<String, String> row = RecordTestSupport.validRow();
        row.put("Country", "Atlantis");
        List<TriageRequest> requests = new ArrayList<>();
        AiTriagePort capture = request -> {
            requests.add(request);
            return new TriageOutcome.Unavailable("captured");
        };
        RecordProcessor capturing = processor(SchemaTestSupport.realSchema(), capture);

        capturing.process(new RawRecord.Parsed(6, row));
        capturing.process(new RawRecord.Parsed(7, row));

        assertThat(requests).hasSize(2);
        TriageRequest first = requests.getFirst();
        assertThat(first.batchId()).isEqualTo(BATCH);
        assertThat(first.record().sourceRowNumber()).isEqualTo(6);
        assertThat(first.record().raw()).isEqualTo(row);
        assertThat(first.violations())
                .extracting(TriageRequest.Violation::field)
                .containsExactly("country");
        assertThat(first.requestId()).isNotEqualTo(requests.getLast().requestId());
    }

    @Test
    void aPriceThatWouldNeedRoundingEndsAsProcessingError() {
        Map<String, String> row = RecordTestSupport.validRow();
        row.put("Price", "2.555");

        ProcessedRecord result = processor(RecordTestSupport.schemaWithPriceScaleThree(), AI_DOWN)
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
        AiTriagePort broken = request -> {
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

    static Stream<Arguments> docs02Section5_aFailedCall() {
        return Stream.of(
                arguments(new TriageOutcome.Unavailable("down"), QuarantineReason.AI_UNAVAILABLE),
                arguments(new TriageOutcome.InvalidResponse("bad"), QuarantineReason.AI_INVALID_RESPONSE),
                arguments(new TriageOutcome.BudgetExhausted("spent"), QuarantineReason.AI_BUDGET_EXCEEDED));
    }

    @ParameterizedTest
    @MethodSource
    void docs02Section5_aFailedCall(TriageOutcome outcome, QuarantineReason reason) {
        List<TriageRequest> sent = new ArrayList<>();
        AiTriagePort port = request -> {
            sent.add(request);
            return outcome;
        };

        ProcessedRecord.Quarantined q = (ProcessedRecord.Quarantined)
                processor(SchemaTestSupport.realSchema(), port).process(new RawRecord.Parsed(6, unknownCountry()));

        assertThat(q.reason()).isEqualTo(reason);
        assertThat(q.analysis()).isNull();
        assertThat(q.aiRequestId()).isEqualTo(sent.getFirst().requestId());
        // AC-11.2: the technical summary of the pipeline, never a text of the AI.
        assertThat(q.summary()).contains("1 violation(s)").contains("country INVALID_ENUM_VALUE");
    }

    @ParameterizedTest
    @CsvSource({
        "triage-response.auto-fixable.json, AUTO_FIXABLE, LOW",
        "triage-response.needs-human-review.json, NEEDS_HUMAN_REVIEW, HIGH"
    })
    void docs02Section5_everyValidAnalysisIsReviewedByAHumanUntilThePolicyExists(
            String fixture, Classification decision, Severity maxSeverity) throws IOException {
        String body = Files.readString(Path.of("..", "contracts", "fixtures", fixture));
        TriageResponse response = TriageJson.parse(body);
        AiTriagePort port = request -> new TriageOutcome.Analysed(
                response, body, new TriageResponseValidator.Result.Valid(decision, maxSeverity));

        ProcessedRecord.Quarantined q = (ProcessedRecord.Quarantined)
                processor(SchemaTestSupport.realSchema(), port).process(new RawRecord.Parsed(6, unknownCountry()));

        assertThat(q.reason()).isEqualTo(QuarantineReason.AI_REVIEW_REQUIRED);
        assertThat(q.summary()).isEqualTo(response.summary());
        assertThat(q.analysis()).isEqualTo(new ProcessedRecord.AiAnalysis(body, maxSeverity));
        assertThat(q.aiRequestId()).isNotNull();
    }

    @Test
    void nfr_03_aFailureAfterTheCallEndsAsProcessingErrorWithItsRequestId() {
        List<TriageRequest> sent = new ArrayList<>();
        AiTriagePort logWriteFails = request -> {
            sent.add(request);
            throw new DataAccessResourceFailureException("ai_call_log is not writable");
        };

        ProcessedRecord.Quarantined q =
                (ProcessedRecord.Quarantined) processor(SchemaTestSupport.realSchema(), logWriteFails)
                        .process(new RawRecord.Parsed(6, unknownCountry()));

        assertThat(q.reason()).isEqualTo(QuarantineReason.PROCESSING_ERROR);
        assertThat(q.aiRequestId()).isEqualTo(sent.getFirst().requestId());
        assertThat(q.analysis()).isNull();
    }

    @Test
    void aFailureBeforeAnyCallLeavesNoRequestId() {
        Map<String, String> row = RecordTestSupport.validRow();
        row.put("Price", "2.555");

        ProcessedRecord.Quarantined q =
                (ProcessedRecord.Quarantined) processor(RecordTestSupport.schemaWithPriceScaleThree(), AI_DOWN)
                        .process(new RawRecord.Parsed(5, row));

        assertThat(q.reason()).isEqualTo(QuarantineReason.PROCESSING_ERROR);
        assertThat(q.aiRequestId()).isNull();
    }

    private static Map<String, String> unknownCountry() {
        Map<String, String> row = RecordTestSupport.validRow();
        row.put("Country", "Atlantis");
        return row;
    }
}
