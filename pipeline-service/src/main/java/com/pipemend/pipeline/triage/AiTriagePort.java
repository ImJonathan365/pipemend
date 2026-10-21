package com.pipemend.pipeline.triage;

import com.pipemend.pipeline.batch.RawRecord;
import com.pipemend.pipeline.validation.Violation;
import java.util.List;

/** Asks the AI service to analyse an invalid record (FR-08). The pipeline decides what to do with the answer. */
public interface AiTriagePort {

    TriageOutcome triage(RawRecord record, List<Violation> violations);
}
