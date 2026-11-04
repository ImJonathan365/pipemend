package com.pipemend.pipeline.triage;

import com.pipemend.pipeline.triage.contract.TriageRequest;

/** Asks the AI service to analyse an invalid record (FR-08). The pipeline decides what to do with the answer. */
public interface AiTriagePort {

    TriageOutcome triage(TriageRequest request);
}
