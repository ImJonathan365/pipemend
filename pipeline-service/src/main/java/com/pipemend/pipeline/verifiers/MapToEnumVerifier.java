package com.pipemend.pipeline.verifiers;

import com.pipemend.pipeline.validation.Whitespace;

/**
 * MAP_TO_ENUM (docs/02 section 4): (1) proposed is in the catalogue; (2) the pair (normalize(s), proposed) is in
 * country-aliases.v1.yaml, or both normalize alike; (3) confidence reaches AI_ENUM_MIN_CONFIDENCE. The policy checks (3)
 * too: the repetition is on purpose, so neither check depends on the other.
 */
public final class MapToEnumVerifier implements Verifier {

    private final CountryAliases aliases;
    private final double enumMinConfidence;

    public MapToEnumVerifier(CountryAliases aliases, double enumMinConfidence) {
        this.aliases = aliases;
        this.enumMinConfidence = enumMinConfidence;
    }

    @Override
    public VerificationResult verify(VerificationInput input) {
        String original = input.original();
        String proposed = input.proposed();
        if (original == null || proposed == null || !aliases.catalogue().contains(proposed)) {
            return VerificationResult.failure(
                    VerificationCode.VALUE_MISMATCH, "The proposed value is not in the country catalogue");
        }
        String s = CountryAliases.normalize(Whitespace.strip(original));
        if (!aliases.maps(s, proposed) && !s.equals(CountryAliases.normalize(proposed))) {
            return VerificationResult.failure(
                    VerificationCode.UNVERIFIABLE_MAPPING, "No alias or normalization links the value to the country");
        }
        if (!(input.confidence() >= enumMinConfidence)) {
            return VerificationResult.failure(
                    VerificationCode.UNVERIFIABLE_MAPPING, "The confidence is below AI_ENUM_MIN_CONFIDENCE");
        }
        return VerificationResult.success();
    }
}
