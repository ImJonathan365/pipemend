package com.pipemend.pipeline.batch;

import com.pipemend.pipeline.persistence.IngestionBatchRepository;
import com.pipemend.pipeline.persistence.IngestionBatchRepository.ReceivedBatch;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Startup reconciliation (AC-04.6, ADR-0009). Nothing runs when the service starts, so every execution still marked
 * running was cut short by a stopped process; it is recovered through the JobOperator, never by editing BATCH_*.
 * A batch that never left RECEIVED ran no step and is queued again; a RUNNING one becomes FAILED with INTERRUPTED and
 * waits for FR-21.
 */
@Component
public class StartupReconciliation implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StartupReconciliation.class);

    private final JobRepository jobRepository;
    private final JobOperator jobOperator;
    private final Job ingestionJob;
    private final IngestionBatchRepository batches;
    private volatile boolean running;

    public StartupReconciliation(
            JobRepository jobRepository, JobOperator jobOperator, Job ingestionJob, IngestionBatchRepository batches) {
        this.jobRepository = jobRepository;
        this.jobOperator = jobOperator;
        this.ingestionJob = ingestionJob;
        this.batches = batches;
    }

    // Before the web server starts (phase Integer.MAX_VALUE - 2048): an upload accepted earlier would leave a queued
    // execution that this reconciliation would take for one cut short. Lifecycle phases also run after the job
    // registry, which restart needs, is filled in afterSingletonsInstantiated.
    @Override
    public int getPhase() {
        return 0;
    }

    @Override
    public void start() {
        reconcile();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Only the two initial queries stop the startup, since they mean the database is down. A batch that cannot be
     * reconciled becomes FAILED with INFRASTRUCTURE_ERROR so that one batch cannot block every startup (D93).
     */
    void reconcile() {
        Set<UUID> withExecution = new HashSet<>();
        for (JobExecution execution : jobRepository.findRunningJobExecutions(IngestionJobConfiguration.JOB_NAME)) {
            UUID batchId = readBatchId(execution);
            if (batchId != null) {
                withExecution.add(batchId);
            }
            try {
                // recover needs no batch: nothing runs at startup, so the execution cannot be alive.
                JobExecution recovered = jobOperator.recover(execution);
                if (batchId == null) {
                    log.error(
                            "Execution {} has no readable batchId; recovered it with no batch to mark",
                            execution.getId());
                    continue;
                }
                String status = batches.findStatus(batchId).orElse(null);
                if (status == null) {
                    log.error(
                            "Execution {} belongs to batch {}, which has no row; recovered it",
                            execution.getId(),
                            batchId);
                } else if (status.equals("RECEIVED")) {
                    // markRunning happens in beforeJob, before any step, so a RECEIVED batch has nothing to duplicate.
                    log.info("Batch {} was queued when the service stopped; queuing it again", batchId);
                    jobOperator.restart(recovered);
                } else if (batches.interrupt(batchId)) {
                    log.warn("Batch {} was interrupted while running and is now FAILED (INTERRUPTED)", batchId);
                }
            } catch (Exception e) {
                failBatch(batchId, "cannot reconcile execution " + execution.getId(), e);
            }
        }
        for (ReceivedBatch batch : batches.findReceived()) {
            if (!withExecution.contains(batch.id())) {
                log.info("Batch {} was registered but never launched; launching it", batch.id());
                try {
                    jobOperator.start(ingestionJob, IngestionJobConfiguration.parameters(batch.id(), batch.checksum()));
                } catch (Exception e) {
                    failBatch(batch.id(), "cannot launch the batch", e);
                }
            }
        }
        log.info("Startup reconciliation finished");
    }

    // The batch stays FAILED and visible, and the cause is logged: not an error turned into a success.
    private void failBatch(@Nullable UUID batchId, String what, Exception cause) {
        log.error("Startup reconciliation of batch {}: {}", batchId, what, cause);
        if (batchId != null && batches.failUnfinished(batchId, "INFRASTRUCTURE_ERROR")) {
            log.error("Batch {} is now FAILED (INFRASTRUCTURE_ERROR)", batchId);
        }
    }

    private static @Nullable UUID readBatchId(JobExecution execution) {
        try {
            return IngestionJobConfiguration.batchId(execution);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
