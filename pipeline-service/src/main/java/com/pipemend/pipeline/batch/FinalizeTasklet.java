package com.pipemend.pipeline.batch;

import com.pipemend.pipeline.persistence.IngestionBatchRepository;
import com.pipemend.pipeline.persistence.IngestionBatchRepository.Counts;
import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/** Checks the count invariant of NFR-06 on the tables and closes the batch as COMPLETED, or fails the job. */
public class FinalizeTasklet implements Tasklet {

    private final IngestionBatchRepository batches;

    public FinalizeTasklet(IngestionBatchRepository batches) {
        this.batches = batches;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        UUID batchId = IngestionJobConfiguration.batchId(contribution.getStepExecution());
        Counts counts = batches.counts(batchId);
        if (!counts.invariantHolds()) {
            throw new InvariantViolationException("Count invariant violated for batch " + batchId + ": " + counts);
        }
        batches.complete(batchId, counts);
        return RepeatStatus.FINISHED;
    }
}
