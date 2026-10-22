package com.pipemend.pipeline.batch;

import com.pipemend.pipeline.persistence.IngestionBatchRepository;
import com.pipemend.pipeline.persistence.ProcessedRecordWriter;
import com.pipemend.pipeline.persistence.QuarantineWriter;
import com.pipemend.pipeline.persistence.SalesTransactionWriter;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.transform.SalesTransactionTransformer;
import com.pipemend.pipeline.triage.DisabledAiTriagePort;
import com.pipemend.pipeline.validation.Validator;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.batch.autoconfigure.BatchTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ingestion job of docs/04 section 3: intake, schema check, record processing and finalize. A REJECTED batch
 * ends the job as COMPLETED with exit status REJECTED; any failed step fails the job (docs/02 section 5).
 */
@Configuration(proxyBeanMethods = false)
public class IngestionJobConfiguration {

    public static final String JOB_NAME = "ingestionJob";
    public static final String BATCH_ID = "batchId";
    public static final String SOURCE_CHECKSUM = "sourceChecksum";
    static final ExitStatus REJECTED = new ExitStatus("REJECTED");

    static UUID batchId(JobExecution execution) {
        return UUID.fromString(execution.getJobParameters().getString(BATCH_ID));
    }

    static UUID batchId(StepExecution execution) {
        return batchId(execution.getJobExecution());
    }

    /** One job at a time, the rest queued (NFR-10); the JobOperator picks it up through @BatchTaskExecutor. */
    @Bean
    @BatchTaskExecutor
    TaskExecutor batchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("ingestion-");
        return executor;
    }

    @Bean
    Job ingestionJob(
            JobRepository jobRepository,
            Step intakeStep,
            Step schemaCheckStep,
            Step processRecordsStep,
            Step finalizeStep,
            IngestionBatchRepository batches) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .listener(new BatchStatusListener(batches))
                .start(intakeStep)
                .on(REJECTED.getExitCode())
                .end(REJECTED.getExitCode())
                .from(intakeStep)
                .on(ExitStatus.COMPLETED.getExitCode())
                .to(schemaCheckStep)
                .from(intakeStep)
                .on("*")
                .fail()
                .from(schemaCheckStep)
                .on(REJECTED.getExitCode())
                .end(REJECTED.getExitCode())
                .from(schemaCheckStep)
                .on(ExitStatus.COMPLETED.getExitCode())
                .to(processRecordsStep)
                .from(schemaCheckStep)
                .on("*")
                .fail()
                .from(processRecordsStep)
                .on(ExitStatus.COMPLETED.getExitCode())
                .to(finalizeStep)
                .from(processRecordsStep)
                .on("*")
                .fail()
                .end()
                .build();
    }

    @Bean
    Step intakeStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            IngestionBatchRepository batches,
            Inbox inbox) {
        return new StepBuilder("intakeStep", jobRepository)
                .tasklet(new IntakeTasklet(batches, inbox), transactionManager)
                .build();
    }

    @Bean
    Step schemaCheckStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            IngestionBatchRepository batches,
            Inbox inbox,
            SchemaDefinition schema,
            JsonMapper json) {
        return new StepBuilder("schemaCheckStep", jobRepository)
                .tasklet(new SchemaCheckTasklet(batches, inbox, schema, json), transactionManager)
                .build();
    }

    @Bean
    Step processRecordsStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ItemStreamReader<RawRecord> rawRecordReader,
            ItemProcessor<RawRecord, ProcessedRecord> recordProcessor,
            ProcessedRecordWriter processedRecordWriter,
            @Value("${pipemend.chunk-size}") int chunkSize) {
        // No skip policy and no retry: an unexpected record becomes PROCESSING_ERROR in the processor, and any
        // other exception fails the chunk and the job (NFR-06).
        return new StepBuilder("processRecordsStep", jobRepository)
                .<RawRecord, ProcessedRecord>chunk(chunkSize)
                .transactionManager(transactionManager)
                .reader(rawRecordReader)
                .processor(recordProcessor)
                .writer(processedRecordWriter)
                .build();
    }

    @Bean
    Step finalizeStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            IngestionBatchRepository batches) {
        return new StepBuilder("finalizeStep", jobRepository)
                .tasklet(new FinalizeTasklet(batches), transactionManager)
                .build();
    }

    // Step-scoped beans are class proxies, so they are declared by interface: both implementations are final.
    @Bean
    @StepScope
    ItemStreamReader<RawRecord> rawRecordReader(Inbox inbox, @Value("#{jobParameters['batchId']}") String batchId) {
        return new RawRecordReader(inbox.source(UUID.fromString(batchId)));
    }

    @Bean
    @StepScope
    ItemProcessor<RawRecord, ProcessedRecord> recordProcessor(
            SchemaDefinition schema, Validator validator, @Value("#{jobParameters['batchId']}") String batchId) {
        return new RecordProcessor(
                UUID.fromString(batchId),
                schema,
                validator,
                new SalesTransactionTransformer(schema),
                new DisabledAiTriagePort());
    }

    @Bean
    ProcessedRecordWriter processedRecordWriter(DataSource dataSource, JsonMapper json) {
        return new ProcessedRecordWriter(
                new SalesTransactionWriter(dataSource), new QuarantineWriter(dataSource, json));
    }
}
