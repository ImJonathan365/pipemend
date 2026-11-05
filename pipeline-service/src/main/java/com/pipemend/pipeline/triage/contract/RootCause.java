package com.pipemend.pipeline.triage.contract;

public enum RootCause {
    FORMAT_MISMATCH,
    LOCALE_FORMAT,
    WHITESPACE,
    CASING,
    SYNONYM,
    NULL_TOKEN,
    MISSING_VALUE,
    IMPLAUSIBLE_VALUE,
    TYPE_MISMATCH,
    AMBIGUOUS_VALUE,
    BUSINESS_RULE,
    STRUCTURAL,
    UNKNOWN
}
