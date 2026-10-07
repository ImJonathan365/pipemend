package com.pipemend.pipeline.validation;

import static com.pipemend.pipeline.validation.ErrorCode.BUSINESS_RULE_VIOLATION;
import static com.pipemend.pipeline.validation.ErrorCode.INVALID_ENUM_VALUE;
import static com.pipemend.pipeline.validation.ErrorCode.INVALID_FORMAT;
import static com.pipemend.pipeline.validation.ErrorCode.INVALID_TYPE;
import static com.pipemend.pipeline.validation.ErrorCode.MAX_LENGTH_EXCEEDED;
import static com.pipemend.pipeline.validation.ErrorCode.MISSING_REQUIRED_FIELD;
import static com.pipemend.pipeline.validation.ErrorCode.OUT_OF_RANGE;
import static com.pipemend.pipeline.validation.ErrorCode.PATTERN_MISMATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.pipemend.pipeline.schema.SchemaTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ValidatorTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC);

    private final Validator validator = new Validator(SchemaTestSupport.realSchema(), CLOCK);

    static Map<String, String> validRecord() {
        Map<String, String> record = new HashMap<>();
        record.put("invoice_no", "536365");
        record.put("stock_code", "85123A");
        record.put("description", "WHITE HANGING HEART T-LIGHT HOLDER");
        record.put("quantity", "6");
        record.put("invoice_date", "2010-12-01 08:26:00");
        record.put("unit_price", "2.55");
        record.put("customer_id", "17850");
        record.put("country", "United Kingdom");
        return record;
    }

    private List<Violation> validateWith(String field, String value) {
        Map<String, String> record = validRecord();
        record.put(field, value);
        return validator.validate(record);
    }

    private void assertSingle(String field, String value, ErrorCode code) {
        assertThat(validateWith(field, value))
                .extracting(Violation::field, Violation::errorCode)
                .containsExactly(tuple(field, code));
    }

    @Test
    void ac_06_1_aValidRecordHasNoViolations() {
        assertThat(validator.validate(validRecord())).isEmpty();
    }

    static Stream<Arguments> section31Table() {
        return Stream.of(
                Arguments.of("quantity", "6.0", INVALID_FORMAT),
                Arguments.of("quantity", "1 000", INVALID_FORMAT),
                Arguments.of("quantity", "seis", INVALID_TYPE),
                Arguments.of("quantity", "6x", INVALID_TYPE),
                Arguments.of("quantity", "--6", INVALID_TYPE),
                Arguments.of("unit_price", "2,55", INVALID_FORMAT),
                Arguments.of("unit_price", "£2.55", INVALID_FORMAT),
                Arguments.of("unit_price", "2.555", INVALID_FORMAT),
                Arguments.of("unit_price", "dos", INVALID_TYPE),
                Arguments.of("unit_price", "2.5.5", INVALID_TYPE),
                Arguments.of("unit_price", "12abc", INVALID_TYPE),
                Arguments.of("invoice_date", "25/12/2010 08:26", INVALID_FORMAT),
                Arguments.of("invoice_date", "ayer", INVALID_TYPE),
                Arguments.of("invoice_date", "N/D 2010", INVALID_TYPE));
    }

    @ParameterizedTest(name = "AC-06.6 docs/02 §3.1: {0} = \"{1}\" -> {2}")
    @MethodSource("section31Table")
    void ac_06_6_typeVersusFormat(String field, String value, ErrorCode code) {
        assertSingle(field, value, code);
    }

    static Stream<Arguments> defectCatalogue() {
        return Stream.of(
                Arguments.of("WHITESPACE", "stock_code", "  85123A ", PATTERN_MISMATCH),
                Arguments.of("WHITESPACE", "invoice_no", "\t536365 ", PATTERN_MISMATCH),
                Arguments.of("WHITESPACE", "country", " United Kingdom", INVALID_ENUM_VALUE),
                Arguments.of("CASE", "country", "united kingdom", INVALID_ENUM_VALUE),
                Arguments.of("DATE_DMY_UNAMBIGUOUS", "invoice_date", "25/12/2010 08:26", INVALID_FORMAT),
                Arguments.of("DATE_ISO_T", "invoice_date", "2010-12-25T08:26:00", INVALID_FORMAT),
                Arguments.of("DATE_AMBIGUOUS", "invoice_date", "03/04/2010 09:15", INVALID_FORMAT),
                Arguments.of("DECIMAL_COMMA", "unit_price", "2,55", INVALID_FORMAT),
                Arguments.of("CURRENCY_SYMBOL", "unit_price", "£2.55", INVALID_FORMAT),
                Arguments.of("FLOAT_ID", "customer_id", "17850.0", PATTERN_MISMATCH),
                Arguments.of("COUNTRY_SYNONYM", "country", "UK", INVALID_ENUM_VALUE),
                Arguments.of("NULL_TOKEN_OPTIONAL", "customer_id", "N/A", PATTERN_MISMATCH),
                Arguments.of("NULL_TOKEN_REQUIRED", "unit_price", "N/A", MISSING_REQUIRED_FIELD),
                Arguments.of("NULL_TOKEN_REQUIRED", "quantity", "N/A", MISSING_REQUIRED_FIELD),
                Arguments.of("MISSING_REQUIRED", "unit_price", "", MISSING_REQUIRED_FIELD),
                Arguments.of("MISSING_REQUIRED", "invoice_date", "", MISSING_REQUIRED_FIELD),
                Arguments.of("MISSING_REQUIRED", "country", "", MISSING_REQUIRED_FIELD),
                Arguments.of("NEGATIVE_PRICE", "unit_price", "-2.55", OUT_OF_RANGE),
                Arguments.of("QUANTITY_OUTLIER", "quantity", "999999", OUT_OF_RANGE),
                Arguments.of("GARBAGE_NUMBER", "quantity", "seis", INVALID_TYPE),
                Arguments.of("GARBAGE_NUMBER", "quantity", "6x", INVALID_TYPE),
                Arguments.of("FUTURE_DATE", "invoice_date", "2099-12-01 08:26:00", OUT_OF_RANGE));
    }

    // COLUMN_SHIFT is MALFORMED_ROW, which the reader emits before validation (docs/02 section 3.1, rule 2).
    @ParameterizedTest(name = "AC-06.6 {0}: {1} = \"{2}\" -> {3}")
    @MethodSource("defectCatalogue")
    void ac_06_6_everyFieldDefectGetsItsExpectedCode(String defect, String field, String value, ErrorCode code) {
        assertSingle(field, value, code);
    }

    @Test
    void ac_06_6_cancelSignMismatchBreaksABusinessRule() {
        Map<String, String> record = validRecord();
        record.put("invoice_no", "C536379");
        record.put("quantity", "5");

        assertThat(validator.validate(record))
                .extracting(Violation::field, Violation::errorCode, Violation::expected)
                .containsExactly(tuple(Validator.RECORD, BUSINESS_RULE_VIOLATION, Map.of("rule", "BR-01")));
    }

    @Test
    void ac_06_2_everyViolationIsReported() {
        Map<String, String> record = validRecord();
        record.put("invoice_date", "25/12/2010 08:26");
        record.put("unit_price", "£2.55");
        record.put("country", "UK");

        assertThat(validator.validate(record))
                .extracting(Violation::field, Violation::errorCode)
                .containsExactly(
                        tuple("invoice_date", INVALID_FORMAT),
                        tuple("unit_price", INVALID_FORMAT),
                        tuple("country", INVALID_ENUM_VALUE));
    }

    @Test
    void ac_06_3_businessRulesNeedValidFields() {
        Map<String, String> negativeWithoutC = validRecord();
        negativeWithoutC.put("quantity", "-6");
        assertThat(validator.validate(negativeWithoutC))
                .extracting(Violation::expected)
                .containsExactly(Map.of("rule", "BR-02"));

        negativeWithoutC.put("invoice_no", " 536365");
        assertThat(validator.validate(negativeWithoutC))
                .extracting(Violation::field)
                .containsExactly("invoice_no");
    }

    @Test
    void ac_06_7_theUpperDateBoundComesFromTheClock() {
        assertThat(validateWith("invoice_date", "2026-10-06 00:00:00")).isEmpty();
        assertSingle("invoice_date", "2026-10-06 00:00:01", OUT_OF_RANGE);
    }

    @Test
    void dateBoundsAndCalendarAreStrict() {
        assertThat(validateWith("invoice_date", "2009-01-01 00:00:00")).isEmpty();
        assertSingle("invoice_date", "2008-12-31 23:59:59", OUT_OF_RANGE);
        assertSingle("invoice_date", "2010-02-30 08:26:00", INVALID_FORMAT);
        assertSingle("invoice_date", "2010-12-01 8:26:00", INVALID_FORMAT);
    }

    @Test
    void quantityRange() {
        assertSingle("quantity", "0", OUT_OF_RANGE);
        assertSingle("quantity", "100001", OUT_OF_RANGE);
        assertSingle("quantity", "99999999999999999999999", OUT_OF_RANGE);
        assertThat(validateWith("quantity", "100000")).isEmpty();
    }

    @Test
    void unitPriceAcceptsUpToTwoDecimals() {
        assertThat(validateWith("unit_price", "3")).isEmpty();
        assertThat(validateWith("unit_price", "2.5")).isEmpty();
        assertThat(validateWith("unit_price", "0")).isEmpty();
        assertSingle("unit_price", "100000.01", OUT_OF_RANGE);
        assertSingle("unit_price", "$2.55", INVALID_FORMAT);
    }

    @Test
    void onlyOneViolationPerField() {
        assertSingle("unit_price", " N/A\t", MISSING_REQUIRED_FIELD);
        assertSingle("stock_code", "", MISSING_REQUIRED_FIELD);
    }

    @Test
    void nullTokensAndAbsentValues() {
        assertThat(validateWith("customer_id", "")).isEmpty();
        assertThat(validateWith("customer_id", null)).isEmpty();
        assertThat(validateWith("description", "N/A")).isEmpty();
        assertSingle("customer_id", "   ", PATTERN_MISMATCH);

        Map<String, String> withoutCountry = validRecord();
        withoutCountry.remove("country");
        assertThat(validator.validate(withoutCountry))
                .extracting(Violation::errorCode, Violation::receivedValue)
                .containsExactly(tuple(MISSING_REQUIRED_FIELD, null));
    }

    @Test
    void onlySpaceAndTabAreWhitespace() {
        assertSingle("customer_id", " ", PATTERN_MISMATCH);
        assertSingle("stock_code", "85123A ", PATTERN_MISMATCH);
    }

    @Test
    void descriptionLength() {
        assertThat(validateWith("description", "x".repeat(255))).isEmpty();
        assertSingle("description", "x".repeat(256), MAX_LENGTH_EXCEEDED);
    }

    @Test
    void expectedUsesTheContractKeys() {
        Violation price = validateWith("unit_price", "£1,25").getFirst();
        assertThat(price.expected())
                .containsExactly(
                        Map.entry("type", "decimal"),
                        Map.entry("scale", 2),
                        Map.entry("min", "0"),
                        Map.entry("max", "100000"),
                        Map.entry("decimalSeparator", "."));
        assertThat(price.sourceHeader()).isEqualTo("Price");
        assertThat(price.exceptionType()).isEqualTo("java.lang.NumberFormatException");

        Violation date = validateWith("invoice_date", "25/12/2010 08:26").getFirst();
        assertThat(date.expected())
                .containsExactly(
                        Map.entry("type", "datetime"),
                        Map.entry("format", "yyyy-MM-dd HH:mm:ss"),
                        Map.entry("min", "2009-01-01 00:00:00"));
        assertThat(date.exceptionType()).isEqualTo("java.time.format.DateTimeParseException");

        Violation country = validateWith("country", "UK").getFirst();
        assertThat(country.technicalMessage()).isEqualTo("Value 'UK' is not in catalog countries.v1");
        assertThat((List<?>) country.expected().get("allowedValues")).hasSize(41);
    }

    @Test
    void technicalMessageIsTruncated() {
        // Long.parseLong echoes the whole input in its message; DateTimeParseException already shortens it.
        Violation violation = validateWith("quantity", "x".repeat(5000)).getFirst();

        assertThat(violation.technicalMessage()).hasSize(1000);
        assertThat(violation.receivedValue()).hasSize(5000);
    }
}
