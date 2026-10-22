package com.pipemend.pipeline.batch;

/** The NFR-06 check failed, which means a bug: the batch ends FAILED with INVARIANT_VIOLATION. */
public class InvariantViolationException extends RuntimeException {

    public InvariantViolationException(String message) {
        super(message);
    }
}
