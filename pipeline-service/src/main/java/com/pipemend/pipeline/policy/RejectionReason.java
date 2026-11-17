package com.pipemend.pipeline.policy;

/** Why the policy rejects one violation (AC-09.3), declared in the order the policy checks them. */
public enum RejectionReason {
    ERROR_CODE_NOT_CORRECTABLE,
    OPERATION_NOT_ALLOWED,
    FIELD_NOT_ALLOWED,
    SEVERITY_TOO_HIGH,
    CONFIDENCE_BELOW_THRESHOLD
}
