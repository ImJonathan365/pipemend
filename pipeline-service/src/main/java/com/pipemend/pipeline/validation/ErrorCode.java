package com.pipemend.pipeline.validation;

/** Violation catalogue of docs/02 section 3. */
public enum ErrorCode {
    MISSING_REQUIRED_FIELD,
    INVALID_TYPE,
    INVALID_FORMAT,
    PATTERN_MISMATCH,
    OUT_OF_RANGE,
    INVALID_ENUM_VALUE,
    MAX_LENGTH_EXCEEDED,
    BUSINESS_RULE_VIOLATION,
    MALFORMED_ROW,
    SCHEMA_DRIFT
}
