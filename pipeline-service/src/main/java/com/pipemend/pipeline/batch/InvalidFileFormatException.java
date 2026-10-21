package com.pipemend.pipeline.batch;

/** The file cannot be read as records at all; the batch ends REJECTED with INVALID_FILE_FORMAT (docs/02 section 5). */
public class InvalidFileFormatException extends RuntimeException {

    public InvalidFileFormatException(String message) {
        super(message);
    }

    public InvalidFileFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
