package com.pipemend.pipeline.verifiers;

import static com.pipemend.pipeline.verifiers.VerifierTestSupport.input;
import static org.assertj.core.api.Assertions.assertThat;

import com.pipemend.pipeline.triage.contract.Operation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** MAP_TO_ENUM of docs/02 section 4, against the real country-aliases.v1.yaml. */
class MapToEnumVerifierTest {

    private final MapToEnumVerifier verifier = new MapToEnumVerifier(VerifierTestSupport.ALIASES, 0.90);

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "UK|United Kingdom",
                "' gbr '|United Kingdom",
                "\u00D6sterreich|Austria",
                "belgi\u00EB|Belgium",
                "Great-Britain|United Kingdom",
                // No alias: the two normalize alike.
                "united.kingdom|United Kingdom",
                "\u00C9ire|EIRE"
            })
    void ac_10_1_anAliasOrTheSameNormalizedNameIsAccepted(String original, String proposed) {
        assertThat(verify(original, proposed, 0.95)).isEqualTo(VerificationResult.success());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                // AC-10.6: plausible but wrong.
                "Austria|Australia",
                "UK|USA",
                "Gibraltar|Spain",
                "USA|Canada"
            })
    void ac_10_5_aMappingNoAliasExplainsIsUnverifiable(String original, String proposed) {
        assertThat(verify(original, proposed, 0.99).code()).isEqualTo(VerificationCode.UNVERIFIABLE_MAPPING);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {"UK|Great Britain", "UK|united kingdom", "Atlantis|Atlantis"})
    void ac_10_1_aValueOutsideTheCatalogueIsRejected(String original, String proposed) {
        assertThat(verify(original, proposed, 0.99).code()).isEqualTo(VerificationCode.VALUE_MISMATCH);
    }

    /** The policy checks this too (AC-09.1); the repetition is intended (docs/02 section 4, condition 3). */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"0.90, true", "0.8999, false", "0.5, false"})
    void ac_10_1_theConfidenceMustReachAiEnumMinConfidence(double confidence, boolean passes) {
        VerificationResult result = verify("UK", "United Kingdom", confidence);

        assertThat(result.passed()).isEqualTo(passes);
        if (!passes) {
            assertThat(result.code()).isEqualTo(VerificationCode.UNVERIFIABLE_MAPPING);
        }
    }

    private VerificationResult verify(String original, String proposed, double confidence) {
        return verifier.verify(input("country", original, Operation.MAP_TO_ENUM, proposed, confidence));
    }
}
