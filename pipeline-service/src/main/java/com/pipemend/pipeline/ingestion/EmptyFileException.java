package com.pipemend.pipeline.ingestion;

/** The file has no bytes, or a header and no record (AC-01.3): 400 EMPTY_FILE, and no batch is created. */
public class EmptyFileException extends RuntimeException {

    public EmptyFileException() {
        super("The file is empty or has a header and no records.");
    }
}
