package com.pipemend.pipeline.ingestion;

import java.util.UUID;

/** No batch with the requested id (AC-14.4): 404 BATCH_NOT_FOUND. */
public class BatchNotFoundException extends RuntimeException {

    public BatchNotFoundException(UUID batchId) {
        super("No batch with id " + batchId + ".");
    }
}
