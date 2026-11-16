package com.pipemend.pipeline.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipemend.pipeline.policy.CorrectionApplier;
import com.pipemend.pipeline.policy.CorrectionCatalog;
import com.pipemend.pipeline.policy.CorrectionPolicy;
import com.pipemend.pipeline.policy.PolicyProperties;
import com.pipemend.pipeline.schema.FieldRule.DecimalRule;
import com.pipemend.pipeline.schema.FieldRule.EnumRule;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.schema.SchemaLoader;
import com.pipemend.pipeline.schema.SchemaTestSupport;
import com.pipemend.pipeline.transform.SalesTransactionTransformer;
import com.pipemend.pipeline.triage.AiBudget;
import com.pipemend.pipeline.triage.AiTriagePort;
import com.pipemend.pipeline.triage.TriageOutcome;
import com.pipemend.pipeline.triage.TriageRequestBuilder;
import com.pipemend.pipeline.triage.TriageResponseValidator;
import com.pipemend.pipeline.triage.contract.Classification;
import com.pipemend.pipeline.triage.contract.Operation;
import com.pipemend.pipeline.triage.contract.RootCause;
import com.pipemend.pipeline.triage.contract.Severity;
import com.pipemend.pipeline.triage.contract.TriageRequest;
import com.pipemend.pipeline.triage.contract.TriageResponse;
import com.pipemend.pipeline.validation.Validator;
import com.pipemend.pipeline.validation.Whitespace;
import com.pipemend.pipeline.verifiers.CountryAliases;
import com.pipemend.pipeline.verifiers.Verifiers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

public final class RecordTestSupport {

    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC);

    private RecordTestSupport() {}

    /** TRIM to the received value without its outer spaces; a value to replace a null one, which the policy rejects. */
    public static final Function<TriageRequest.Violation, TriageResponse.Correction> STRIP =
            violation -> new TriageResponse.Correction(
                    Operation.TRIM,
                    violation.receivedValue(),
                    violation.receivedValue() == null ? "x" : Whitespace.strip(violation.receivedValue()),
                    Map.of());

    /** A valid record keyed by the real file headers. */
    public static Map<String, String> validRow() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Invoice", "536365");
        row.put("StockCode", "85123A");
        row.put("Description", "WHITE HANGING HEART T-LIGHT HOLDER");
        row.put("Quantity", "6");
        row.put("InvoiceDate", "2010-12-01 08:26:00");
        row.put("Price", "2.55");
        row.put("Customer ID", "17850");
        row.put("Country", "United Kingdom");
        return row;
    }

    /** The real policy, verifiers and validator, never a mock (docs/09 section 2.3). */
    public static RecordProcessor processor(UUID batchId, SchemaDefinition schema, AiTriagePort triage) {
        return processor(batchId, schema, triage, new AiBudget(() -> false));
    }

    public static RecordProcessor processor(
            UUID batchId, SchemaDefinition schema, AiTriagePort triage, AiBudget budget) {
        Validator validator = new Validator(schema, CLOCK);
        return new RecordProcessor(
                batchId,
                schema,
                validator,
                new SalesTransactionTransformer(schema),
                new TriageRequestBuilder(batchId, schema, "dirty.csv", "CSV"),
                triage,
                budget,
                applier(schema, validator));
    }

    /** With the defaults of application.yml: 0.85, 0.90 and the whole allowlist of docs/02 section 4. */
    public static CorrectionApplier applier(SchemaDefinition schema, Validator validator) {
        CountryAliases aliases = CountryAliases.load(SchemaTestSupport.resource("country-aliases.v1.yaml"), (EnumRule)
                schema.field("country").rule());
        return new CorrectionApplier(
                new CorrectionPolicy(new PolicyProperties(0.85, 0.90, CorrectionCatalog.FIELDS)),
                Verifiers.of(schema, aliases, 0.90),
                validator);
    }

    /**
     * A port that answers every request with an AUTO_FIXABLE LOW analysis of each violation, with the correction the
     * function proposes for it. The answer must pass the real TriageResponseValidator, as it would in the client.
     */
    public static AiTriagePort autoFixable(
            String explanation, Function<TriageRequest.Violation, TriageResponse.Correction> proposal) {
        return request -> {
            TriageResponse response = new TriageResponse(
                    request.requestId(),
                    Classification.AUTO_FIXABLE,
                    Severity.LOW,
                    "summary of the AI",
                    request.violations().stream()
                            .map(violation -> new TriageResponse.Analysis(
                                    violation.violationId(),
                                    violation.field(),
                                    RootCause.FORMAT_MISMATCH,
                                    explanation,
                                    Severity.LOW,
                                    Classification.AUTO_FIXABLE,
                                    0.97,
                                    proposal.apply(violation),
                                    null))
                            .toList(),
                    new TriageResponse.Meta("mock", "mock-rules-v1", "triage_v1", 1, false, null, null, 0));
            TriageResponseValidator.Result verdict = TriageResponseValidator.validate(request, response);
            assertThat(verdict).isInstanceOf(TriageResponseValidator.Result.Valid.class);
            return new TriageOutcome.Analysed(
                    response,
                    JsonMapper.builder().build().writeValueAsString(response),
                    (TriageResponseValidator.Result.Valid) verdict);
        };
    }

    /** The real schema with unit_price at scale 3, so "2.555" passes validation and reaches the transformer. */
    public static SchemaDefinition schemaWithPriceScaleThree() {
        String yaml = SchemaTestSupport.resource("sales_transaction.v1.yaml");
        SchemaDefinition schema =
                SchemaLoader.load(yaml.replaceFirst("scale: 2", "scale: 3"), SchemaTestSupport::resource);
        assertThat(((DecimalRule) schema.field("unit_price").rule()).scale()).isEqualTo(3);
        return schema;
    }
}
