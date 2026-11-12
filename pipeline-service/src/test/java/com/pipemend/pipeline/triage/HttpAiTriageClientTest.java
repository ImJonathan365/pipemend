package com.pipemend.pipeline.triage;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.pipemend.pipeline.PostgresTestConfiguration;
import com.pipemend.pipeline.persistence.AiCallLogRepository;
import com.pipemend.pipeline.triage.contract.Classification;
import com.pipemend.pipeline.triage.contract.Severity;
import com.pipemend.pipeline.triage.contract.TriageRequest;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The client against WireMock on 127.0.0.1 and a real PostgreSQL: one test per row of docs/06 A.5, plus what the table
 * does not list. No test leaves the loopback interface.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class HttpAiTriageClientTest {

    private static final String PROBLEM = "application/problem+json";
    private static final String ROW_VALUE = "row-value-that-must-not-reach-the-logs";
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(300);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(1000);

    private static WireMockServer server;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    AiCallLogRepository callLog;

    @Autowired
    AiClientProperties defaults;

    @Autowired
    PlatformTransactionManager transactions;

    private HttpAiTriageClient client;
    private TriageRequest request;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        server.resetAll();
        client = clientFor(URI.create(server.baseUrl()));
        TriageRequest fixture = TriageResponseFixtures.requestOf("triage-response.auto-fixable.json");
        request = new TriageRequest(
                fixture.requestId(),
                insertBatch(),
                fixture.schema(),
                fixture.source(),
                fixture.record(),
                fixture.violations(),
                fixture.options());
    }

    @Test
    void theSpringTestsReachAClosedLoopbackPortWithTheDefaultTimeouts() {
        assertThat(defaults)
                .isEqualTo(new AiClientProperties(
                        URI.create(PostgresTestConfiguration.AI_DOWN),
                        Duration.ofMillis(2000),
                        Duration.ofMillis(30000)));
    }

    @Test
    void theDefaultBaseUrlIsTheComposeServiceOfDocs04() throws IOException {
        assertThat(new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8))
                .contains("base-url: ${AI_SERVICE_BASE_URL:http://ai-service:8000}");
    }

    @Test
    void sendsTheRequestWithTheIdsInTheHeaders() {
        answer(aResponse().withStatus(200).withBody(validAnswer().toString()));

        client.triage(request);

        server.verify(postRequestedFor(urlEqualTo("/v1/triage"))
                .withHeader("X-Request-Id", equalTo(request.requestId().toString()))
                .withHeader("X-Batch-Id", equalTo(request.batchId().toString()))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson(json.writeValueAsString(request))));
    }

    @Test
    void ac_08_6_aValidAnswerIsAnalysedAndLogsItsMeta() {
        String body = validAnswer().toString();
        answer(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body));

        TriageOutcome reply = client.triage(request);

        assertThat(reply).isInstanceOfSatisfying(TriageOutcome.Analysed.class, analysed -> {
            assertThat(analysed.body()).isEqualTo(body);
            assertThat(analysed.verdict().recordDecision()).isEqualTo(Classification.AUTO_FIXABLE);
            assertThat(analysed.verdict().maxSeverity()).isEqualTo(Severity.LOW);
            assertThat(analysed.response().requestId()).isEqualTo(request.requestId());
        });
        assertThat(onlyRow())
                .containsEntry("endpoint", "TRIAGE")
                .containsEntry("outcome", "SUCCESS")
                .containsEntry("http_status", 200)
                .containsEntry("error_code", null)
                .containsEntry("source_row_number", request.record().sourceRowNumber())
                .containsEntry("llm_calls", 0)
                .containsEntry("provider", "mock")
                .containsEntry("model", "mock-rules-v1")
                .containsEntry("prompt_version", "triage_v1")
                .containsEntry("input_tokens", null)
                .containsEntry("output_tokens", null)
                .containsEntry("error_detail", null);
    }

    @Test
    void ac_08_6_aCachedAnswerIsLoggedAsCacheHit() {
        ObjectNode body = validAnswer();
        ((ObjectNode) body.get("meta")).put("cacheHit", true);
        answer(aResponse().withStatus(200).withBody(body.toString()));

        assertThat(client.triage(request)).isInstanceOf(TriageOutcome.Analysed.class);
        assertThat(onlyRow()).containsEntry("outcome", "CACHE_HIT");
    }

    static Stream<Arguments> ac_12_3_invalidAnswers() {
        ObjectNode numericSeverity = validAnswer();
        numericSeverity.put("maxSeverity", 0);
        ObjectNode otherField = validAnswer();
        ((ObjectNode) otherField.get("analyses").get(0)).put("field", "country");
        ObjectNode otherRequestId = validAnswer();
        otherRequestId.put("requestId", UUID.randomUUID().toString());
        ObjectNode tooManyCalls = validAnswer();
        ((ObjectNode) tooManyCalls.get("meta")).put("llmCalls", Short.MAX_VALUE + 1);
        return Stream.of(
                arguments("not the contract", numericSeverity.toString()),
                arguments("analysis on another field", otherField.toString()),
                arguments("another requestId", otherRequestId.toString()),
                arguments("llmCalls beyond a smallint", tooManyCalls.toString()),
                arguments("not JSON", "<html>" + ROW_VALUE + "</html>"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void ac_12_3_invalidAnswers(String why, String body, CapturedOutput output) {
        answer(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body));

        assertThat(client.triage(request)).isInstanceOf(TriageOutcome.InvalidResponse.class);
        assertThat(onlyRow())
                .containsEntry("outcome", "INVALID_RESPONSE")
                .containsEntry("http_status", 200)
                .containsEntry("error_detail", body)
                .containsEntry("llm_calls", null)
                .containsEntry("provider", null);
        assertThat(output).doesNotContain(ROW_VALUE);
    }

    static Stream<Arguments> docs06A5_uPlus0000() {
        ObjectNode inSummary = validAnswer();
        inSummary.put("summary", "a" + (char) 0 + "b");
        ObjectNode inAParameter = validAnswer();
        ((ObjectNode) inAParameter.get("analyses").get(0).get("correction").get("parameters"))
                .put("sourcePattern", "dd/MM" + (char) 0);
        ObjectNode inAnUnknownKey = validAnswer();
        inAnUnknownKey.put("extra" + (char) 0, 1);
        return Stream.of(
                arguments("in summary", inSummary.toString()),
                arguments("in a value of correction.parameters", inAParameter.toString()),
                arguments("in a key TriageJson ignores", inAnUnknownKey.toString()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void docs06A5_uPlus0000(String where, String body) {
        answer(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body));

        assertThat(client.triage(request))
                .isEqualTo(
                        new TriageOutcome.InvalidResponse("The answer of ai-service has U+0000 in a key or a string"));
        assertThat(onlyRow())
                .containsEntry("outcome", "INVALID_RESPONSE")
                .containsEntry("http_status", 200)
                .containsEntry("provider", null)
                // The body carries the six characters of the JSON escape, which text admits; never U+0000 itself.
                .containsEntry("error_detail", body);
    }

    /** The number goes into the body as written, in place of a placeholder string. */
    private static String withNumber(String where, String number) {
        ObjectNode body = validAnswer();
        ObjectNode parameters =
                (ObjectNode) body.get("analyses").get(0).get("correction").get("parameters");
        switch (where) {
            case "parameter" -> parameters.put("n", "@number@");
            case "array" -> parameters.putArray("l").add("@number@");
            default -> body.put("extra", "@number@");
        }
        return body.toString().replace("\"@number@\"", number);
    }

    @ParameterizedTest(name = "{1} in {0}")
    @CsvSource({
        "parameter, 1e1000000",
        "unknown key, 1e-20000",
        "array, 1e1000000",
        "parameter, 1.5e-16383",
        "parameter, -1e131072",
        "parameter, 10e131071",
        "parameter, 1.50e-16382",
        "parameter, 1e2147483647",
        "parameter, 1e-2147483647",
        // Beyond the int scale of BigDecimal: the tree cannot even be read (NumberFormatException).
        "parameter, 1e2147483648",
        "unknown key, 1e-2147483649",
        "array, 1e99999999999"
    })
    void docs06A5_aNumberPostgresCannotStoreIsAnInvalidResponse(String where, String number) {
        String body = withNumber(where, number);
        answer(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body));

        assertThat(client.triage(request))
                .isEqualTo(new TriageOutcome.InvalidResponse(
                        "The answer of ai-service has a number that PostgreSQL numeric cannot hold"));
        assertThat(onlyRow())
                .containsEntry("outcome", "INVALID_RESPONSE")
                .containsEntry("http_status", 200)
                .containsEntry("provider", null)
                .containsEntry("error_detail", body);
    }

    /** The exact limits of numeric on postgres:18-alpine: 131072 integer digits and 16383 decimals, zeros included. */
    @ParameterizedTest(name = "{1} in {0}")
    @CsvSource({
        "parameter, 1e131071",
        "parameter, 1e-16383",
        "parameter, 1.5e-16382",
        "unknown key, 1.0e131071",
        "array, -1e131071"
    })
    void docs06A5_aNumberAtTheLimitsOfNumericIsAccepted(String where, String number) {
        answer(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(withNumber(where, number)));

        assertThat(client.triage(request)).isInstanceOf(TriageOutcome.Analysed.class);
        assertThat(onlyRow()).containsEntry("outcome", "SUCCESS");
    }

    /** D141: a literal NUL byte is not valid JSON, and error_detail stores it as U+FFFD (D131). */
    @Test
    void docs06A5_aLiteralNulByteIsAnInvalidResponse() {
        String nul = String.valueOf((char) 0);
        String body = validAnswer().put("summary", "@summary@").toString().replace("@summary@", "a" + nul + "b");
        answer(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body));

        assertThat(client.triage(request)).isInstanceOf(TriageOutcome.InvalidResponse.class);
        String stored = (String) onlyRow().get("error_detail");
        assertThat(onlyRow()).containsEntry("outcome", "INVALID_RESPONSE");
        assertThat(stored).contains(String.valueOf((char) 0xFFFD)).doesNotContain(nul);
        assertThat(stored).isEqualTo(body.replace(nul, String.valueOf((char) 0xFFFD)));
    }

    @Test
    void docs06A5_uPlus0001IsAccepted() {
        ObjectNode body = validAnswer();
        body.put("summary", "a" + (char) 1 + "b");
        answer(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body.toString()));

        assertThat(client.triage(request))
                .isInstanceOfSatisfying(
                        TriageOutcome.Analysed.class,
                        analysed -> assertThat(analysed.response().summary()).isEqualTo("a" + (char) 1 + "b"));
        assertThat(onlyRow()).containsEntry("outcome", "SUCCESS");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ac_12_3_anEmpty200IsAnInvalidResponse(boolean withContentType) {
        ResponseDefinitionBuilder empty = aResponse().withStatus(200);
        answer(withContentType ? empty.withHeader("Content-Type", "application/json") : empty);

        assertThat(client.triage(request)).isInstanceOf(TriageOutcome.InvalidResponse.class);
        assertThat(onlyRow())
                .containsEntry("outcome", "INVALID_RESPONSE")
                .containsEntry("http_status", 200)
                .containsEntry("error_detail", null);
    }

    static Stream<Arguments> docs06A5() {
        return Stream.of(
                // The rows of docs/06 A.5.
                arguments(400, PROBLEM, "INVALID_REQUEST", TriageOutcome.InvalidResponse.class, "HTTP_ERROR"),
                arguments(404, PROBLEM, "NOT_FOUND", TriageOutcome.InvalidResponse.class, "HTTP_ERROR"),
                arguments(405, PROBLEM, "METHOD_NOT_ALLOWED", TriageOutcome.InvalidResponse.class, "HTTP_ERROR"),
                arguments(422, PROBLEM, "VALIDATION_ERROR", TriageOutcome.InvalidResponse.class, "HTTP_ERROR"),
                arguments(429, PROBLEM, "RATE_LIMITED", TriageOutcome.Unavailable.class, "HTTP_ERROR"),
                arguments(429, PROBLEM, "LLM_BUDGET_EXHAUSTED", TriageOutcome.BudgetExhausted.class, "HTTP_ERROR"),
                arguments(500, PROBLEM, "INTERNAL_ERROR", TriageOutcome.Unavailable.class, "HTTP_ERROR"),
                arguments(502, PROBLEM, "LLM_INVALID_OUTPUT", TriageOutcome.InvalidResponse.class, "HTTP_ERROR"),
                arguments(502, PROBLEM, "LLM_PROVIDER_ERROR", TriageOutcome.Unavailable.class, "HTTP_ERROR"),
                arguments(503, PROBLEM, "PROVIDER_NOT_CONFIGURED", TriageOutcome.Unavailable.class, "HTTP_ERROR"),
                arguments(504, PROBLEM, "LLM_TIMEOUT", TriageOutcome.Unavailable.class, "HTTP_ERROR"),
                arguments(502, "text/html", null, TriageOutcome.Unavailable.class, "UNAVAILABLE"),
                arguments(503, "text/html", null, TriageOutcome.Unavailable.class, "UNAVAILABLE"),
                arguments(504, "text/html", null, TriageOutcome.Unavailable.class, "UNAVAILABLE"),
                arguments(
                        503,
                        "application/json",
                        "PROVIDER_NOT_CONFIGURED",
                        TriageOutcome.Unavailable.class,
                        "UNAVAILABLE"),
                // Outside the table: by the status, whatever the code says.
                arguments(400, PROBLEM, "LLM_BUDGET_EXHAUSTED", TriageOutcome.InvalidResponse.class, "HTTP_ERROR"),
                arguments(429, PROBLEM, "LLM_INVALID_OUTPUT", TriageOutcome.Unavailable.class, "HTTP_ERROR"),
                arguments(503, PROBLEM, "LLM_INVALID_OUTPUT", TriageOutcome.Unavailable.class, "HTTP_ERROR"),
                arguments(429, "text/plain", null, TriageOutcome.Unavailable.class, "HTTP_ERROR"),
                arguments(422, "text/plain", null, TriageOutcome.InvalidResponse.class, "HTTP_ERROR"),
                arguments(401, PROBLEM, "UNAUTHORIZED", TriageOutcome.InvalidResponse.class, "HTTP_ERROR"),
                arguments(418, "text/plain", null, TriageOutcome.InvalidResponse.class, "HTTP_ERROR"),
                arguments(302, "text/plain", null, TriageOutcome.InvalidResponse.class, "HTTP_ERROR"),
                arguments(201, "application/json", null, TriageOutcome.InvalidResponse.class, "HTTP_ERROR"),
                arguments(500, "text/plain", null, TriageOutcome.Unavailable.class, "UNAVAILABLE"),
                arguments(501, PROBLEM, "SOMETHING_NEW", TriageOutcome.Unavailable.class, "HTTP_ERROR"),
                arguments(503, PROBLEM, null, TriageOutcome.Unavailable.class, "UNAVAILABLE"));
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource
    void docs06A5(
            int status,
            String contentType,
            @Nullable String code,
            Class<? extends TriageOutcome> expected,
            String outcome) {
        String body = code == null
                ? "{\"detail\":\"" + ROW_VALUE + "\"}"
                : "{\"type\":\"https://pipemend.dev/problems/x\",\"title\":\"t\",\"status\":" + status
                        + ",\"detail\":\"" + ROW_VALUE + "\",\"code\":\"" + code + "\"}";
        answer(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", contentType)
                .withBody(body));

        assertThat(client.triage(request)).isInstanceOf(expected);
        assertThat(onlyRow())
                .containsEntry("outcome", outcome)
                .containsEntry("http_status", status)
                .containsEntry("error_code", contentType.equals(PROBLEM) ? code : null)
                .containsEntry("error_detail", body);
    }

    @Test
    void docs06A5_aContractBugIsLoggedAsErrorWithoutTheBody(CapturedOutput output) {
        answer(aResponse()
                .withStatus(422)
                .withHeader("Content-Type", PROBLEM)
                .withBody("{\"code\":\"VALIDATION_ERROR\",\"detail\":\"" + ROW_VALUE + "\"}"));

        client.triage(request);

        assertThat(output).contains("ai-service rejected request " + request.requestId());
        assertThat(output).contains("HTTP 422 VALIDATION_ERROR");
        assertThat(output).doesNotContain(ROW_VALUE);
    }

    @Test
    void docs06A5_aRefusedConnectionIsUnavailable() throws IOException {
        int closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }
        client = clientFor(URI.create("http://127.0.0.1:" + closed));

        assertThat(client.triage(request)).isInstanceOf(TriageOutcome.Unavailable.class);
        assertThat(onlyRow())
                .containsEntry("outcome", "UNAVAILABLE")
                .containsEntry("http_status", null)
                .containsEntry("error_detail", "java.net.ConnectException");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE"})
    void docs06A5_aCutConnectionIsUnavailable(String fault) {
        answer(aResponse().withFault(Fault.valueOf(fault)));

        assertThat(client.triage(request)).isInstanceOf(TriageOutcome.Unavailable.class);
        assertThat(onlyRow())
                .containsEntry("outcome", "UNAVAILABLE")
                .containsEntry("http_status", null)
                .containsEntry("error_detail", "java.io.IOException");
    }

    @Test
    void docs06A5_aReadTimeoutIsUnavailable() {
        answer(aResponse()
                .withStatus(200)
                .withBody(validAnswer().toString())
                .withFixedDelay((int) READ_TIMEOUT.toMillis() * 3));

        assertThat(client.triage(request))
                .isEqualTo(new TriageOutcome.Unavailable(
                        "ai-service did not answer in time: java.net.http.HttpTimeoutException"));
        assertThat(onlyRow())
                .containsEntry("outcome", "TIMEOUT")
                .containsEntry("http_status", null)
                .containsEntry("error_detail", "java.net.http.HttpTimeoutException");
    }

    @Test
    void docs06A5_aConnectTimeoutIsUnavailable() throws IOException {
        List<Socket> held = new ArrayList<>();
        try (ServerSocket full = saturated(held)) {
            client = clientFor(URI.create("http://127.0.0.1:" + full.getLocalPort()));

            assertThat(client.triage(request))
                    .isEqualTo(new TriageOutcome.Unavailable(
                            "ai-service did not answer in time: java.net.http.HttpConnectTimeoutException"));
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }
        assertThat(onlyRow())
                .containsEntry("outcome", "TIMEOUT")
                .containsEntry("http_status", null)
                .containsEntry("error_detail", "java.net.http.HttpConnectTimeoutException");
    }

    @Test
    void nfr_05_theRowSurvivesARollbackOfTheChunk() {
        answer(aResponse()
                .withStatus(503)
                .withHeader("Content-Type", "text/html")
                .withBody("down"));
        UUID control = UUID.randomUUID();

        new TransactionTemplate(transactions).executeWithoutResult(chunk -> {
            insertBatch(control);
            client.triage(request);
            chunk.setRollbackOnly();
        });

        assertThat(onlyRow()).containsEntry("outcome", "UNAVAILABLE");
        assertThat(jdbc.sql("SELECT count(*) FROM ingestion_batch WHERE id = :id")
                        .param("id", control)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void nfr_03_aFailedLogWriteReachesTheCaller() {
        answer(aResponse().withStatus(200).withBody(validAnswer().toString()));
        // No ingestion_batch row, so the foreign key of ai_call_log rejects the write.
        TriageRequest orphan = new TriageRequest(
                request.requestId(),
                UUID.randomUUID(),
                request.schema(),
                request.source(),
                request.record(),
                request.violations(),
                request.options());

        assertThatThrownBy(() -> client.triage(orphan)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private HttpAiTriageClient clientFor(URI baseUrl) {
        return new HttpAiTriageClient(new AiClientProperties(baseUrl, CONNECT_TIMEOUT, READ_TIMEOUT), json, callLog);
    }

    private static void answer(ResponseDefinitionBuilder response) {
        server.stubFor(post(urlEqualTo("/v1/triage")).willReturn(response));
    }

    private static ObjectNode validAnswer() {
        return TriageResponseFixtures.tree("triage-response.auto-fixable.json");
    }

    /** AC-08.5: exactly one row per call, also a failed one, with attempts = 1 until Sprint 4. */
    private Map<String, Object> onlyRow() {
        List<Map<String, Object>> rows = jdbc.sql(
                        "SELECT * FROM ai_call_log WHERE batch_id = :batch AND request_id = :request")
                .param("batch", request.batchId())
                .param("request", request.requestId())
                .query()
                .listOfRows();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row).containsEntry("attempts", 1);
        assertThat((Integer) row.get("latency_ms")).isNotNegative();
        return row;
    }

    /** A loopback listener whose accept queue is full: Linux drops the next SYN, so connect waits for its timeout. */
    private static ServerSocket saturated(List<Socket> held) throws IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        for (int i = 0; i < 64; i++) {
            Socket socket = new Socket();
            try {
                socket.connect(server.getLocalSocketAddress(), (int) CONNECT_TIMEOUT.toMillis());
                held.add(socket);
            } catch (SocketTimeoutException e) {
                socket.close();
                return server;
            }
        }
        server.close();
        throw new IllegalStateException("The accept queue never filled up");
    }

    private UUID insertBatch() {
        UUID id = UUID.randomUUID();
        insertBatch(id);
        return id;
    }

    private void insertBatch(UUID id) {
        jdbc.sql("""
                        INSERT INTO ingestion_batch (id, source_filename, source_type, source_checksum,
                            source_size_bytes, schema_name, schema_version, status)
                        VALUES (:id, 'sample.csv', 'CSV', :checksum, 1, 'sales_transaction', '1', 'RUNNING')
                        """)
                .param("id", id)
                .param("checksum", id.toString().replace("-", "").repeat(2))
                .update();
    }
}
