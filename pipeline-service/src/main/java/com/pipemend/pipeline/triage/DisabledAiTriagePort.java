package com.pipemend.pipeline.triage;

import com.pipemend.pipeline.batch.RawRecord;
import com.pipemend.pipeline.validation.Violation;
import java.util.List;

/**
 * Stands in until the HTTP client exists (docs/07, Sprint 2): every invalid record is quarantined with
 * AI_UNAVAILABLE, so the count invariant holds from the first batch.
 */
public final class DisabledAiTriagePort implements AiTriagePort {

    @Override
    public TriageOutcome triage(RawRecord record, List<Violation> violations) {
        return new TriageOutcome.Unavailable("AI triage is not enabled in this build");
    }
}
