package com.pipemend.pipeline.triage.contract;

import com.pipemend.pipeline.validation.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The request of POST /v1/triage (docs/06 A.1). ai-service answers 422 to an unknown key or a missing one. */
public record TriageRequest(
        UUID requestId,
        UUID batchId,
        SchemaRef schema,
        Source source,
        Row record,
        List<Violation> violations,
        @Nullable Options options) {

    public record SchemaRef(String name, String version) {}

    public record Source(String filename, String type) {}

    public record Row(int sourceRowNumber, Map<String, @Nullable String> raw) {}

    /**
     * @param expected a map and not a record: an absent key and a null value differ, as in {@code receivedColumns}
     */
    public record Violation(
            String violationId,
            String field,
            @Nullable String sourceHeader,
            ErrorCode errorCode,
            @Nullable String receivedValue,
            Map<String, @Nullable Object> expected,
            String technicalMessage,
            @Nullable String exceptionType) {}

    public record Options(@Nullable String explanationLanguage) {}
}
