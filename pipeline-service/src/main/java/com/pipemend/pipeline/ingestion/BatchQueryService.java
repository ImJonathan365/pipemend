package com.pipemend.pipeline.ingestion;

import com.pipemend.pipeline.persistence.IngestionBatchRepository;
import com.pipemend.pipeline.persistence.IngestionBatchRepository.BatchView;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Batch status for the API (FR-14); ingestion_batch is the source of truth (docs/02 section 5). */
@Service
public class BatchQueryService {

    private static final Set<String> STATUSES = Set.of("RECEIVED", "RUNNING", "COMPLETED", "FAILED", "REJECTED");
    private static final int MAX_SIZE = 200;

    private final IngestionBatchRepository batches;
    private final JsonMapper json;

    public BatchQueryService(IngestionBatchRepository batches, JsonMapper json) {
        this.batches = batches;
        this.json = json;
    }

    public BatchResponse find(UUID batchId) {
        return batches.find(batchId).map(this::response).orElseThrow(() -> new BatchNotFoundException(batchId));
    }

    public PageResponse<BatchResponse> list(@Nullable String status, int page, int size) {
        if (status != null && !STATUSES.contains(status)) {
            throw new InvalidQueryParameterException("The parameter 'status' must be one of " + STATUSES + ".");
        }
        if (page < 0) {
            throw new InvalidQueryParameterException("The parameter 'page' must be 0 or greater.");
        }
        // docs/06 B: above the maximum is an error, never a silently smaller page.
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidQueryParameterException("The parameter 'size' must be between 1 and " + MAX_SIZE + ".");
        }
        long total = batches.count(status);
        List<BatchResponse> content =
                batches.page(status, page, size).stream().map(this::response).toList();
        return new PageResponse<>(content, page, size, total, (total + size - 1) / size);
    }

    private BatchResponse response(BatchView batch) {
        Long durationMs = batch.startedAt() == null || batch.finishedAt() == null
                ? null
                : Duration.between(batch.startedAt(), batch.finishedAt()).toMillis();
        return new BatchResponse(
                batch.id(),
                batch.status(),
                batch.sourceFilename(),
                batch.sourceType(),
                batch.sourceChecksum(),
                new BatchResponse.Schema(batch.schemaName(), batch.schemaVersion()),
                tree(batch.schemaMappingJson()),
                new BatchResponse.Counts(batch.read(), batch.direct(), batch.autoCorrected(), batch.quarantined()),
                batch.aiCalls(),
                batch.failureReason(),
                tree(batch.schemaDriftReportJson()),
                batch.receivedAt(),
                batch.startedAt(),
                batch.finishedAt(),
                durationMs);
    }

    private @Nullable JsonNode tree(@Nullable String value) {
        return value == null ? null : json.readTree(value);
    }
}
