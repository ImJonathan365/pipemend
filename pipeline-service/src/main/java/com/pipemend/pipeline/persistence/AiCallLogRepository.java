package com.pipemend.pipeline.persistence;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One row of ai_call_log per logical call to ai-service (docs/05 section 3.5, AC-08.5). Written in its own transaction
 * so the row survives a rollback of the chunk (NFR-05). A failed write is not caught here: it reaches the caller.
 */
@Repository
public class AiCallLogRepository {

    static final int ERROR_DETAIL_MAX_BYTES = 4096;

    /** The values of the outcome CHECK that this code emits; CIRCUIT_OPEN comes with the circuit breaker. */
    public enum Outcome {
        SUCCESS,
        CACHE_HIT,
        TIMEOUT,
        UNAVAILABLE,
        INVALID_RESPONSE,
        HTTP_ERROR
    }

    /** @param errorDetail truncated here to 4 KB of UTF-8 (docs/05 section 3.5) */
    public record Entry(
            UUID requestId,
            UUID batchId,
            int sourceRowNumber,
            Outcome outcome,
            @Nullable Integer httpStatus,
            @Nullable String errorCode,
            int attempts,
            @Nullable Integer llmCalls,
            int latencyMs,
            @Nullable String provider,
            @Nullable String model,
            @Nullable String promptVersion,
            @Nullable Integer inputTokens,
            @Nullable Integer outputTokens,
            @Nullable String errorDetail) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate ownTransaction;

    public AiCallLogRepository(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void save(Entry entry) {
        ownTransaction.executeWithoutResult(status -> jdbc.sql("""
                        INSERT INTO ai_call_log (request_id, batch_id, source_row_number, endpoint, outcome,
                            http_status, error_code, attempts, llm_calls, latency_ms, provider, model, prompt_version,
                            input_tokens, output_tokens, error_detail)
                        VALUES (:requestId, :batchId, :row, 'TRIAGE', :outcome, :httpStatus, :errorCode, :attempts,
                            :llmCalls, :latencyMs, :provider, :model, :promptVersion, :inputTokens, :outputTokens,
                            :errorDetail)
                        """)
                .param("requestId", entry.requestId())
                .param("batchId", entry.batchId())
                .param("row", entry.sourceRowNumber())
                .param("outcome", entry.outcome().name())
                .param("httpStatus", entry.httpStatus())
                .param("errorCode", storable(entry.errorCode()))
                .param("attempts", entry.attempts())
                .param("llmCalls", entry.llmCalls())
                .param("latencyMs", entry.latencyMs())
                .param("provider", storable(entry.provider()))
                .param("model", storable(entry.model()))
                .param("promptVersion", storable(entry.promptVersion()))
                .param("inputTokens", entry.inputTokens())
                .param("outputTokens", entry.outputTokens())
                .param("errorDetail", truncateUtf8(storable(entry.errorDetail()), ERROR_DETAIL_MAX_BYTES))
                .update());
    }

    /** Never splits a code point, so the stored text is still valid UTF-8. */
    static @Nullable String truncateUtf8(@Nullable String text, int maxBytes) {
        if (text == null) {
            return null;
        }
        int bytes = 0;
        int end = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            bytes += utf8Length(codePoint);
            if (bytes > maxBytes) {
                break;
            }
            end += Character.charCount(codePoint);
        }
        return text.substring(0, end);
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }

    // PostgreSQL rejects U+0000 in text; without this, a body that carries it would fail the write.
    private static @Nullable String storable(@Nullable String text) {
        return text == null ? null : text.replace('\u0000', '\uFFFD');
    }
}
