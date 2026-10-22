package com.pipemend.pipeline.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pipemend.pipeline.batch.Inbox;
import com.pipemend.pipeline.persistence.IngestionBatchRepository;
import com.pipemend.pipeline.schema.SchemaTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.core.io.ByteArrayResource;

class IngestionServiceTest {

    @TempDir
    Path inboxDir;

    @Test
    void aJobThatCannotBeLaunchedLeavesTheBatchFailedAndNoFile() throws Exception {
        IngestionBatchRepository batches = mock(IngestionBatchRepository.class);
        when(batches.insert(any(), anyString(), anyString(), anyLong(), anyString(), any(Integer.class)))
                .thenReturn(OffsetDateTime.now());
        JobOperator jobOperator = mock(JobOperator.class);
        when(jobOperator.start(any(Job.class), any(JobParameters.class)))
                .thenThrow(new IllegalStateException("executor down"));
        IngestionService service = new IngestionService(
                new Inbox(inboxDir), batches, SchemaTestSupport.realSchema(), jobOperator, mock(Job.class));
        byte[] content = ("Invoice,StockCode\n536365,85123A\n").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.ingest("data.csv", new ByteArrayResource(content)))
                .isInstanceOf(IllegalStateException.class);

        verify(batches).fail(any(UUID.class), org.mockito.ArgumentMatchers.eq("INFRASTRUCTURE_ERROR"));
        try (Stream<Path> entries = Files.list(inboxDir)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void theStoredNameIsOnlyTheLastSegmentWithoutControlCharacters() {
        assertThat(IngestionService.sanitize("../../evil.csv")).isEqualTo("evil.csv");
        assertThat(IngestionService.sanitize("C:\\Users\\x\\data.csv")).isEqualTo("data.csv");
        assertThat(IngestionService.sanitize("da\nta\u0000.csv")).isEqualTo("data.csv");
    }
}
