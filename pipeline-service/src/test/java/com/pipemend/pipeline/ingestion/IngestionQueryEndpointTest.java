package com.pipemend.pipeline.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.awaitility.Awaitility.await;

import com.pipemend.pipeline.PostgresTestConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** GET /api/v1/ingestions/{batchId} and the paginated list over real HTTP (FR-14, NFR-20). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class IngestionQueryEndpointTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    private RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.create("http://localhost:" + port);
    }

    @Test
    void ac_14_1_aCompletedBatchShowsEveryFieldOfTheContract() {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add(
                "file",
                new ByteArrayResource(("Invoice,StockCode,Description,Quantity,InvoiceDate,Price,Customer ID,"
                                + "Country\n536365,85123A,ITEM " + UUID.randomUUID()
                                + ",6,2010-12-01 08:26:00,2.55,17850,United Kingdom\n")
                        .getBytes(StandardCharsets.UTF_8)) {
                    @Override
                    public String getFilename() {
                        return "one.csv";
                    }
                });
        String location = http.post()
                .uri("/api/v1/ingestions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .toBodilessEntity()
                .getHeaders()
                .getLocation()
                .toString();

        await().atMost(Duration.ofMinutes(1))
                .until(() -> "COMPLETED".equals(get(location).body().get("status")));
        Map<String, Object> batch = get(location).body();

        assertThat(batch)
                .containsOnlyKeys(
                        "batchId",
                        "status",
                        "sourceFilename",
                        "sourceType",
                        "sourceChecksum",
                        "schema",
                        "schemaMapping",
                        "counts",
                        "aiCalls",
                        "failureReason",
                        "schemaDriftReport",
                        "receivedAt",
                        "startedAt",
                        "finishedAt",
                        "durationMs")
                .containsEntry("sourceFilename", "one.csv")
                .containsEntry("sourceType", "CSV")
                .containsEntry("schema", Map.of("name", "sales_transaction", "version", "1"))
                .containsEntry("counts", Map.of("read", 1, "direct", 1, "autoCorrected", 0, "quarantined", 0))
                .containsEntry("aiCalls", 0)
                .containsEntry("failureReason", null)
                .containsEntry("schemaDriftReport", null);
        assertThat(batch.get("schemaMapping")).asInstanceOf(MAP).containsEntry("strategy", "IDENTITY");
        assertThat(location).isEqualTo("/api/v1/ingestions/" + batch.get("batchId"));
        assertThat(((Number) batch.get("durationMs")).longValue()).isNotNegative();
    }

    @Test
    void ac_14_2_aRunningBatchCountsTheCommittedRowsNotTheStoredColumns() {
        UUID batchId = insertBatch("RUNNING", OffsetDateTime.now());
        // Stored counters that no longer match the rows: the response must not use them.
        jdbc.sql("UPDATE ingestion_batch SET read_count = 5, direct_count = 99, quarantined_count = 99,"
                        + " ai_calls_count = 99, started_at = now() WHERE id = :id")
                .param("id", batchId)
                .update();
        insertSale(batchId, 1, "DIRECT");
        insertSale(batchId, 2, "DIRECT");
        insertSale(batchId, 3, "AUTO_CORRECTED");
        insertQuarantine(batchId, 4);
        insertAiCall(batchId, "SUCCESS");
        insertAiCall(batchId, "CACHE_HIT");

        Map<String, Object> batch = get("/api/v1/ingestions/" + batchId).body();

        assertThat(batch)
                .containsEntry("status", "RUNNING")
                .containsEntry("counts", Map.of("read", 5, "direct", 2, "autoCorrected", 1, "quarantined", 1))
                .containsEntry("aiCalls", 1)
                .containsEntry("finishedAt", null)
                .containsEntry("durationMs", null);
    }

    @Test
    void ac_14_1_aBatchRejectedAtIntakeReadsZeroAndShowsItsReason() {
        UUID batchId = insertBatch("REJECTED", OffsetDateTime.now());
        jdbc.sql("UPDATE ingestion_batch SET failure_reason = 'INVALID_FILE_FORMAT' WHERE id = :id")
                .param("id", batchId)
                .update();

        Map<String, Object> batch = get("/api/v1/ingestions/" + batchId).body();

        assertThat(batch)
                .containsEntry("status", "REJECTED")
                .containsEntry("failureReason", "INVALID_FILE_FORMAT")
                .containsEntry("counts", Map.of("read", 0, "direct", 0, "autoCorrected", 0, "quarantined", 0))
                .containsEntry("schemaMapping", null)
                .containsEntry("startedAt", null);
    }

    @Test
    void ac_14_3_batchesComeNewestFirstInAStableOrderWhenTheyShareTheirTime() {
        // Far in the future, so that these are the newest batches whatever other tests left in the database.
        OffsetDateTime tied = OffsetDateTime.parse("2100-01-02T00:00:00Z");
        UUID first = insertBatch("FAILED", tied);
        UUID second = insertBatch("FAILED", tied);
        UUID older = insertBatch("FAILED", tied.minusDays(1));
        // PostgreSQL orders uuid byte by byte, as the canonical text does; UUID.compareTo compares signed longs.
        List<String> tiedInIdOrder =
                List.of(first, second).stream().map(UUID::toString).sorted().toList();

        Map<String, Object> page0 = get("/api/v1/ingestions?size=2").body();
        Map<String, Object> page1 = get("/api/v1/ingestions?page=1&size=1").body();
        Map<String, Object> page2 = get("/api/v1/ingestions?page=2&size=1").body();

        assertThat(ids(page0)).isEqualTo(tiedInIdOrder);
        assertThat(ids(page1)).containsExactly(tiedInIdOrder.get(1));
        assertThat(ids(page2)).containsExactly(older.toString());
        long total = ((Number) page0.get("totalElements")).longValue();
        assertThat(page0).containsEntry("page", 0).containsEntry("size", 2).containsEntry("totalPages", (int)
                ((total + 1) / 2));
        Map<String, Object> failed =
                get("/api/v1/ingestions?status=FAILED&size=200").body();
        assertThat(content(failed)).isNotEmpty().allSatisfy(b -> assertThat(b).containsEntry("status", "FAILED"));
    }

    @Test
    void ac_14_4_anUnknownBatchIsA404ProblemWithItsCode() {
        Reply reply = get("/api/v1/ingestions/" + UUID.randomUUID());

        assertProblem(reply, 404, "BATCH_NOT_FOUND");
        assertThat(reply.body()).containsEntry("type", "https://pipemend.dev/problems/batch-not-found");
    }

    @Test
    void nfr_20_parametersOutOfTheirRangeAreInvalidParameterProblems() {
        for (String query : List.of(
                "/api/v1/ingestions/not-a-uuid",
                "/api/v1/ingestions?size=201",
                "/api/v1/ingestions?size=0",
                "/api/v1/ingestions?page=-1",
                "/api/v1/ingestions?page=abc",
                "/api/v1/ingestions?status=DONE")) {
            assertProblem(get(query), 400, "INVALID_PARAMETER");
        }
    }

    @Test
    void nfr_20_theFrameworkErrorsUseTheirStatusNameAsCode() {
        assertProblem(get("/api/v1/nothing-here"), 404, "NOT_FOUND");
        assertProblem(exchange(HttpMethod.DELETE, "/api/v1/ingestions"), 405, "METHOD_NOT_ALLOWED");
    }

    private void assertProblem(Reply reply, int status, String code) {
        assertThat(reply.status()).isEqualTo(status);
        assertThat(reply.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(reply.body()).containsEntry("code", code).containsKey("requestId");
        assertThat((String) reply.body().get("requestId")).isNotBlank();
    }

    private Reply get(String uri) {
        return exchange(HttpMethod.GET, uri);
    }

    private Reply exchange(HttpMethod method, String uri) {
        return http.method(method)
                .uri(uri)
                .exchange((request, response) -> new Reply(
                        response.getStatusCode().value(),
                        String.valueOf(response.getHeaders().getContentType()),
                        json.readValue(
                                response.getBody().readAllBytes(), new TypeReference<Map<String, Object>>() {})));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("content");
    }

    private static List<String> ids(Map<String, Object> page) {
        return content(page).stream().map(b -> (String) b.get("batchId")).toList();
    }

    private UUID insertBatch(String status, OffsetDateTime receivedAt) {
        UUID batchId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO ingestion_batch (id, source_filename, source_type, source_checksum,
                            source_size_bytes, schema_name, schema_version, status, received_at)
                        VALUES (:id, 'seeded.csv', 'CSV', :checksum, 1, 'sales_transaction', '1', :status, :receivedAt)
                        """)
                .param("id", batchId)
                .param("checksum", batchId.toString().replace("-", "").repeat(2))
                .param("status", status)
                .param("receivedAt", receivedAt)
                .update();
        return batchId;
    }

    private void insertSale(UUID batchId, int row, String origin) {
        jdbc.sql("""
                        INSERT INTO sales_transaction (batch_id, source_row_number, invoice_no, is_cancellation,
                            stock_code, quantity, invoice_date, unit_price, line_total, country, load_origin)
                        VALUES (:batchId, :row, '536365', false, '85123A', 6, TIMESTAMP '2010-12-01 08:26:00',
                            2.55, 15.30, 'United Kingdom', :origin)
                        """)
                .param("batchId", batchId)
                .param("row", row)
                .param("origin", origin)
                .update();
    }

    private void insertQuarantine(UUID batchId, int row) {
        jdbc.sql("""
                        INSERT INTO quarantine_record (batch_id, source_row_number, raw_payload, violations, summary,
                            quarantine_reason)
                        VALUES (:batchId, :row, '{}', '[]', 'seeded', 'AI_UNAVAILABLE')
                        """).param("batchId", batchId).param("row", row).update();
    }

    private void insertAiCall(UUID batchId, String outcome) {
        jdbc.sql("""
                        INSERT INTO ai_call_log (request_id, batch_id, endpoint, outcome)
                        VALUES (:requestId, :batchId, 'TRIAGE', :outcome)
                        """)
                .param("requestId", UUID.randomUUID())
                .param("batchId", batchId)
                .param("outcome", outcome)
                .update();
    }

    private record Reply(int status, String contentType, Map<String, Object> body) {}
}
