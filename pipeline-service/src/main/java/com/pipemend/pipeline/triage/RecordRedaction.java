package com.pipemend.pipeline.triage;

import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.validation.Validator;
import com.pipemend.pipeline.validation.Violation;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What of a parsed record travels to the AI (NFR-15). Only the request: quarantine_record keeps the row as read,
 * because a human needs it to fix the row (D233). FR-05 sends sampleRows under the same rule (docs/03 NFR-15).
 */
public final class RecordRedaction {

    public static final String REDACTED = "[REDACTED]";

    private final Map<String, String> headersByField = new LinkedHashMap<>();
    private final boolean sendFullRecord;

    public RecordRedaction(SchemaDefinition schema, AiPrivacyProperties privacy) {
        for (String field : privacy.redactFields()) {
            // D232: a name the schema does not know would redact nothing, so the pipeline does not start.
            headersByField.put(field, schema.field(field).sourceHeader());
        }
        this.sendFullRecord = privacy.sendFullRecord();
    }

    public boolean sendFullRecord() {
        return sendFullRecord;
    }

    /** A new map: the record's own is what quarantine_record stores. */
    public Map<String, String> apply(Map<String, String> valuesByHeader, List<Violation> violations) {
        Set<String> violatedFields = new HashSet<>();
        Set<String> violatedHeaders = new HashSet<>();
        for (Violation violation : violations) {
            violatedFields.add(violation.field());
            if (!Validator.RECORD.equals(violation.field()) && violation.sourceHeader() != null) {
                violatedHeaders.add(violation.sourceHeader());
            }
        }
        Map<String, String> sent = new LinkedHashMap<>();
        valuesByHeader.forEach((header, value) -> {
            // D236: a _record violation names no columns of its own.
            if (sendFullRecord || violatedHeaders.contains(header)) {
                sent.put(header, value);
            }
        });
        headersByField.forEach((field, header) -> {
            // D234 and D237: the fields under analysis travel as they are; any other value, even an empty one, not.
            if (!violatedFields.contains(field) && sent.containsKey(header)) {
                sent.put(header, REDACTED);
            }
        });
        return Collections.unmodifiableMap(sent);
    }
}
