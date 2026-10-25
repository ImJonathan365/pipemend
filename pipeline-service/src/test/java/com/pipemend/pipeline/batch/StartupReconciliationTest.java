package com.pipemend.pipeline.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.pipemend.pipeline.PipelineServiceApplication;
import com.pipemend.pipeline.PostgresTestConfiguration;
import com.pipemend.pipeline.persistence.IngestionBatchRepository;
import com.pipemend.pipeline.persistence.IngestionBatchRepository.Counts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * AC-04.6 after a real stop: the state a killed process leaves is seeded in PostgreSQL through the batch and job
 * repositories, then a new application context starts against the same database and inbox.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class StartupReconciliationTest {

    @Autowired
    JobRepository jobRepository;

    @Autowired
    IngestionBatchRepository batches;

    @Autowired
    Inbox inbox;

    @Autowired
    JdbcConnectionDetails database;

    @Autowired
    Environment environment;

    @Autowired
    JdbcClient jdbc;

    @Test
    void ac_04_6_aRestartReconcilesTheBatchesAStoppedProcessLeftBehind() throws Exception {
        // A: died in the middle of processRecordsStep.
        UUID running = receive();
        JobExecution runningExecution = execution(running, BatchStatus.STARTED);
        batches.markRunning(running, runningExecution.getId());
        step(runningExecution, "intakeStep", BatchStatus.COMPLETED);
        step(runningExecution, "processRecordsStep", BatchStatus.STARTED);
        // B: queued in the executor, never picked up.
        UUID queued = receive();
        execution(queued, BatchStatus.STARTING);
        // C: registered, then the process died before JobOperator.start.
        UUID unlaunched = receive();
        // D: finished, then the process died before Spring Batch stored the end of the execution.
        UUID finished = receive();
        JobExecution finishedExecution = execution(finished, BatchStatus.STARTED);
        batches.markRunning(finished, finishedExecution.getId());
        batches.complete(finished, new Counts(1, 1, 0, 0, 0, 1, 1));

        try (ConfigurableApplicationContext restarted = startService()) {
            assertThat(batches.findStatus(running)).hasValue("FAILED");
            assertThat(failureReason(running)).isEqualTo("INTERRUPTED");
            assertThat(executions(running)).singleElement().satisfies(e -> {
                assertThat(e.getStatus()).isEqualTo(BatchStatus.FAILED);
                assertThat(e.getStepExecutions()).allMatch(s -> !s.getStatus().isRunning());
            });
            assertThat(Files.exists(inbox.directory(running).resolve(Inbox.SOURCE_FILE)))
                    .isTrue();

            assertThat(batches.findStatus(finished)).hasValue("COMPLETED");
            assertThat(executions(finished))
                    .singleElement()
                    .extracting(JobExecution::getStatus)
                    .isEqualTo(BatchStatus.FAILED);

            await().atMost(Duration.ofMinutes(2))
                    .until(() -> List.of(batches.status(queued), batches.status(unlaunched))
                            .equals(List.of("COMPLETED", "COMPLETED")));
            assertThat(executions(queued))
                    .extracting(JobExecution::getStatus)
                    .containsExactlyInAnyOrder(BatchStatus.FAILED, BatchStatus.COMPLETED);
            assertThat(executions(unlaunched))
                    .singleElement()
                    .extracting(JobExecution::getStatus)
                    .isEqualTo(BatchStatus.COMPLETED);
        }

        try (ConfigurableApplicationContext again = startService()) {
            assertThat(batches.findStatus(running)).hasValue("FAILED");
            assertThat(batches.findStatus(finished)).hasValue("COMPLETED");
            assertThat(executions(running)).hasSize(1);
            assertThat(executions(queued)).hasSize(2);
            assertThat(executions(unlaunched)).hasSize(1);
            assertThat(executions(finished)).hasSize(1);
        }
    }

    @Test
    void ac_04_6_aBatchThatCannotBeReconciledFailsAloneAndTheStartupGoesOn() throws Exception {
        // Spring Batch itself refuses to run again an instance that already has a COMPLETED execution.
        UUID restartRefused = receive();
        execution(restartRefused, BatchStatus.COMPLETED);
        execution(restartRefused, BatchStatus.STARTING);
        UUID startRefused = receive();
        execution(startRefused, BatchStatus.COMPLETED);
        JobExecution withoutRow = execution(UUID.randomUUID(), BatchStatus.STARTING);
        JobExecution unreadable = execution(
                new JobParametersBuilder()
                        .addString(IngestionJobConfiguration.BATCH_ID, "not-a-uuid-" + UUID.randomUUID())
                        .toJobParameters(),
                BatchStatus.STARTED);
        UUID healthy = receive();

        try (ConfigurableApplicationContext restarted = startService()) {
            assertThat(List.of(restartRefused, startRefused)).allSatisfy(batchId -> {
                assertThat(batches.findStatus(batchId)).hasValue("FAILED");
                assertThat(failureReason(batchId)).isEqualTo("INFRASTRUCTURE_ERROR");
            });
            assertThat(List.of(withoutRow, unreadable))
                    .extracting(e -> jobRepository.getJobExecution(e.getId()).getStatus())
                    .containsOnly(BatchStatus.FAILED);
            await().atMost(Duration.ofMinutes(2))
                    .until(() -> batches.status(healthy).equals("COMPLETED"));
        }
    }

    private ConfigurableApplicationContext startService() {
        // Command-line arguments: builder properties are defaults and application.yml would override the inbox.
        return new SpringApplicationBuilder(PipelineServiceApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + database.getJdbcUrl(),
                        "--spring.datasource.username=" + database.getUsername(),
                        "--spring.datasource.password=" + database.getPassword(),
                        "--pipemend.inbox-dir=" + environment.getRequiredProperty("pipemend.inbox-dir"));
    }

    private UUID receive() throws Exception {
        UUID batchId = UUID.randomUUID();
        byte[] content = ("Invoice,StockCode,Description,Quantity,InvoiceDate,Price,Customer ID,Country\n"
                        + "536365,85123A,ITEM " + batchId + ",6,2010-12-01 08:26:00,2.55,17850,United Kingdom\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(inbox.directory(batchId));
        Files.write(inbox.directory(batchId).resolve(Inbox.SOURCE_FILE), content);
        batches.insert(batchId, "source.csv", checksum(batchId), content.length, "sales_transaction", 1);
        return batchId;
    }

    private JobExecution execution(UUID batchId, BatchStatus status) {
        return execution(IngestionJobConfiguration.parameters(batchId, checksum(batchId)), status);
    }

    private JobExecution execution(JobParameters parameters, BatchStatus status) {
        JobInstance instance = jobRepository.getJobInstance(IngestionJobConfiguration.JOB_NAME, parameters);
        if (instance == null) {
            instance = jobRepository.createJobInstance(IngestionJobConfiguration.JOB_NAME, parameters);
        }
        JobExecution execution = jobRepository.createJobExecution(instance, parameters, new ExecutionContext());
        execution.setStatus(status);
        if (status != BatchStatus.STARTING) {
            execution.setStartTime(LocalDateTime.now());
        }
        if (status == BatchStatus.COMPLETED) {
            execution.setEndTime(LocalDateTime.now());
            execution.setExitStatus(ExitStatus.COMPLETED);
        }
        jobRepository.update(execution);
        return execution;
    }

    private void step(JobExecution execution, String name, BatchStatus status) {
        StepExecution step = jobRepository.createStepExecution(name, execution);
        step.setStatus(status);
        step.setStartTime(LocalDateTime.now());
        if (status == BatchStatus.COMPLETED) {
            step.setEndTime(LocalDateTime.now());
        }
        jobRepository.update(step);
    }

    private List<JobExecution> executions(UUID batchId) {
        JobInstance instance = jobRepository.getJobInstance(
                IngestionJobConfiguration.JOB_NAME, IngestionJobConfiguration.parameters(batchId, checksum(batchId)));
        return jobRepository.getJobExecutions(instance);
    }

    private String failureReason(UUID batchId) {
        return jdbc.sql("SELECT failure_reason FROM ingestion_batch WHERE id = :id")
                .param("id", batchId)
                .query(String.class)
                .single();
    }

    private static String checksum(UUID batchId) {
        return batchId.toString().replace("-", "").repeat(2);
    }
}
