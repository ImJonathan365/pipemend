package com.pipemend.pipeline.triage;

import com.pipemend.pipeline.triage.contract.TriageRequest;

/**
 * Stands in until the HTTP client exists (docs/07, Sprint 2): every invalid record is quarantined with
 * AI_UNAVAILABLE, so the count invariant holds from the first batch.
 */
public final class DisabledAiTriagePort implements AiTriagePort {

    @Override
    public TriageOutcome triage(TriageRequest request) {
        return new TriageOutcome.Unavailable("AI triage is not enabled in this build");
    }
}
