package com.pipemend.pipeline.triage;

import com.pipemend.pipeline.persistence.AiCallLogRepository;
import com.pipemend.pipeline.persistence.AiCallLogRepository.Outcome;
import com.pipemend.pipeline.triage.contract.TriageJson;
import com.pipemend.pipeline.triage.contract.TriageRequest;
import com.pipemend.pipeline.triage.contract.TriageResponse;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /v1/triage (FR-08) and the docs/06 A.5 table that turns each answer into a {@link Reply}. Every call, also a
 * failed one, leaves one row in ai_call_log (AC-08.5). Retries and the circuit breaker come in Sprint 4 (docs/07), so
 * attempts is always 1. The body of a response never goes to the logs: it carries values of the row.
 */
public final class HttpAiTriageClient {

    static final String TRIAGE_PATH = "/v1/triage";
    static final String REQUEST_ID_HEADER = "X-Request-Id";
    static final String BATCH_ID_HEADER = "X-Batch-Id";

    private static final Logger log = LoggerFactory.getLogger(HttpAiTriageClient.class);
    private static final JsonMapper PROBLEM_READER = JsonMapper.builder().build();
    // Only these go into a detail or a log line; any other code goes to error_code alone.
    private static final Set<String> KNOWN_CODES = Set.of(
            "INVALID_REQUEST",
            "NOT_FOUND",
            "METHOD_NOT_ALLOWED",
            "VALIDATION_ERROR",
            "RATE_LIMITED",
            "LLM_BUDGET_EXHAUSTED",
            "INTERNAL_ERROR",
            "LLM_INVALID_OUTPUT",
            "LLM_PROVIDER_ERROR",
            "PROVIDER_NOT_CONFIGURED",
            "LLM_TIMEOUT");

    /** What the pipeline does with the record follows from the variant (docs/06 A.5). */
    public sealed interface Reply {

        /** @param body as received, for ai_analysis */
        record Analysed(TriageResponse response, String body, TriageResponseValidator.Result.Valid verdict)
                implements Reply {}

        /** AI_UNAVAILABLE */
        record Unavailable(String detail) implements Reply {}

        /** AI_INVALID_RESPONSE */
        record InvalidResponse(String detail) implements Reply {}

        /** AI_BUDGET_EXCEEDED */
        record BudgetExhausted(String detail) implements Reply {}
    }

    private enum Kind {
        UNAVAILABLE,
        INVALID,
        BUDGET
    }

    private record Received(int status, @Nullable MediaType contentType, String body) {}

    private final RestClient rest;
    private final JsonMapper json;
    private final AiCallLogRepository callLog;

