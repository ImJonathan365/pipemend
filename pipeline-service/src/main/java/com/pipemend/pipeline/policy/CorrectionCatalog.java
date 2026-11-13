package com.pipemend.pipeline.policy;

import com.pipemend.pipeline.triage.contract.Operation;
import com.pipemend.pipeline.validation.ErrorCode;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The fixed universe of docs/02 section 4 for record corrections: what each operation may touch, and the error codes
 * that are never corrected (AC-09.1). The configured allowlist can only narrow it; widening it takes an ADR (AC-09.4).
 */
public final class CorrectionCatalog {

    public static final Map<Operation, Set<String>> FIELDS = fields();

    public static final Set<ErrorCode> NOT_CORRECTABLE = Set.copyOf(EnumSet.of(
            ErrorCode.MISSING_REQUIRED_FIELD,
            ErrorCode.OUT_OF_RANGE,
            ErrorCode.MAX_LENGTH_EXCEEDED,
            ErrorCode.BUSINESS_RULE_VIOLATION,
            ErrorCode.MALFORMED_ROW));

    private CorrectionCatalog() {}

    private static Map<Operation, Set<String>> fields() {
        Map<Operation, Set<String>> fields = new EnumMap<>(Operation.class);
        fields.put(Operation.TRIM, Set.of("invoice_no", "stock_code", "country", "customer_id"));
        // Not stock_code: its case is significant.
        fields.put(Operation.NORMALIZE_CASE, Set.of("country", "invoice_no"));
        fields.put(Operation.PARSE_DATE, Set.of("invoice_date"));
        fields.put(Operation.PARSE_NUMBER, Set.of("unit_price", "quantity", "customer_id"));
        fields.put(Operation.MAP_TO_ENUM, Set.of("country"));
        fields.put(Operation.NULLIFY_TOKEN, Set.of("customer_id"));
        return Map.copyOf(fields);
    }
}
