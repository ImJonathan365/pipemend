package com.pipemend.pipeline.verifiers;

import com.pipemend.pipeline.schema.FieldRule.EnumRule;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.schema.SchemaTestSupport;
import com.pipemend.pipeline.triage.contract.Operation;
import com.pipemend.pipeline.triage.contract.TriageResponse;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Hand-written inputs over the real schema and the real aliases file, read only. */
final class VerifierTestSupport {

    static final SchemaDefinition SCHEMA = SchemaTestSupport.realSchema();
    static final CountryAliases ALIASES =
            CountryAliases.load(SchemaTestSupport.resource("country-aliases.v1.yaml"), (EnumRule)
                    SCHEMA.field("country").rule());

    private VerifierTestSupport() {}

    static VerificationInput input(
            String field,
            @Nullable String original,
            Operation operation,
            @Nullable String proposed,
            double confidence) {
        return new VerificationInput(
                field, original, new TriageResponse.Correction(operation, original, proposed, Map.of()), confidence);
    }
}