    /** @param json the JsonMapper of Boot, the one the contract tests check the request against */
    public HttpAiTriageClient(AiClientProperties properties, JsonMapper json, AiCallLogRepository callLog) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        this.rest = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(factory)
                .build();
        this.json = json;
        this.callLog = callLog;
    }

    public Reply call(TriageRequest request) {
        long start = System.nanoTime();
        Received received;
        try {
            received = rest.post()
                    .uri(TRIAGE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(REQUEST_ID_HEADER, request.requestId().toString())
                    .header(BATCH_ID_HEADER, request.batchId().toString())
                    .body(json.writeValueAsBytes(request))
                    .exchange((sent, response) -> new Received(
                            response.getStatusCode().value(),
                            contentType(response.getHeaders()),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (ResourceAccessException e) {
            return transportFailure(request, e, elapsedMs(start));
        }
        int latencyMs = elapsedMs(start);
        if (received.status() == 200) {
            return answer(request, received, latencyMs);
        }
        return httpFailure(request, received, latencyMs);
    }

    private Reply answer(TriageRequest request, Received received, int latencyMs) {
        TriageResponse response;
        try {
            response = TriageJson.parse(received.body());
        } catch (TriageJson.InvalidTriageJsonException e) {
            return invalidAnswer(request, received, latencyMs, "is not valid against the contract");
        }
        // ai_call_log.llm_calls is a smallint; ai-service never makes more than 3 (NFR-07).
        if (response.meta().llmCalls() > Short.MAX_VALUE) {
            return invalidAnswer(request, received, latencyMs, "has llmCalls out of range");
        }
        return switch (TriageResponseValidator.validate(request, response)) {
            case TriageResponseValidator.Result.Valid valid -> {
                Outcome outcome = response.meta().cacheHit() ? Outcome.CACHE_HIT : Outcome.SUCCESS;
                save(request, outcome, received.status(), null, latencyMs, null, response.meta());
                yield new Reply.Analysed(response, received.body(), valid);
            }
            case TriageResponseValidator.Result.Invalid invalid -> {
                log.warn("ai-service answer {} failed the semantic check: {}", request.requestId(), invalid.issues());
                yield invalidAnswer(request, received, latencyMs, "failed the semantic check " + invalid.issues());
            }
        };
    }

    private Reply invalidAnswer(TriageRequest request, Received received, int latencyMs, String why) {
        save(request, Outcome.INVALID_RESPONSE, received.status(), null, latencyMs, nonEmpty(received.body()), null);
        return new Reply.InvalidResponse("The answer of ai-service " + why);
    }

    private Reply httpFailure(TriageRequest request, Received received, int latencyMs) {
        int status = received.status();
        String code = problemCode(received);
        Kind kind;
        Outcome outcome = Outcome.HTTP_ERROR;
        if (status >= 500 && code == null) {
            // A 5xx that is not a problem+json of ai-service comes from the transport (docs/06 A.5).
            kind = Kind.UNAVAILABLE;
            outcome = Outcome.UNAVAILABLE;
        } else if (status >= 500) {
            kind = status == 502 && code.equals("LLM_INVALID_OUTPUT") ? Kind.INVALID : Kind.UNAVAILABLE;
        } else if (status == 429) {
            kind = "LLM_BUDGET_EXHAUSTED".equals(code) ? Kind.BUDGET : Kind.UNAVAILABLE;
        } else {
            // 400, 404, 405, 422 and any other status outside the table: a contract bug, not an outage.
            kind = Kind.INVALID;
        }
        save(request, outcome, status, code, latencyMs, nonEmpty(received.body()), null);

        String detail =
                "ai-service answered HTTP " + status + (code != null && KNOWN_CODES.contains(code) ? " " + code : "");
        return switch (kind) {
            case UNAVAILABLE -> new Reply.Unavailable(detail);
            case BUDGET -> new Reply.BudgetExhausted(detail);
            case INVALID -> {
                if (status < 500) {
                    log.error("ai-service rejected request {}: {}", request.requestId(), detail);
                }
                yield new Reply.InvalidResponse(detail);
            }
        };
    }

    private Reply transportFailure(TriageRequest request, ResourceAccessException e, int latencyMs) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        // HttpConnectTimeoutException is a subclass, so this covers the connect and the read timeout.
        boolean timeout = cause instanceof HttpTimeoutException;
        String type = cause.getClass().getName();
        save(request, timeout ? Outcome.TIMEOUT : Outcome.UNAVAILABLE, null, null, latencyMs, type, null);
        return new Reply.Unavailable(
                (timeout ? "ai-service did not answer in time: " : "ai-service is unreachable: ") + type);
    }

    /** @param meta only from an answer that passed the validator */
    private void save(
            TriageRequest request,
            Outcome outcome,
            @Nullable Integer httpStatus,
            @Nullable String errorCode,
            int latencyMs,
            @Nullable String errorDetail,
            TriageResponse.@Nullable Meta meta) {
        callLog.save(new AiCallLogRepository.Entry(
                request.requestId(),
                request.batchId(),
                request.record().sourceRowNumber(),
                outcome,
                httpStatus,
                errorCode,
                1,
                meta == null ? null : meta.llmCalls(),
                latencyMs,
                meta == null ? null : meta.provider(),
                meta == null ? null : meta.model(),
                meta == null ? null : meta.promptVersion(),
                meta == null ? null : meta.inputTokens(),
                meta == null ? null : meta.outputTokens(),
                errorDetail));
    }

    /** The code of a problem+json, or null when the body is not one (docs/06 A.5). */
    private static @Nullable String problemCode(Received received) {
        MediaType type = received.contentType();
        if (type == null || !MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(type)) {
            return null;
        }
        try {
            JsonNode code = PROBLEM_READER.readTree(received.body()).get("code");
            return code != null && code.isString() ? code.asString() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static @Nullable MediaType contentType(HttpHeaders headers) {
        try {
            return headers.getContentType();
        } catch (InvalidMediaTypeException e) {
            return null;
        }
    }

    private static @Nullable String nonEmpty(String body) {
        return body.isEmpty() ? null : body;
    }

    private static int elapsedMs(long start) {
        return (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
