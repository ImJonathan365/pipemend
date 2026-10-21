package com.pipemend.pipeline.batch;

import com.pipemend.pipeline.batch.ProcessedRecord.QuarantineReason;
import com.pipemend.pipeline.schema.FieldSpec;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.transform.SalesTransaction.LoadOrigin;
import com.pipemend.pipeline.transform.SalesTransactionTransformer;
import com.pipemend.pipeline.triage.AiTriagePort;
import com.pipemend.pipeline.triage.TriageOutcome;
import com.pipemend.pipeline.validation.ErrorCode;
import com.pipemend.pipeline.validation.Validator;
import com.pipemend.pipeline.validation.Violation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private final AiTriagePort triage;

    public RecordProcessor(
            UUID batchId,
            SchemaDefinition schema,
            Validator validator,
            SalesTransactionTransformer transformer,
            AiTriagePort triage) {
        this.batchId = batchId;
        this.schema = schema;
        this.validator = validator;
        this.transformer = transformer;
        this.triage = triage;
    }

    @Override
    public ProcessedRecord process(RawRecord record) {
        List<Violation> violations = List.of();
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
            return triage(record, violations);
        } catch (Exception e) {
            String summary = "Unexpected error while processing the record: " + e;
            return quarantine(
                    record,
                    violations,
                    QuarantineReason.PROCESSING_ERROR,
                    violations.isEmpty() ? summary : summary + ". " + describe(violations));
        }
    }

    private ProcessedRecord triage(RawRecord record, List<Violation> violations) {
        return switch (triage.triage(record, violations)) {
            case TriageOutcome.Unavailable unavailable ->
                quarantine(
                        record,
                        violations,
                        QuarantineReason.AI_UNAVAILABLE,
                        unavailable.detail() + ". " + describe(violations));
        };
    }

    private ProcessedRecord.Quarantined quarantine(
            RawRecord record, List<Violation> violations, QuarantineReason reason, String summary) {
        Map<String, String> payload = switch (record) {
            case RawRecord.Parsed parsed -> parsed.valuesByHeader();
            case RawRecord.Malformed malformed -> Map.of("_rawLine", malformed.rawLine());
        };
        return new ProcessedRecord.Quarantined(batchId, record.sourceRowNumber(), payload, violations, reason, summary);
    }

    private Map<String, String> toFields(RawRecord.Parsed record) {
        Map<String, String> fields = new HashMap<>();
        for (FieldSpec field : schema.fields()) {
            fields.put(field.name(), record.valuesByHeader().get(field.sourceHeader()));
        }
        return fields;
    }

    private static Violation malformedRow(RawRecord.Malformed record) {
        return new Violation(
                Validator.RECORD,
                null,
                ErrorCode.MALFORMED_ROW,
                null,
                Map.of(),
                "Expected " + record.expectedColumns() + " columns, found " + record.actualColumns(),
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
