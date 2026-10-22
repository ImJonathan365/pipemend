package com.pipemend.pipeline.ingestion;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Body of the 202 of docs/06 B.1. {@code status} is RECEIVED while queued and RUNNING once the job started. */
public record IngestionResponse(
        UUID batchId,
        String status,
        String sourceFilename,
        String sourceType,
        String sourceChecksum,
        OffsetDateTime receivedAt) {}
