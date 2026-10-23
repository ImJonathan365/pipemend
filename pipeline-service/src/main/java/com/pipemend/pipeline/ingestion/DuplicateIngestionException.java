package com.pipemend.pipeline.ingestion;

import java.util.UUID;

/** Another batch not REJECTED already holds the same checksum (AC-04.2): 409 DUPLICATE_INGESTION. */
public class DuplicateIngestionException extends RuntimeException {

    private final UUID existingBatchId;
    private final String existingStatus;

    public DuplicateIngestionException(UUID existingBatchId, String existingStatus) {
        super(
                "FAILED".equals(existingStatus)
                        // AC-04.3: re-ingesting a failed batch could duplicate the chunks it already committed.
                        ? "A file with the same content was already ingested and its batch failed; ingesting it again as"
                                + " a new batch could duplicate data."
                        : "A file with the same content was already ingested.");
        this.existingBatchId = existingBatchId;
        this.existingStatus = existingStatus;
    }

    public UUID existingBatchId() {
        return existingBatchId;
    }

    public String existingStatus() {
        return existingStatus;
    }
}
