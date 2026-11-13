package com.pipemend.pipeline.verifiers;

import com.pipemend.pipeline.schema.FieldRule.EnumRule;
import com.pipemend.pipeline.schema.FieldRule.StringRule;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.validation.Whitespace;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * NORMALIZE_CASE (docs/02 section 4): the same letters as strip(original) in another case, and a valid value of the
 * field: the exact catalogue for country, the schema pattern for invoice_no. Never stock_code, whose case matters.
 */
public final class NormalizeCaseVerifier implements Verifier {

    private final Set<String> countries;
    private final Pattern invoiceNo;

    public NormalizeCaseVerifier(SchemaDefinition schema) {
        this.countries = ((EnumRule) schema.field("country").rule()).values();
        this.invoiceNo = ((StringRule) schema.field("invoice_no").rule()).pattern();
    }

    @Override
    public VerificationResult verify(VerificationInput input) {
        String original = input.original();
        String proposed = input.proposed();
        if (original == null || proposed == null) {
            return VerificationResult.failure(VerificationCode.VALUE_MISMATCH, "NORMALIZE_CASE needs a value");
        }
        // "equalsIgnoreCase with Locale.ROOT" (docs/02): String.equalsIgnoreCase takes no locale, so both sides go
        // through toLowerCase(Locale.ROOT).
        String s = Whitespace.strip(original);
        if (!proposed.toLowerCase(Locale.ROOT).equals(s.toLowerCase(Locale.ROOT))) {
            return VerificationResult.failure(
                    VerificationCode.VALUE_MISMATCH, "The proposed value differs from the original in more than case");
        }
        boolean valid = switch (input.field()) {
            case "country" -> countries.contains(proposed);
            case "invoice_no" -> invoiceNo.matcher(proposed).matches();
            default -> false;
        };
        if (!valid) {
            return VerificationResult.failure(
                    VerificationCode.VALUE_MISMATCH, "The proposed value is not a valid value of the field");
        }
        return VerificationResult.success();
    }
}
