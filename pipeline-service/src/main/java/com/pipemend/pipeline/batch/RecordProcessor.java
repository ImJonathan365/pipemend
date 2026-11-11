package com.pipemend.pipeline.batch;

import com.pipemend.pipeline.batch.ProcessedRecord.QuarantineReason;
import com.pipemend.pipeline.schema.FieldSpec;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.transform.SalesTransaction.LoadOrigin;
import com.pipemend.pipeline.transform.SalesTransactionTransformer;
import com.pipemend.pipeline.triage.AiTriagePort;
import com.pipemend.pipeline.triage.TriageOutcome;
import com.pipemend.pipeline.triage.TriageRequestBuilder;
import com.pipemend.pipeline.triage.contract.TriageRequest;
import com.pipemend.pipeline.validation.ErrorCode;
import com.pipemend.pipeline.validation.Validator;
import com.pipemend.pipeline.validation.Violation;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * Decides the outcome of one record (docs/04 section 4): valid records are transformed and loaded, invalid ones go
 * through the triage port to quarantine. There is no skip policy: an unexpected exception quarantines the record with
 * PROCESSING_ERROR, so it is never lost (NFR-06).
 */
public final class RecordProcessor implements ItemProcessor<RawRecord, ProcessedRecord> {

    private final UUID batchId;
    private final SchemaDefinition schema;
    private final Validator validator;
    private final SalesTransactionTransformer transformer;
    private final TriageRequestBuilder requests;
    private final AiTriagePort triage;

    public RecordProcessor(
            UUID batchId,
            SchemaDefinition schema,
            Validator validator,
            SalesTransactionTransformer transformer,
            TriageRequestBuilder requests,
            AiTriagePort triage) {
        this.batchId = batchId;
        this.schema = schema;
        this.validator = validator;
        this.transformer = transformer;
        this.requests = requests;
        this.triage = triage;
    }

    @Override
    public ProcessedRecord process(RawRecord record) {
        List<Violation> violations = List.of();
        UUID aiRequestId = null;
        try {
            switch (record) {
                case RawRecord.Malformed malformed -> violations = List.of(malformedRow(malformed));
                case RawRecord.Parsed parsed -> {
                    Map<String, String> fields = toFields(parsed);
                    violations = validator.validate(fields);
                    if (violations.isEmpty()) {
                        return new ProcessedRecord.Loaded(
                                transformer.transform(batchId, parsed.sourceRowNumber(), fields, LoadOrigin.DIRECT));
                    }
                }
            }
            TriageRequest request = requests.build(UUID.randomUUID(), record, violations);
            aiRequestId = request.requestId();
            return triage(record, violations, request);
        } catch (Exception e) {
            String summary = "Unexpected error while processing the record: " + e;
            return quarantine(
                    record,
                    violations,
                    QuarantineReason.PROCESSING_ERROR,
                    violations.isEmpty() ? summary : summary + ". " + describe(violations),
                    null,
                    aiRequestId);
        }
    }

    /** docs/02 section 5: the first step that fails decides the reason. */
    private ProcessedRecord triage(RawRecord record, List<Violation> violations, TriageRequest request) {
        UUID id = request.requestId();
        return switch (triage.triage(request)) {
            case TriageOutcome.Unavailable failed ->
                technical(record, violations, QuarantineReason.AI_UNAVAILABLE, failed.detail(), id);
            case TriageOutcome.InvalidResponse failed ->
                technical(record, violations, QuarantineReason.AI_INVALID_RESPONSE, failed.detail(), id);
            case TriageOutcome.BudgetExhausted failed ->
                technical(record, violations, QuarantineReason.AI_BUDGET_EXCEEDED, failed.detail(), id);
            // AUTO_FIXABLE lands here too until the CorrectionPolicy exists (Sprint 4): no policy, no correction.
            case TriageOutcome.Analysed analysed ->
                quarantine(
                        record,
                        violations,
                        QuarantineReason.AI_REVIEW_REQUIRED,
                        analysed.response().summary(),
                        new ProcessedRecord.AiAnalysis(
                                analysed.body(), analysed.verdict().maxSeverity()),
                        id);
        };
    }

    /** AC-11.2: a summary written by the pipeline, without AI. */
    private ProcessedRecord.Quarantined technical(
            RawRecord record, List<Violation> violations, QuarantineReason reason, String detail, UUID aiRequestId) {
        return quarantine(record, violations, reason, detail + ". " + describe(violations), null, aiRequestId);
    }

    private ProcessedRecord.Quarantined quarantine(
            RawRecord record,
            List<Violation> violations,
            QuarantineReason reason,
            String summary,
            ProcessedRecord.@Nullable AiAnalysis analysis,
            @Nullable UUID aiRequestId) {
        Map<String, String> payload = switch (record) {
            case RawRecord.Parsed parsed -> parsed.valuesByHeader();
            case RawRecord.Malformed malformed -> Map.of(TriageRequestBuilder.RAW_LINE, malformed.rawLine());
        };
        return new ProcessedRecord.Quarantined(
                batchId, record.sourceRowNumber(), payload, violations, reason, summary, analysis, aiRequestId);
    }

    private Map<String, String> toFields(RawRecord.Parsed record) {
        Map<String, String> fields = new HashMap<>();
        for (FieldSpec field : schema.fields()) {
            fields.put(field.name(), record.valuesByHeader().get(field.sourceHeader()));
        }
        return fields;
    }

    private static Violation malformedRow(RawRecord.Malformed record) {
        // Not Map.of: a null receivedColumns is part of the contract (D96).
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("expectedColumns", record.expectedColumns());
        expected.put("receivedColumns", record.receivedColumns());
        return new Violation(
                Validator.RECORD,
                null,
                ErrorCode.MALFORMED_ROW,
                null,
                Collections.unmodifiableMap(expected),
                record.reason(),
                null);
    }

    /** The technical summary of AC-11.2, written by the pipeline without AI. */
    private static String describe(List<Violation> violations) {
        return violations.size() + " violation(s): "
                + violations.stream()
                        .map(v -> v.field() + " " + v.errorCode() + " (" + v.technicalMessage() + ")")
                        .collect(Collectors.joining("; "));
    }
}
