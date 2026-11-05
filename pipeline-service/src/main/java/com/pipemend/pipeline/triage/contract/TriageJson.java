package com.pipemend.pipeline.triage.contract;

import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * Reads the answer of ai-service, failing closed (docs/06, docs/09 7.5). Not a bean: the JsonMapper of Boot accepts
 * "severity": 0 as LOW and "confidence": "0.97" as a number, and the rest of the application keeps that one.
 */
public final class TriageJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .withCoercionConfig(LogicalType.Textual, config -> {
                for (CoercionInputShape shape :
                        List.of(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean)) {
                    config.setCoercion(shape, CoercionAction.Fail);
                }
            })
            .withCoercionConfig(LogicalType.Float, config -> {
                config.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            })
            .withCoercionConfig(LogicalType.Integer, config -> {
                config.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
            })
            .withCoercionConfig(LogicalType.Boolean, config -> {
                config.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
            })
            .build();

    private TriageJson() {}

    public static TriageResponse parse(@Nullable String body) {
        if (body == null) {
            throw new InvalidTriageJsonException();
        }
        TriageResponse response;
        try {
            response = MAPPER.readValue(body, TriageResponse.class);
        } catch (JacksonException e) {
            throw new InvalidTriageJsonException();
        }
        // A literal null is valid JSON, and Jackson answers it with null.
        if (response == null) {
            throw new InvalidTriageJsonException();
        }
        return response;
    }

    /** No message or cause from Jackson, which can quote what was received; the caller has the whole body. */
    public static final class InvalidTriageJsonException extends RuntimeException {

        private InvalidTriageJsonException() {
            super("The response of ai-service is not valid against the contract");
        }
    }
}
