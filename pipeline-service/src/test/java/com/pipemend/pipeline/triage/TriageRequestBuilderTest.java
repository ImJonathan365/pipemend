package com.pipemend.pipeline.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.pipemend.pipeline.batch.RawRecord;
import com.pipemend.pipeline.batch.RecordTestSupport;
import com.pipemend.pipeline.schema.SchemaTestSupport;
import com.pipemend.pipeline.triage.contract.TriageRequest;
import com.pipemend.pipeline.validation.ErrorCode;
import com.pipemend.pipeline.validation.Violation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TriageRequestBuilderTest {

    private static final UUID REQUEST = UUID.fromString("0b6c3f5e-7f0f-4a55-9d0e-2a1f0f1b6a10");
    private static final UUID BATCH = UUID.fromString("8a2e6d1c-43b2-4f55-8f6b-1f2b3c4d5e6f");
    private static final String EMOJI = "😀";

    private final TriageRequestBuilder builder =
            new TriageRequestBuilder(BATCH, SchemaTestSupport.realSchema(), "dirty-1k.csv", "CSV");

    private static Violation violation(String field, String value) {
        return new Violation(
                field, "Header", ErrorCode.INVALID_FORMAT, value, Map.of("type", "string"), "message", "SomeException");
    }

    @Test
    void ac_07_1_carriesTheBatchTheSourceTheRowAndEveryViolation() {
        Map<String, String> row = RecordTestSupport.validRow();

        TriageRequest request = builder.build(REQUEST, new RawRecord.Parsed(152, row), List.of(violation("a", "1")));

        assertThat(request.requestId()).isEqualTo(REQUEST);
        assertThat(request.batchId()).isEqualTo(BATCH);
        assertThat(request.schema()).isEqualTo(new TriageRequest.SchemaRef("sales_transaction", "1"));
        assertThat(request.source()).isEqualTo(new TriageRequest.Source("dirty-1k.csv", "CSV"));
        assertThat(request.record()).isEqualTo(new TriageRequest.Row(152, row));
        assertThat(request.options()).isNull();
        assertThat(request.violations())
                .containsExactly(new TriageRequest.Violation(
                        "v1",
                        "a",
                        "Header",
                        ErrorCode.INVALID_FORMAT,
                        "1",
                        Map.of("type", "string"),
                        "message",
                        "SomeException"));
    }

    @Test
    void violationIdsAreUniqueAndFollowTheValidationOrder() {
        TriageRequest request = builder.build(
                REQUEST,
                new RawRecord.Parsed(1, Map.of()),
                List.of(violation("c", "1"), violation("a", "2"), violation("b", "3")));

        assertThat(request.violations())
                .extracting(TriageRequest.Violation::violationId, TriageRequest.Violation::field)
                .containsExactly(tuple("v1", "c"), tuple("v2", "a"), tuple("v3", "b"));
    }

    @Test
    void ac_07_4_aMalformedRowTravelsAsItsRawLine() {
        TriageRequest request = builder.build(
                REQUEST, new RawRecord.Malformed(311, "a,b,c", "Expected 8 columns, found 3", 8, 3), List.of());

        assertThat(request.record().raw()).isEqualTo(Map.of("_rawLine", "a,b,c"));
    }

    @Test
    void ac_07_4_theRawLineIsCutAtTheLimitOnlyWhenLonger() {
        assertThat(rawLine("x".repeat(2000))).hasSize(2000);
        assertThat(rawLine("x".repeat(2001))).isEqualTo("x".repeat(2000));
    }

    @Test
    void ac_07_4_theRawLineNeverSplitsAnEmojiAtTheLimit() {
        String cut = rawLine("x".repeat(1999) + EMOJI + "tail");

        assertThat(cut).isEqualTo("x".repeat(1999) + EMOJI);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(2000);
    }

    @Test
    void ac_07_4_theLimitCountsCodePointsNotChars() {
        String line = EMOJI.repeat(1500);

        assertThat(rawLine(line)).isEqualTo(line);
    }

    private String rawLine(String line) {
        RawRecord record = new RawRecord.Malformed(1, line, "A stray quote", 8, null);
        return builder.build(REQUEST, record, List.of()).record().raw().get(TriageRequestBuilder.RAW_LINE);
    }
}
