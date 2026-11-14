package com.pipemend.pipeline.verifiers;

import static com.pipemend.pipeline.verifiers.VerifierTestSupport.input;
import static org.assertj.core.api.Assertions.assertThat;

import com.pipemend.pipeline.triage.contract.Operation;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** PARSE_DATE of docs/02 section 4; the canonical format is yyyy-MM-dd HH:mm:ss (sales_transaction.v1.yaml). */
class ParseDateVerifierTest {

    private final ParseDateVerifier verifier = new ParseDateVerifier(VerifierTestSupport.SCHEMA);

    @ParameterizedTest(name = "{0} with {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "25/12/2010 08:26|dd/MM/yyyy HH:mm|2010-12-25 08:26:00",
                "' 25/12/2010 08:26 '|dd/MM/yyyy HH:mm|2010-12-25 08:26:00",
                "25.12.2010 08:26:30|dd.MM.yyyy HH:mm:ss|2010-12-25 08:26:30",
                "2010/12/25 08:26|yyyy/MM/dd HH:mm|2010-12-25 08:26:00",
                "2010-12-25T08:26:00|yyyy-MM-dd'T'HH:mm:ss|2010-12-25 08:26:00",
                "13/2/2010 8:05|d/M/yyyy H:mm|2010-02-13 08:05:00",
                // Day equal to month: the swapped pattern gives the same date.
                "03/03/2010 09:15|dd/MM/yyyy HH:mm|2010-03-03 09:15:00"
            })
    void ac_10_1_aReparsedDateInTheCanonicalFormatIsAccepted(String original, String pattern, String proposed) {
        assertThat(verify(original, pattern, proposed)).isEqualTo(VerificationResult.success());
    }

    @ParameterizedTest(name = "{0} with {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "03/04/2010 09:15|dd/MM/yyyy HH:mm|2010-04-03 09:15:00",
                // AC-10.6: day and month swapped in an ambiguous date.
                "03/04/2010 09:15|MM/dd/yyyy HH:mm|2010-03-04 09:15:00",
                "2010/12/03 08:26|yyyy/MM/dd HH:mm|2010-12-03 08:26:00",
                "1/2/2010 8:05|d/M/yyyy H:mm|2010-02-01 08:05:00"
            })
    void ac_10_5_aDateThatAlsoParsesWithDayAndMonthSwappedIsAmbiguous(
            String original, String pattern, String proposed) {
        assertThat(verify(original, pattern, proposed).code()).isEqualTo(VerificationCode.AMBIGUOUS_DATE);
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @CsvSource(
            delimiter = '|',
            value = {
                "31/02/2010 08:26|dd/MM/yyyy HH:mm|2010-02-31 08:26:00",
                // SMART would resolve 31 February to the 28th; STRICT does not.
                "31/02/2010 08:26|dd/MM/yyyy HH:mm|2010-02-28 08:26:00",
                "25/12/2010 08:26|dd/MM/yyyy HH:mm|2010-12-24 08:26:00",
                // AC-10.6: a proposed value that is not in the canonical format.
                "25/12/2010 08:26|dd/MM/yyyy HH:mm|2010-12-25T08:26:00",
                "25/12/2010 08:26|dd/MM/yyyy HH:mm|2010-12-25 08:26",
                "25/12/2010|dd/MM/yyyy HH:mm|2010-12-25 00:00:00",
                "25-12-2010 08:26|dd/MM/yyyy HH:mm|2010-12-25 08:26:00"
            })
    void ac_10_1_anythingButTheReparsedCanonicalValueIsRejected(String original, String pattern, String proposed) {
        assertThat(verify(original, pattern, proposed).code()).isEqualTo(VerificationCode.VALUE_MISMATCH);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "dd/MM/yyyy",
                "dd/MM/yyyy HH",
                "dd/MM/yyyy HH:m",
                "dd MMM yyyy HH:mm",
                "dd LLL yyyy HH:mm",
                "dd/MM/yyyy HH:mm QQQ",
                "EEE dd/MM/yyyy HH:mm",
                "dd/MM/yyyy hh:mm a",
                "dd/MM/yyyy HH:mm z",
                "dd/MM/yyyy HH:mmX",
                "dd/MM/yyyy HH:mm VV",
                "dd/MM/yyyy HH:mm '",
                "dd/MM/yyyy HH:mm {"
            })
    void ac_10_5_aPatternWithoutHourAndMinuteOrWithZoneOrTextHasInvalidParameters(String pattern) {
        assertThat(verify("25/12/2010 08:26", pattern, "2010-12-25 08:26:00").code())
                .isEqualTo(VerificationCode.INVALID_PARAMETERS);
    }

    @Test
    void ac_10_5_aMissingOrNonTextPatternHasInvalidParameters() {
        assertThat(verifier.verify(input("invoice_date", "25/12/2010 08:26", Operation.PARSE_DATE, "x", Map.of()))
                        .code())
                .isEqualTo(VerificationCode.INVALID_PARAMETERS);
        assertThat(verifier.verify(input(
                                "invoice_date",
                                "25/12/2010 08:26",
                                Operation.PARSE_DATE,
                                "x",
                                Map.of("sourcePattern", 5)))
                        .code())
                .isEqualTo(VerificationCode.INVALID_PARAMETERS);
    }

    @Test
    void ac_10_1_aMissingValueCannotBeParsed() {
        assertThat(verify(null, "dd/MM/yyyy HH:mm", "2010-12-25 08:26:00").code())
                .isEqualTo(VerificationCode.VALUE_MISMATCH);
    }

    private VerificationResult verify(@Nullable String original, String pattern, String proposed) {
        return verifier.verify(
                input("invoice_date", original, Operation.PARSE_DATE, proposed, Map.of("sourcePattern", pattern)));
    }
}
