package com.pipemend.pipeline.batch;

import com.pipemend.pipeline.persistence.IngestionBatchRepository;
import java.util.UUID;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

/** Keeps ingestion_batch in step with the job (docs/02 section 5): RUNNING at start, FAILED with its reason at the end. */
public class BatchStatusListener implements JobExecutionListener {

    private final IngestionBatchRepository batches;

    public BatchStatusListener(IngestionBatchRepository batches) {
        this.batches = batches;
    }

    @Override
    public void beforeJob(JobExecution execution) {
        batches.markRunning(IngestionJobConfiguration.batchId(execution), execution.getId());
    }

    @Override
    public void afterJob(JobExecution execution) {
        if (execution.getStatus() != BatchStatus.FAILED) {
            return;
        }
        boolean invariant =
                execution.getAllFailureExceptions().stream().anyMatch(InvariantViolationException.class::isInstance);
        UUID batchId = IngestionJobConfiguration.batchId(execution);
        batches.fail(batchId, invariant ? "INVARIANT_VIOLATION" : "INFRASTRUCTURE_ERROR");
    }
}
