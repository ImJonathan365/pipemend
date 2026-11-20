package com.pipemend.pipeline.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pipemend.pipeline.batch.RawRecord;
import com.pipemend.pipeline.batch.RecordTestSupport;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.schema.SchemaTestSupport;
import com.pipemend.pipeline.triage.contract.TriageRequest;
import com.pipemend.pipeline.validation.ErrorCode;
import com.pipemend.pipeline.validation.Validator;
import com.pipemend.pipeline.validation.Violation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** NFR-15 and AC-07.4: what of a record travels to the AI. */
class RecordRedactionTest {

    private static final SchemaDefinition SCHEMA = SchemaTestSupport.realSchema();
    private static final AiPrivacyProperties DEFAULTS = new AiPrivacyProperties(List.of("customer_id"), true);

    private static Violation field(String field, String header, String value) {
        return new Violation(field, header, ErrorCode.INVALID_FORMAT, value, Map.of("type", "string"), "message", null);
    }

    private static Violation businessRule() {
        return new Violation(
                Validator.RECORD,
                null,
                ErrorCode.BUSINESS_RULE_VIOLATION,
                null,
                Map.of("rule", "BR-01"),
                "BR-01: seeded",
                null);
    }

    private static Map<String, String> sent(AiPrivacyProperties privacy, Map<String, String> row, Violation... found) {
        return new RecordRedaction(SCHEMA, privacy).apply(row, List.of(found));
    }

    private static TriageRequest request(AiPrivacyProperties privacy, RawRecord record) {
        return new TriageRequestBuilder(
                        UUID.randomUUID(), SCHEMA, "dirty.csv", "CSV", new RecordRedaction(SCHEMA, privacy))
                .build(UUID.randomUUID(), record, List.of(field("country", "Country", "Atlantis")));
    }

    @Test
    void nfr_15_theDefaultSendsTheCustomerAsRedactedAndEveryOtherColumnAsItIs() {
        Map<String, String> row = RecordTestSupport.validRow();

        Map<String, String> sent = sent(DEFAULTS, row, field("country", "Country", "United Kingdom"));

        Map<String, String> expected = new LinkedHashMap<>(row);
        expected.put("Customer ID", RecordRedaction.REDACTED);
        assertThat(sent).containsExactlyEntriesOf(expected);
    }

    @Test
    void nfr_15_aFieldUnderAnalysisTravelsAsItIs() {
        Map<String, String> row = RecordTestSupport.validRow();
        row.put("Customer ID", "N/A");

        Map<String, String> sent = sent(
                DEFAULTS,
                row,
                field("country", "Country", "United Kingdom"),
                field("customer_id", "Customer ID", "N/A"));

        assertThat(sent).containsEntry("Customer ID", "N/A");
    }

    @Test
    void d237_anEmptyValueIsRedactedToo() {
        Map<String, String> row = RecordTestSupport.validRow();
        row.put("Customer ID", "");

        assertThat(sent(DEFAULTS, row, field("country", "Country", "United Kingdom")))
                .containsEntry("Customer ID", RecordRedaction.REDACTED);
    }

    @Test
    void nfr_15_withoutTheFullRecordOnlyTheColumnsUnderAnalysisTravel() {
        AiPrivacyProperties privacy = new AiPrivacyProperties(List.of("customer_id"), false);

        Map<String, String> sent = sent(
                privacy,
                RecordTestSupport.validRow(),
                field("country", "Country", "United Kingdom"),
                field("unit_price", "Price", "2.55"),
                businessRule());

        assertThat(sent).containsExactly(Map.entry("Price", "2.55"), Map.entry("Country", "United Kingdom"));
    }

    @Test
    void d236_aBusinessRuleAloneSendsNoColumnWithoutTheFullRecord() {
        AiPrivacyProperties privacy = new AiPrivacyProperties(List.of("customer_id"), false);

        assertThat(sent(privacy, RecordTestSupport.validRow(), businessRule())).isEmpty();
    }

    @Test
    void ac_07_4_aMalformedLineTravelsUnredactedWithTheFullRecord() {
        RawRecord malformed = new RawRecord.Malformed(7, "a,17850,c", "columns", 8, 3);

        assertThat(request(DEFAULTS, malformed).record().raw()).isEqualTo(Map.of("_rawLine", "a,17850,c"));
    }

    @Test
    void ac_07_4_aMalformedLineDoesNotTravelWithoutTheFullRecord() {
        RawRecord malformed = new RawRecord.Malformed(7, "a,17850,c", "columns", 8, 3);

        assertThat(request(new AiPrivacyProperties(List.of("customer_id"), false), malformed)
                        .record()
                        .raw())
                .isEmpty();
    }

    @Test
    void d235_anEmptyListRedactsNothing() {
        Map<String, String> row = RecordTestSupport.validRow();

        assertThat(sent(new AiPrivacyProperties(List.of(), true), row, field("country", "Country", "United Kingdom")))
                .isEqualTo(row);
    }

    @Test
    void d232_aFieldTheSchemaDoesNotKnowIsRefused() {
        assertThatThrownBy(() -> new RecordRedaction(SCHEMA, new AiPrivacyProperties(List.of("Customer ID"), true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Customer ID");
    }

    @Test
    void d233_theRecordItselfIsNotChanged() {
        Map<String, String> row = RecordTestSupport.validRow();
        Map<String, String> before = new LinkedHashMap<>(row);

        sent(DEFAULTS, row, field("country", "Country", "United Kingdom"));

        assertThat(row).isEqualTo(before);
    }
}
