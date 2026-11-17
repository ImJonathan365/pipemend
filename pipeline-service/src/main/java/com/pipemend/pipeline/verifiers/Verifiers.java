package com.pipemend.pipeline.verifiers;

import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.triage.contract.Operation;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/** One verifier per operation of the allowlist (docs/02 section 4); it does not build with an operation missing. */
public final class Verifiers {

    private final Map<Operation, Verifier> byOperation;

    public Verifiers(Map<Operation, Verifier> byOperation) {
        EnumSet<Operation> missing = EnumSet.allOf(Operation.class);
        missing.removeAll(byOperation.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("No verifier for " + missing);
        }
        this.byOperation = Map.copyOf(byOperation);
    }

    public static Verifiers of(SchemaDefinition schema, CountryAliases aliases, double enumMinConfidence) {
        Map<Operation, Verifier> verifiers = new EnumMap<>(Operation.class);
        verifiers.put(Operation.TRIM, new TrimVerifier());
        verifiers.put(Operation.NORMALIZE_CASE, new NormalizeCaseVerifier(schema));
        verifiers.put(Operation.PARSE_DATE, new ParseDateVerifier(schema));
        verifiers.put(Operation.PARSE_NUMBER, new ParseNumberVerifier());
        verifiers.put(Operation.MAP_TO_ENUM, new MapToEnumVerifier(aliases, enumMinConfidence));
        verifiers.put(Operation.NULLIFY_TOKEN, new NullifyTokenVerifier(schema));
        return new Verifiers(verifiers);
    }

    public Verifier forOperation(Operation operation) {
        return byOperation.get(operation);
    }
}
