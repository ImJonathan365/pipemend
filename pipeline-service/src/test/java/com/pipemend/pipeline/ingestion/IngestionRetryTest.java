package com.pipemend.pipeline.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pipemend.pipeline.PostgresTestConfiguration;
import com.pipemend.pipeline.batch.Inbox;
import com.pipemend.pipeline.persistence.IngestionBatchRepository;
import com.pipemend.pipeline.schema.SchemaDefinition;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;

/** A launch failure against the real database: the checksum must stay free for the next upload (D88). */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class IngestionRetryTest {

    @Autowired
    IngestionService ingestions;

    @Autowired
    Inbox inbox;

    @Autowired
    IngestionBatchRepository batches;

    @Autowired
    SchemaDefinition schema;

    @Autowired
    Job ingestionJob;

    @Test
    void afterALaunchFailureTheSameFileCanBeUploadedAgain() throws Exception {
        byte[] content = ("Invoice,StockCode,Description,Quantity,InvoiceDate,Price,Customer ID,Country\n"
                        + "536365,85123A,ITEM " + UUID.randomUUID()
                        + ",6,2010-12-01 08:26:00,2.55,17850,United Kingdom\n")
                .getBytes(StandardCharsets.UTF_8);
        JobOperator broken = mock(JobOperator.class);
        when(broken.start(any(Job.class), any(JobParameters.class))).thenThrow(new IllegalStateException("down"));
        IngestionService failing = new IngestionService(inbox, batches, schema, broken, ingestionJob);

        assertThatThrownBy(() -> failing.ingest("data.csv", new ByteArrayResource(content)))
                .isInstanceOf(IllegalStateException.class);

        IngestionResponse retried = ingestions.ingest("data.csv", new ByteArrayResource(content));
        assertThat(retried.status()).isIn("RECEIVED", "RUNNING");
    }
}
