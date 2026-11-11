package com.pipemend.pipeline.triage;

import com.pipemend.pipeline.triage.contract.TriageResponse;

/** What the triage port returned for one record; the quarantine reason follows from the variant (docs/06 A.5). */
public sealed interface TriageOutcome {

    /** @param body as received, for ai_analysis */
    record Analysed(TriageResponse response, String body, TriageResponseValidator.Result.Valid verdict)
            implements TriageOutcome {}

    /** AI_UNAVAILABLE */
    record Unavailable(String detail) implements TriageOutcome {}

    /** AI_INVALID_RESPONSE */
    record InvalidResponse(String detail) implements TriageOutcome {}

    /** AI_BUDGET_EXCEEDED */
    record BudgetExhausted(String detail) implements TriageOutcome {}
}
