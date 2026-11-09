package com.pipemend.pipeline.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipemend.pipeline.PostgresTestConfiguration;
import com.pipemend.pipeline.persistence.AiCallLogRepository.Entry;
import com.pipemend.pipeline.persistence.AiCallLogRepository.Outcome;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** error_detail against a real PostgreSQL: at most 4 KB of UTF-8 (docs/05 section 3.5), never half a character. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class AiCallLogRepositoryTest {

    private static final String EURO = "\u20AC";
    private static final String EMOJI = "\uD83D\uDE00";

    @Autowired
    AiCallLogRepository callLog;

    @Autowired
    JdbcClient jdbc;

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void ac_12_3_aThreeByteCharacterAcrossTheLimitIsLeftOut(int bytesOfItBeforeTheLimit) {
        String head = "a".repeat(AiCallLogRepository.ERROR_DETAIL_MAX_BYTES - bytesOfItBeforeTheLimit);

        String stored = storedDetail(head + EURO + "tail");

        assertThat(stored).isEqualTo(head);
    }

    @Test
    void ac_12_3_aThreeByteCharacterEndingExactlyAtTheLimitIsKept() {
        String head = "a".repeat(AiCallLogRepository.ERROR_DETAIL_MAX_BYTES - 3) + EURO;

        assertThat(storedDetail(head + "tail")).isEqualTo(head);
    }

    @Test
    void ac_12_3_aSurrogatePairIsNeverSplit() {
        String head = "a".repeat(AiCallLogRepository.ERROR_DETAIL_MAX_BYTES - 2);

        assertThat(storedDetail(head + EMOJI)).isEqualTo(head);
        assertThat(storedDetail("a".repeat(AiCallLogRepository.ERROR_DETAIL_MAX_BYTES - 4) + EMOJI + "b"))
                .endsWith(EMOJI);
    }

    @Test
    void ac_12_3_aDetailWithinTheLimitIsKeptWhole() {
        String detail = EURO.repeat(AiCallLogRepository.ERROR_DETAIL_MAX_BYTES / 3);

        assertThat(storedDetail(detail)).isEqualTo(detail);
    }

    @Test
    void aNulCharacterIsStoredAsTheReplacementCharacter() {
        String stored = storedDetail("before\u0000after");

        assertThat(stored).isEqualTo("before\uFFFDafter");
    }

    @Test
    void ac_08_5_everyColumnIsStored() {
        UUID batchId = insertBatch();
        UUID requestId = UUID.randomUUID();

        callLog.save(new Entry(
                requestId, batchId, 7, Outcome.CACHE_HIT, 200, null, 1, 0, 12, "mock", "m", "triage_v1", 5, 6, null));

        assertThat(row(requestId))
                .containsEntry("batch_id", batchId)
                .containsEntry("source_row_number", 7)
                .containsEntry("endpoint", "TRIAGE")
                .containsEntry("outcome", "CACHE_HIT")
                .containsEntry("http_status", 200)
                .containsEntry("attempts", 1)
                .containsEntry("llm_calls", 0)
                .containsEntry("latency_ms", 12)
                .containsEntry("provider", "mock")
                .containsEntry("model", "m")
                .containsEntry("prompt_version", "triage_v1")
                .containsEntry("input_tokens", 5)
                .containsEntry("output_tokens", 6);
    }

    private @Nullable String storedDetail(String detail) {
        UUID requestId = UUID.randomUUID();
        callLog.save(new Entry(
                requestId,
                insertBatch(),
                1,
                Outcome.HTTP_ERROR,
                500,
                "INTERNAL_ERROR",
                1,
                null,
                1,
                null,
                null,
                null,
                null,
                null,
                detail));
        Map<String, Object> row = row(requestId);
        int bytes = jdbc.sql("SELECT octet_length(error_detail) FROM ai_call_log WHERE request_id = :id")
                .param("id", requestId)
                .query(Integer.class)
                .single();
        assertThat(bytes).isLessThanOrEqualTo(AiCallLogRepository.ERROR_DETAIL_MAX_BYTES);
        return (String) row.get("error_detail");
    }

    private Map<String, Object> row(UUID requestId) {
        return jdbc.sql("SELECT * FROM ai_call_log WHERE request_id = :id")
                .param("id", requestId)
                .query()
                .singleRow();
    }

    private UUID insertBatch() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO ingestion_batch (id, source_filename, source_type, source_checksum,
                            source_size_bytes, schema_name, schema_version, status)
                        VALUES (:id, 'sample.csv', 'CSV', :checksum, 1, 'sales_transaction', '1', 'RUNNING')
                        """)
                .param("id", id)
                .param("checksum", id.toString().replace("-", "").repeat(2))
                .update();
        return id;
    }
}
