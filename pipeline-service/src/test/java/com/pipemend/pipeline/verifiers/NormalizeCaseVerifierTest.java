package com.pipemend.pipeline.verifiers;

import static com.pipemend.pipeline.verifiers.VerifierTestSupport.input;
import static org.assertj.core.api.Assertions.assertThat;

import com.pipemend.pipeline.triage.contract.Operation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** NORMALIZE_CASE of docs/02 section 4: only the case changes, and the result is a valid value of the field. */
class NormalizeCaseVerifierTest {

    private final NormalizeCaseVerifier verifier = new NormalizeCaseVerifier(VerifierTestSupport.SCHEMA);

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @CsvSource(
            delimiter = '|',
            value = {
                "country|united kingdom|United Kingdom",
                "country|' FRANCE '|France",
                "country|eire|EIRE",
                "invoice_no|c536365|C536365",
                "invoice_no|' a536365 '|A536365"
            })
    void ac_10_1_aChangeOfCaseToAValidValueIsAccepted(String field, String original, String proposed) {
        assertThat(verify(field, original, proposed)).isEqualTo(VerificationResult.success());
    }

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @CsvSource(
            delimiter = '|',
            value = {
                // Same letters, but not a value of the catalogue (it is exact and case sensitive).
                "country|united KINGDOM|United kingdom",
                "country|Frace|France",
                "invoice_no|x536365|X536365",
                // AC-10.6: NORMALIZE_CASE on stock_code, whose case is significant.
                "stock_code|85123a|85123A",
                "country|Atlantis|ATLANTIS"
            })
    void ac_10_1_aValueThatIsNotJustTheCaseOrNotValidIsRejected(String field, String original, String proposed) {
        assertThat(verify(field, original, proposed).code()).isEqualTo(VerificationCode.VALUE_MISMATCH);
    }

    private VerificationResult verify(String field, String original, String proposed) {
        return verifier.verify(input(field, original, Operation.NORMALIZE_CASE, proposed, 0.99));
    }
}
