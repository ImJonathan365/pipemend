package com.pipemend.pipeline.triage.contract;

/** The allowlist of docs/02 section 4 that triage can propose; RENAME_COLUMN belongs to schema drift only. */
public enum Operation {
    TRIM,
    NORMALIZE_CASE,
    PARSE_DATE,
    PARSE_NUMBER,
    MAP_TO_ENUM,
    NULLIFY_TOKEN
}
