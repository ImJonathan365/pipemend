package com.pipemend.pipeline.ingestion;

import org.jspecify.annotations.Nullable;

/** The extension is not .csv (AC-01.2): 415 UNSUPPORTED_MEDIA_TYPE, and no batch is created. */
public class UnsupportedFileTypeException extends RuntimeException {

    public UnsupportedFileTypeException(@Nullable String filename) {
        super("Only .csv files are accepted; received '" + filename + "'.");
    }
}
