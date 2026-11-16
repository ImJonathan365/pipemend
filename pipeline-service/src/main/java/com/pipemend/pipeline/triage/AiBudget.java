package com.pipemend.pipeline.triage;

import java.util.function.BooleanSupplier;

/**
 * The AI budget of one batch (AC-08.6), asked before every call the batch could make (docs/04 section 4). It keeps no
 * count of its own: during the job the source is ai_call_log (docs/05, ai_calls_count), so a restart gets no new
 * budget. Once spent it stays spent, because the rows are never deleted and the cap does not change while the step runs.
 */
public final class AiBudget {

    private final BooleanSupplier exhaustedInLog;
    private boolean exhausted;

    public AiBudget(BooleanSupplier exhaustedInLog) {
        this.exhaustedInLog = exhaustedInLog;
    }

    public boolean exhausted() {
        if (!exhausted) {
            exhausted = exhaustedInLog.getAsBoolean();
        }
        return exhausted;
    }
}
