package com.pipemend.pipeline.batch;

import com.pipemend.pipeline.persistence.IngestionBatchRepository;
import com.pipemend.pipeline.schema.FieldSpec;
import com.pipemend.pipeline.schema.SchemaDefinition;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * Accepts only headers identical to the schema's (AC-05.1). Any other header set ends the batch REJECTED with
 * SCHEMA_DRIFT_UNRESOLVED, which stays the behaviour if FR-05 is cut; normalisation and AI mapping belong to FR-05.
 */
public class SchemaCheckTasklet implements Tasklet {

    private final IngestionBatchRepository batches;
    private final Inbox inbox;
    private final SchemaDefinition schema;
    private final JsonMapper json;

    public SchemaCheckTasklet(IngestionBatchRepository batches, Inbox inbox, SchemaDefinition schema, JsonMapper json) {
        this.batches = batches;
        this.inbox = inbox;
        this.schema = schema;
        this.json = json;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        UUID batchId = IngestionJobConfiguration.batchId(contribution.getStepExecution());
        RawRecordReader reader = new RawRecordReader(inbox.source(batchId));
        List<String> received;
        try {
            reader.open(new ExecutionContext());
            received = reader.headers();
        } finally {
            reader.close();
        }
        List<String> expected =
                schema.fields().stream().map(FieldSpec::sourceHeader).toList();
        // The reader already rejects repeated headers, so equal sets of equal size mean the same headers.
        if (received.size() == expected.size() && new HashSet<>(received).equals(new HashSet<>(expected))) {
            batches.saveSchemaMapping(batchId, json.writeValueAsString(identityMapping()));
            return RepeatStatus.FINISHED;
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("receivedHeaders", received);
        report.put("expectedHeaders", expected);
        report.put("reason", "The headers are not identical to the expected ones (AC-05.1)");
        batches.reject(batchId, "SCHEMA_DRIFT_UNRESOLVED", json.writeValueAsString(report));
        contribution.setExitStatus(IngestionJobConfiguration.REJECTED);
        return RepeatStatus.FINISHED;
    }

    /** The schema_mapping shape of docs/05 section 5, with every header resolved by identity. */
    private Map<String, Object> identityMapping() {
        Map<String, Object> mapping = new LinkedHashMap<>();
        for (FieldSpec field : schema.fields()) {
            mapping.put(field.sourceHeader(), Map.of("field", field.name(), "origin", "IDENTITY"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", "IDENTITY");
        result.put("mapping", mapping);
        result.put("ignoredHeaders", List.of());
        result.put("missingOptionalFields", List.of());
        return result;
    }
}
