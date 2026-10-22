package com.pipemend.pipeline.batch;

import com.pipemend.pipeline.persistence.IngestionBatchRepository;
import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * Counts the records with the same reader that will process them, before any is processed, so read_count is a measure
 * independent of what gets written (NFR-06). A file that cannot be read as records ends REJECTED with
 * INVALID_FILE_FORMAT here, with no row loaded or quarantined.
 */
public class IntakeTasklet implements Tasklet {

    private final IngestionBatchRepository batches;
    private final Inbox inbox;

    public IntakeTasklet(IngestionBatchRepository batches, Inbox inbox) {
        this.batches = batches;
        this.inbox = inbox;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        UUID batchId = IngestionJobConfiguration.batchId(contribution.getStepExecution());
        RawRecordReader reader = new RawRecordReader(inbox.source(batchId));
        int count = 0;
        try {
            reader.open(new ExecutionContext());
            while (reader.read() != null) {
                count++;
            }
        } catch (InvalidFileFormatException e) {
            batches.reject(batchId, "INVALID_FILE_FORMAT", null);
            contribution.setExitStatus(IngestionJobConfiguration.REJECTED);
            return RepeatStatus.FINISHED;
        } finally {
            reader.close();
        }
        batches.saveReadCount(batchId, count);
        return RepeatStatus.FINISHED;
    }
}
