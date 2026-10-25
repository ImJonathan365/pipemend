package com.pipemend.pipeline.ingestion;

/** A query parameter out of its documented range (docs/06 B): 400 INVALID_PARAMETER. */
public class InvalidQueryParameterException extends RuntimeException {

    public InvalidQueryParameterException(String message) {
        super(message);
    }
}
