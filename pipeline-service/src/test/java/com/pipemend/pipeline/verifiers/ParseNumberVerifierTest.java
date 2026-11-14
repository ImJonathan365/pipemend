package com.pipemend.pipeline.verifiers;

import static com.pipemend.pipeline.verifiers.VerifierTestSupport.input;
import static org.assertj.core.api.Assertions.assertThat;

import com.pipemend.pipeline.triage.contract.Operation;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * PARSE_NUMBER of docs/02 section 4. In the tables, "_" stands for a null grouping separator and "~" for a space, which
 * a CSV source would trim.
 */
class ParseNumberVerifierTest {

    private final ParseNumberVerifier verifier = new ParseNumberVerifier();

    @ParameterizedTest(name = "{0}: {1} -> {4}")
    @CsvSource(
            delimiter = '|',
            value = {
                "unit_price|\u00A31,25|,|_|1.25",
                "unit_price|2.55\u00A3|.|_|2.55",
                "unit_price|-\u00A32.55|.|_|-2.55",
                "unit_price|\u00A3-2.55|.|_|-2.55",
                "unit_price|1.234,50|,|.|1234.50",
                "unit_price|12,345,678|.|,|12345678",
                "unit_price|' 2,55 '|,|_|2.55",
                "quantity|1~000|.|~|1000",
                "quantity|6.0|.|_|6",
                "quantity|-6|.|_|-6",
                "customer_id|17850.0|.|_|17850"
            })
    void ac_10_1_aRenormalizedNumberIsAccepted(
            String field, String original, String decimal, String grouping, String proposed) {
        assertThat(verify(field, original, decimal, grouping, proposed)).isEqualTo(VerificationResult.success());
    }

    @ParameterizedTest(name = "{0}: {1} -> {4}")
    @CsvSource(
            delimiter = '|',
            value = {
                // (4) of docs/02: one separator followed by exactly three digits. AC-10.6: "2,550" -> 2.55.
                "unit_price|2,550|,|_|2.55",
                "unit_price|1.234|.|_|1.234",
                "unit_price|\u00A31.234|.|_|1234",
                // B2: with a grouping separator, every group after the first has exactly three digits.
                "unit_price|12,34|.|,|1234",
                "unit_price|2,5|.|,|25",
                "unit_price|1,2,3|.|,|123",
                "unit_price|1234,567|.|,|1234567",
                "unit_price|1234,567,890|.|,|1234567890",
                "quantity|1~00|.|~|100"
            })
    void ac_10_5_separatorsThatAdmitTwoReadingsAreAmbiguous(
            String field, String original, String decimal, String grouping, String proposed) {
        assertThat(verify(field, original, decimal, grouping, proposed).code())
                .isEqualTo(VerificationCode.AMBIGUOUS_NUMBER);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                // AC-10.6: "\u20AC2.55" -> 2.55.
                "unit_price|\u20AC2.55|2.55",
                "unit_price|$2.55|2.55",
                "unit_price|2.55\u00A5|2.55",
                "quantity|\u00A36|6",
                "customer_id|\u00A317850|17850"
            })
    void ac_10_5_anyCurrencyButThePoundOfUnitPriceIsNotConvertible(String field, String original, String proposed) {
        assertThat(verify(field, original, ".", "_", proposed).code())
                .isEqualTo(VerificationCode.CURRENCY_NOT_CONVERTIBLE);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                // AC-10.6: "-2.55" -> 2.55.
                "-2.55|2.55",
                "2.55|-2.55",
                "2.55|+2.55",
                "-\u00A32.55|2.55"
            })
    void ac_10_5_theSignNeverChanges(String original, String proposed) {
        assertThat(verify("unit_price", original, ".", "_", proposed).code()).isEqualTo(VerificationCode.SIGN_CHANGED);
    }

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @CsvSource(
            delimiter = '|',
            value = {
                // AC-10.6: "17850.5" -> 17850.
                "customer_id|17850.5|17850",
                "customer_id|17850.0|17850.0",
                "quantity|6.5|6.5",
                "unit_price|2.55|2.56",
                "unit_price|\u00A32.55\u00A3|2.55",
                "unit_price|2\u00A3.55|2.55",
                "unit_price|2.5.5|2.55",
                "unit_price|2.55|abc",
                "unit_price|2,55|2.55",
                "unit_price|.55|0.55"
            })
    void ac_10_1_anythingButTheRenormalizedValueIsRejected(String field, String original, String proposed) {
        assertThat(verify(field, original, ".", "_", proposed).code()).isEqualTo(VerificationCode.VALUE_MISMATCH);
    }

    @ParameterizedTest(name = "decimal {0}, grouping {1}")
    @CsvSource(
            delimiter = '|',
            value = {";|_", "_|_", ".|.", ",|,", ".|-"})
    void ac_10_5_missingUnknownOrEqualSeparatorsAreInvalidParameters(String decimal, String grouping) {
        assertThat(verify("unit_price", "2.55", decimal, grouping, "2.55").code())
                .isEqualTo(VerificationCode.INVALID_PARAMETERS);
    }

    @Test
    void ac_10_1_aMissingValueCannotBeParsed() {
        assertThat(verify("unit_price", null, ".", "_", "2.55").code()).isEqualTo(VerificationCode.VALUE_MISMATCH);
    }

    private VerificationResult verify(
            String field, @Nullable String original, String decimal, String grouping, String proposed) {
        Map<String, @Nullable Object> parameters = new HashMap<>();
        parameters.put("decimalSeparator", decimal.equals("_") ? null : decimal);
        parameters.put("groupingSeparator", grouping.equals("_") ? null : grouping.replace('~', ' '));
        return verifier.verify(input(
                field,
                original == null ? null : original.replace('~', ' '),
                Operation.PARSE_NUMBER,
                proposed,
                parameters));
    }
}
