package com.pipemend.pipeline.ingestion;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** A batch as docs/06 B.2 describes it, also each element of the paginated list. */
public record BatchResponse(
        UUID batchId,
        String status,
        String sourceFilename,
        String sourceType,
        String sourceChecksum,
        Schema schema,
        @Nullable JsonNode schemaMapping,
        Counts counts,
        int aiCalls,
        @Nullable String failureReason,
        @Nullable JsonNode schemaDriftReport,
        OffsetDateTime receivedAt,
        @Nullable OffsetDateTime startedAt,
        @Nullable OffsetDateTime finishedAt,
        @Nullable Long durationMs) {

    public record Schema(String name, String version) {}

    public record Counts(int read, int direct, int autoCorrected, int quarantined) {}
}
