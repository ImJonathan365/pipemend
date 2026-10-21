package com.pipemend.pipeline.triage;

/** What the triage port returned for one record. */
public sealed interface TriageOutcome {

    /** The AI service could not be used; the record goes to quarantine with AI_UNAVAILABLE (FR-12). */
    record Unavailable(String detail) implements TriageOutcome {}
}
