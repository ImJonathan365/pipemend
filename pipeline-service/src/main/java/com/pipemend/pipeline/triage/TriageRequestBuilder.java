package com.pipemend.pipeline.triage;

import com.pipemend.pipeline.batch.RawRecord;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.triage.contract.TriageRequest;
import com.pipemend.pipeline.validation.Text;
import com.pipemend.pipeline.validation.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The context of an invalid record as it travels to the AI (FR-07). AC-07.1 is partial: AI_REDACT_FIELDS and
 * AI_SEND_FULL_RECORD arrive in Sprint 5 (docs/07), before any real provider can receive this.
 */
public final class TriageRequestBuilder {

    public static final String RAW_LINE = "_rawLine";

    static final int RAW_LINE_MAX_LENGTH = 2000;

    private final UUID batchId;
    private final TriageRequest.SchemaRef schema;
    private final TriageRequest.Source source;

    public TriageRequestBuilder(UUID batchId, SchemaDefinition schema, String filename, String sourceType) {
        this.batchId = batchId;
        this.schema = new TriageRequest.SchemaRef(schema.name(), String.valueOf(schema.version()));
        this.source = new TriageRequest.Source(filename, sourceType);
    }

    public TriageRequest build(UUID requestId, RawRecord record, List<Violation> violations) {
        Map<String, String> raw = switch (record) {
            case RawRecord.Parsed parsed -> parsed.valuesByHeader();
            // AC-07.4: only what travels is cut; quarantine_record keeps the whole line.
            case RawRecord.Malformed malformed ->
                Map.of(RAW_LINE, Text.truncate(malformed.rawLine(), RAW_LINE_MAX_LENGTH));
        };
        return new TriageRequest(
                requestId,
                batchId,
                schema,
                source,
                new TriageRequest.Row(record.sourceRowNumber(), raw),
                withIds(violations),
                // ai-service falls back to AI_EXPLANATION_LANGUAGE (docs/06 A.1).
                null);
    }

    /** The violation shape of docs/06 A.1, with ids v1, v2... in validation order. */
    public static List<TriageRequest.Violation> withIds(List<Violation> violations) {
        List<TriageRequest.Violation> list = new ArrayList<>();
        for (Violation v : violations) {
            list.add(new TriageRequest.Violation(
                    "v" + (list.size() + 1),
                    v.field(),
                    v.sourceHeader(),
                    v.errorCode(),
                    v.receivedValue(),
                    v.expected(),
                    v.technicalMessage(),
                    v.exceptionType()));
        }
        return List.copyOf(list);
    }
}
