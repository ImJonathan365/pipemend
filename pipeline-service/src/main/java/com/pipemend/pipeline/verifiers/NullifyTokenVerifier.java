package com.pipemend.pipeline.verifiers;

import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.validation.Whitespace;

/** NULLIFY_TOKEN (docs/02 section 4): an optional field, strip(original) among the null tokens, and proposed null. */
public final class NullifyTokenVerifier implements Verifier {

    private final SchemaDefinition schema;

    public NullifyTokenVerifier(SchemaDefinition schema) {
        this.schema = schema;
    }

    @Override
    public VerificationResult verify(VerificationInput input) {
        String original = input.original();
        boolean optional =
                schema.fields().stream().anyMatch(field -> field.name().equals(input.field()) && !field.required());
        if (!optional) {
            return VerificationResult.failure(
                    VerificationCode.VALUE_MISMATCH, "Only an optional field can become null");
        }
        if (original == null || !schema.nullTokens().contains(Whitespace.strip(original))) {
            return VerificationResult.failure(
                    VerificationCode.VALUE_MISMATCH, "The original value is not a null token");
        }
        if (input.proposed() != null) {
            return VerificationResult.failure(VerificationCode.VALUE_MISMATCH, "NULLIFY_TOKEN must propose null");
        }
        return VerificationResult.success();
    }
}
