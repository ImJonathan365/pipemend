package com.pipemend.pipeline.triage.contract;

/** Declared from lowest to highest: the validator takes maxSeverity from this order. */
public enum Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
