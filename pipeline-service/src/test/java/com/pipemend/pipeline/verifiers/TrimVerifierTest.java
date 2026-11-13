package com.pipemend.pipeline.verifiers;

import static com.pipemend.pipeline.verifiers.VerifierTestSupport.input;
import static org.assertj.core.api.Assertions.assertThat;

import com.pipemend.pipeline.triage.contract.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** TRIM of docs/02 section 4; a space is U+0020 or tab (docs/02 section 2). */
class TrimVerifierTest {

    private final TrimVerifier verifier = new TrimVerifier();

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {"' 85123A '|85123A", "'\t536365'|536365", "'United Kingdom  '|United Kingdom"})
    void ac_10_1_theOuterSpacesAndTabsAreRemoved(String original, String proposed) {
        assertThat(verify(original, proposed)).isEqualTo(VerificationResult.success());
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                // AC-10.6: a TRIM that also changes one character.
                "' 85123A '|85123B",
                "' 85123A '|' 85123A '",
                "'   '|''",
                "' 85123A '|' 85123A'"
            })
    void ac_10_1_anythingButTheStrippedValueIsRejected(String original, String proposed) {
        assertThat(verify(original, proposed).code()).isEqualTo(VerificationCode.VALUE_MISMATCH);
    }

    /** String.strip() would remove an em space or a line break, which docs/02 section 2 does not let TRIM touch. */
    @ParameterizedTest
    @ValueSource(ints = {0xA0, 0x2003, 0x0A, 0x0B, 0x3000})
    void ac_10_1_onlySpaceAndTabAreWhitespace(int character) {
        String other = String.valueOf((char) character);

        assertThat(verify(other + "85123A" + other, "85123A").code()).isEqualTo(VerificationCode.VALUE_MISMATCH);
    }

    @Test
    void ac_10_1_aMissingValueCannotBeTrimmed() {
        assertThat(verify(null, "85123A").code()).isEqualTo(VerificationCode.VALUE_MISMATCH);
    }

    private VerificationResult verify(String original, String proposed) {
        return verifier.verify(input("stock_code", original, Operation.TRIM, proposed, 0.99));
    }
}
