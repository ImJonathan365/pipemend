package com.pipemend.pipeline.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.pipemend.pipeline.PostgresTestConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** POST /api/v1/ingestions over real HTTP: multipart size limits only apply in a real servlet container. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class IngestionEndpointTest {

    private static final String HEADER =
            "Invoice,StockCode,Description,Quantity,InvoiceDate,Price,Customer ID,Country\n";

    @LocalServerPort
    int port;

    @Value("${pipemend.inbox-dir}")
    Path inbox;

    @Autowired
    JdbcClient jdbc;

    private RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.create("http://localhost:" + port);
    }

    @Test
    void ac_01_1_aValidCsvIsAcceptedAtOnceAndProcessedInTheBackground() throws Exception {
        byte[] content = csv(2);

        long start = System.nanoTime();
        Reply reply = upload("clean.csv", content, null);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(reply.status()).isEqualTo(202);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        Map<String, Object> body = reply.json();
        UUID batchId = UUID.fromString((String) body.get("batchId"));
        assertThat(reply.headers().getLocation()).hasToString("/api/v1/ingestions/" + batchId);
        assertThat(body)
                .containsOnlyKeys("batchId", "status", "sourceFilename", "sourceType", "sourceChecksum", "receivedAt")
                .containsEntry("sourceFilename", "clean.csv")
                .containsEntry("sourceType", "CSV")
                .containsEntry("sourceChecksum", sha256(content));
        assertThat((String) body.get("status")).isIn("RECEIVED", "RUNNING");

        awaitEnd(batchId);
        // AC-04.1: the batch keeps its checksum, name, type, timestamps and the Spring Batch execution id.
        assertThat(batch(batchId))
                .containsEntry("status", "COMPLETED")
                .containsEntry("source_checksum", sha256(content))
                .containsEntry("source_size_bytes", (long) content.length)
                .containsEntry("direct_count", 2);
        assertThat(batch(batchId).get("job_execution_id")).isNotNull();
        assertThat(batch(batchId).get("started_at")).isNotNull();
    }

    @Test
    void ac_01_2_theExtensionDecidesAndOnlyCsvIsAcceptedUntilFr02() throws Exception {
        long batches = batchCount();

        Reply xlsx = upload("data.xlsx", csv(1), null);
        Reply jsonFile = upload("data.json", csv(1), null);

        assertProblem(xlsx, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertProblem(jsonFile, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(batchCount()).isEqualTo(batches);
        assertThat(upload("DATA.CSV", csv(1), null).status()).isEqualTo(202);
    }

    @Test
    void ac_01_3_anEmptyOrHeaderOnlyFileIsRejectedWithoutCreatingABatch() throws Exception {
        long batches = batchCount();
        long directories = inboxEntries();

        assertProblem(upload("empty.csv", new byte[0], null), 400, "EMPTY_FILE");
        assertProblem(upload("header.csv", HEADER.getBytes(StandardCharsets.UTF_8), null), 400, "EMPTY_FILE");

        assertThat(batchCount()).isEqualTo(batches);
        assertThat(inboxEntries()).isEqualTo(directories);
    }

    @Test
    void ac_01_8_aRepeatedHeaderIsAcceptedAndThenRejectedByTheJob() throws Exception {
        Reply reply = upload("repeated.csv", ("Invoice,Invoice\n1,2\n" + UUID.randomUUID()).getBytes(), null);

        assertThat(reply.status()).isEqualTo(202);
        UUID batchId = UUID.fromString((String) reply.json().get("batchId"));
        awaitEnd(batchId);
        assertThat(batch(batchId))
                .containsEntry("status", "REJECTED")
                .containsEntry("failure_reason", "INVALID_FILE_FORMAT");
    }

    @Test
    void ac_01_4_aFileOverTheLimitIsRejectedAsProblemJson() throws Exception {
        long batches = batchCount();

        Reply reply = upload("big.csv", new byte[50 * 1024 * 1024 + 1], null);

        assertProblem(reply, 413, "FILE_TOO_LARGE");
        assertThat(batchCount()).isEqualTo(batches);
    }

    @Test
    void aRequestWithoutTheFilePartIsAMissingFile() throws Exception {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("other", "x");

        assertProblem(send(parts, null), 400, "MISSING_FILE");
    }

    @Test
    void ac_01_6_theFileIsStoredUnchangedUnderTheBatchIdAndTheNameNeverBuildsAPath() throws Exception {
        byte[] content = csv(3);

        Reply reply = upload("../../evil.csv", content, null);

        UUID batchId = UUID.fromString((String) reply.json().get("batchId"));
        assertThat(Files.readAllBytes(inbox.resolve(batchId.toString()).resolve("source.csv")))
                .isEqualTo(content);
        assertThat(batch(batchId)).containsEntry("source_filename", "evil.csv");
        assertThat(inbox.getParent().resolve("evil.csv")).doesNotExist();
        awaitEnd(batchId);
    }

    @Test
    void ac_04_2_aDuplicateOfAnActiveBatchIsAConflictThatCreatesNothing() throws Exception {
        byte[] content = csv(1);
        UUID first = UUID.fromString(
                (String) upload("first.csv", content, null).json().get("batchId"));
        awaitEnd(first);
        long batches = batchCount();
        long directories = inboxEntries();

        Reply duplicate = upload("again.csv", content, null);

        assertProblem(duplicate, 409, "DUPLICATE_INGESTION");
        assertThat(duplicate.json())
                .containsEntry("type", "https://pipemend.dev/problems/duplicate-ingestion")
                .containsEntry("existingBatchId", first.toString())
                .containsEntry("existingStatus", "COMPLETED");
        assertThat(batchCount()).isEqualTo(batches);
        assertThat(inboxEntries()).isEqualTo(directories);
    }

    @Test
    void ac_04_3_aDuplicateOfAFailedBatchExplainsWhyWithoutNamingARoute() throws Exception {
        byte[] content = csv(1);
        UUID failed = insertBatch(content, "FAILED");

        Reply duplicate = upload("again.csv", content, null);

        assertProblem(duplicate, 409, "DUPLICATE_INGESTION");
        assertThat(duplicate.json())
                .containsEntry("existingBatchId", failed.toString())
                .containsEntry("existingStatus", "FAILED");
        assertThat((String) duplicate.json().get("detail"))
                .contains("failed", "duplicate data")
                .doesNotContain("/");
    }

    @Test
    void ac_04_3b_aFileWhoseOnlyBatchWasRejectedIsAcceptedAsANewBatch() throws Exception {
        byte[] content = csv(1);
        UUID rejected = insertBatch(content, "REJECTED");

        Reply reply = upload("again.csv", content, null);

        assertThat(reply.status()).isEqualTo(202);
        UUID batchId = UUID.fromString((String) reply.json().get("batchId"));
        assertThat(batchId).isNotEqualTo(rejected);
        awaitEnd(batchId);
    }

    @Test
    void ac_04_5_twoConcurrentUploadsOfTheSameFileGiveExactlyOneBatch() throws Exception {
        byte[] content = csv(50);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Reply>> replies = List.of(
                    pool.submit(() -> {
                        go.await();
                        return upload("a.csv", content, null);
                    }),
                    pool.submit(() -> {
                        go.await();
                        return upload("b.csv", content, null);
                    }));
            go.countDown();

            List<Reply> done = List.of(replies.get(0).get(), replies.get(1).get());
            assertThat(done).extracting(Reply::status).containsExactlyInAnyOrder(202, 409);
            Reply accepted =
                    done.stream().filter(r -> r.status() == 202).findFirst().orElseThrow();
            Reply conflict =
                    done.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
            assertThat(conflict.json())
                    .containsEntry("existingBatchId", accepted.json().get("batchId"));
            awaitEnd(UUID.fromString((String) accepted.json().get("batchId")));
        }
    }

    @Test
    void nfr_20_aValidRequestIdIsEchoedAndAnythingElseIsReplaced() throws Exception {
        String requestId = UUID.randomUUID().toString();

        Reply echoed = upload("data.xlsx", csv(1), requestId);
        Reply replaced = upload("data.xlsx", csv(1), "not-a-uuid");

        assertThat(echoed.headers().getFirst("X-Request-Id")).isEqualTo(requestId);
        assertThat(echoed.json()).containsEntry("requestId", requestId);
        String generated = replaced.headers().getFirst("X-Request-Id");
        assertThat(UUID.fromString(generated)).isNotNull();
        assertThat(replaced.json()).containsEntry("requestId", generated);
    }

    private void assertProblem(Reply reply, int status, String code) {
        assertThat(reply.status()).isEqualTo(status);
        assertThat(reply.headers().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(reply.json())
                .containsEntry("status", status)
                .containsEntry("code", code)
                .containsKeys("type", "title", "detail", "instance", "requestId")
                .doesNotContainKey("trace");
    }

    private Reply upload(String filename, byte[] content, @Nullable String requestId) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        return send(parts, requestId);
    }

    private Reply send(MultiValueMap<String, Object> parts, @Nullable String requestId) {
        return http.post()
                .uri("/api/v1/ingestions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .headers(headers -> {
                    if (requestId != null) {
                        headers.set("X-Request-Id", requestId);
                    }
                })
                .body(parts)
                .exchange((request, response) -> new Reply(
                        response.getStatusCode().value(),
                        response.getHeaders(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private void awaitEnd(UUID batchId) {
        await().atMost(Duration.ofMinutes(1))
                .until(() ->
                        !List.of("RECEIVED", "RUNNING").contains(batch(batchId).get("status")));
    }

    private Map<String, Object> batch(UUID batchId) {
        return jdbc.sql("SELECT * FROM ingestion_batch WHERE id = :id")
                .param("id", batchId)
                .query()
                .singleRow();
    }

    private UUID insertBatch(byte[] content, String status) throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO ingestion_batch (id, source_filename, source_type, source_checksum,
                            source_size_bytes, schema_name, schema_version, status)
                        VALUES (:id, 'old.csv', 'CSV', :checksum, :size, 'sales_transaction', '1', :status)
                        """)
                .param("id", id)
                .param("checksum", sha256(content))
                .param("size", content.length)
                .param("status", status)
                .update();
        return id;
    }

    private long batchCount() {
        return jdbc.sql("SELECT count(*) FROM ingestion_batch")
                .query(Long.class)
                .single();
    }

    private long inboxEntries() throws IOException {
        try (Stream<Path> entries = Files.list(inbox)) {
            return entries.count();
        }
    }

    /** Valid rows that differ from any other test's, so no two uploads share a checksum. */
    private static byte[] csv(int rows) {
        StringBuilder text = new StringBuilder(HEADER);
        for (int i = 0; i < rows; i++) {
            text.append("536365,85123A,ITEM ")
                    .append(UUID.randomUUID())
                    .append(",6,2010-12-01 08:26:00,2.55,17850,United Kingdom\n");
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private record Reply(int status, HttpHeaders headers, String body) {

        Map<String, Object> json() {
            return JsonMapper.shared().readValue(body, new TypeReference<>() {});
        }
    }
}
