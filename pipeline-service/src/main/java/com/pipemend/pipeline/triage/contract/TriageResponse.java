package com.pipemend.pipeline.triage.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The response of POST /v1/triage (docs/06 A.1). Every key is required, also the ones that admit null: required=true
 * rejects an absent key but lets an explicit null through, so the constructors reject it where the spec does not allow
 * it (docs/04 section 6.1).
 */
public record TriageResponse(
        @JsonProperty(required = true) UUID requestId,
        @JsonProperty(required = true) Classification recordDecision,
        @JsonProperty(required = true) Severity maxSeverity,
        @JsonProperty(required = true) String summary,
        @JsonProperty(required = true) List<Analysis> analyses,
        @JsonProperty(required = true) Meta meta) {

    public TriageResponse {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(recordDecision);
        Objects.requireNonNull(maxSeverity);
        Objects.requireNonNull(summary);
        analyses.forEach(Objects::requireNonNull);
        Objects.requireNonNull(meta);
    }

    public record Analysis(
            @JsonProperty(required = true) String violationId,
            @JsonProperty(required = true) String field,
            @JsonProperty(required = true) RootCause rootCause,
            @JsonProperty(required = true) String explanation,
            @JsonProperty(required = true) Severity severity,
            @JsonProperty(required = true) Classification classification,
            @JsonProperty(required = true) double confidence,
            @JsonProperty(required = true) @Nullable Correction correction,
            @JsonProperty(required = true) @Nullable String suggestedHumanAction) {

        public Analysis {
            Objects.requireNonNull(violationId);
            Objects.requireNonNull(field);
            Objects.requireNonNull(rootCause);
            Objects.requireNonNull(explanation);
            Objects.requireNonNull(severity);
            Objects.requireNonNull(classification);
        }
    }

    public record Correction(
            @JsonProperty(required = true) Operation operation,
            @JsonProperty(required = true) @Nullable String originalValue,
            @JsonProperty(required = true) @Nullable String proposedValue,
            @JsonProperty(required = true) Map<String, @Nullable Object> parameters) {

        public Correction {
            Objects.requireNonNull(operation);
            Objects.requireNonNull(parameters);
        }
    }

    public record Meta(
            @JsonProperty(required = true) String provider,
            @JsonProperty(required = true) String model,
            @JsonProperty(required = true) String promptVersion,
            @JsonProperty(required = true) int latencyMs,
            @JsonProperty(required = true) boolean cacheHit,
            @JsonProperty(required = true) @Nullable Integer inputTokens,
            @JsonProperty(required = true) @Nullable Integer outputTokens,
            @JsonProperty(required = true) int llmCalls) {

        public Meta {
            Objects.requireNonNull(provider);
            Objects.requireNonNull(model);
            Objects.requireNonNull(promptVersion);
        }
    }
}
