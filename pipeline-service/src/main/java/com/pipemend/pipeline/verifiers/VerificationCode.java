package com.pipemend.pipeline.verifiers;

/** The rejection codes of AC-10.5, stored per violation in quarantine_record.policy_decision. */
public enum VerificationCode {
    AMBIGUOUS_DATE,
    AMBIGUOUS_NUMBER,
    CURRENCY_NOT_CONVERTIBLE,
    UNVERIFIABLE_MAPPING,
    VALUE_MISMATCH,
    SIGN_CHANGED,
    INVALID_PARAMETERS
}
