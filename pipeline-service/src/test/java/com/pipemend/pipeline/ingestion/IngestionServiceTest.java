package com.pipemend.pipeline.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pipemend.pipeline.batch.Inbox;
import com.pipemend.pipeline.persistence.IngestionBatchRepository;
import com.pipemend.pipeline.schema.SchemaTestSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.TransientDataAccessResourceException;

class IngestionServiceTest {

    private static final byte[] CONTENT = "Invoice,StockCode\n536365,85123A\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path inboxDir;

    private final IngestionBatchRepository batches = mock(IngestionBatchRepository.class);
    private final JobOperator jobOperator = mock(JobOperator.class);
    private IngestionService service;

    @BeforeEach
    void setUp() {
        when(batches.insert(any(), anyString(), anyString(), anyLong(), anyString(), any(Integer.class)))
                .thenReturn(OffsetDateTime.now());
        service = new IngestionService(
                new Inbox(inboxDir), batches, SchemaTestSupport.realSchema(), jobOperator, mock(Job.class));
    }

    @Test
    void aJobThatCannotBeLaunchedRemovesItsBatchAndFile() throws Exception {
        when(jobOperator.start(any(Job.class), any(JobParameters.class)))
                .thenThrow(new IllegalStateException("executor down"));
        when(batches.deleteIfReceived(any())).thenReturn(true);

        assertThatThrownBy(() -> service.ingest("data.csv", new ByteArrayResource(CONTENT)))
                .isInstanceOf(IllegalStateException.class);

        verify(batches).deleteIfReceived(any(UUID.class));
        verify(batches, never()).fail(any(), anyString());
        assertThat(inboxEntries()).isZero();
    }

    @Test
    void aFailureAfterASuccessfulLaunchNeverRemovesTheFileTheJobIsReading() throws Exception {
        when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(mock(JobExecution.class));
        when(batches.status(any())).thenThrow(new TransientDataAccessResourceException("connection lost"));

        assertThatThrownBy(() -> service.ingest("data.csv", new ByteArrayResource(CONTENT)))
                .isInstanceOf(TransientDataAccessResourceException.class);

        verify(batches, never()).deleteIfReceived(any());
        assertThat(inboxEntries()).isEqualTo(1);
    }

    @Test
    void theStoredNameIsOnlyTheLastSegmentWithoutControlCharacters() {
        assertThat(IngestionService.sanitize("../../evil.csv")).isEqualTo("evil.csv");
        assertThat(IngestionService.sanitize("C:\\Users\\x\\data.csv")).isEqualTo("data.csv");
        assertThat(IngestionService.sanitize("da\nta\u0000.csv")).isEqualTo("data.csv");
    }

    private long inboxEntries() throws IOException {
        try (Stream<Path> entries = Files.list(inboxDir)) {
            return entries.count();
        }
    }
}
