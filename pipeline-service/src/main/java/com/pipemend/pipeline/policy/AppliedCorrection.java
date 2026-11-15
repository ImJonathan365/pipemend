package com.pipemend.pipeline.policy;

import com.pipemend.pipeline.triage.contract.Operation;
import com.pipemend.pipeline.triage.contract.Severity;
import com.pipemend.pipeline.validation.ErrorCode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One corrected field, with everything a correction_log row needs (AC-10.3, V2).
 *
 * @param correctedValue null only for NULLIFY_TOKEN
 * @param confidence rounded half up to the three decimals of correction_log.confidence
 */
public record AppliedCorrection(
        String fieldName,
        ErrorCode errorCode,
        Operation operation,
        Map<String, @Nullable Object> operationParams,
        @Nullable String originalValue,
        @Nullable String correctedValue,
        BigDecimal confidence,
        Severity severity,
        String aiExplanation,
        String aiProvider,
        String aiModel,
        String promptVersion,
        UUID aiRequestId) {}
