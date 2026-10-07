package com.pipemend.pipeline.validation;

import java.util.Map;

/**
 * One rule broken by one field, or by the record when {@code field} is {@link Validator#RECORD}. {@code expected}
 * uses the keys of docs/06 A.1.
 */
public record Violation(
        String field,
        String sourceHeader,
        ErrorCode errorCode,
        String receivedValue,
        Map<String, Object> expected,
        String technicalMessage,
        String exceptionType) {}
