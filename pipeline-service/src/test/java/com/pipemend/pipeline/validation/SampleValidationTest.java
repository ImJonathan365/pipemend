package com.pipemend.pipeline.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipemend.pipeline.batch.RawRecord;
import com.pipemend.pipeline.batch.RawRecordReader;
import com.pipemend.pipeline.schema.FieldSpec;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.schema.SchemaTestSupport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.core.io.FileSystemResource;

/**
 * Reads the committed samples with the real reader and validates every record. clean-1k is the Java side of the D40
 * cross-check on the Python baseline filter; dirty-1k checks every labelled row against its expected codes (AC-19.4),
 * MALFORMED_ROW included, which only the reader can produce.
 */
class SampleValidationTest {

    private static final Path SAMPLES = Path.of("..", "data", "samples");

    private final SchemaDefinition schema = SchemaTestSupport.realSchema();
    private final Validator validator = new Validator(schema, ValidatorTest.CLOCK);

    @Test
    void everyCleanBaselineRowIsValid() throws Exception {
        List<RawRecord> records = read("clean-1k.csv");

        assertThat(records).hasSize(1000);
        for (RawRecord record : records) {
            assertThat(codes(record)).as("row %d", record.sourceRowNumber()).isEmpty();
        }
    }

    @Test
    void ac_19_4_everyLabelledRowProducesItsExpectedCodes() throws Exception {
        List<RawRecord> records = read("dirty-1k.csv");
        Map<Integer, Set<String>> expected = new TreeMap<>();
        for (RawRecord label : read("dirty-1k.labels.csv")) {
            Map<String, String> columns = ((RawRecord.Parsed) label).valuesByHeader();
            expected.computeIfAbsent(Integer.valueOf(columns.get("source_row_number")), row -> new TreeSet<>())
                    .add(columns.get("field") + ":" + columns.get("expected_error_code"));
        }

        assertThat(records).hasSize(1000);
        for (RawRecord record : records) {
            assertThat(codes(record))
                    .as("row %d", record.sourceRowNumber())
                    .isEqualTo(expected.getOrDefault(record.sourceRowNumber(), Set.of()));
        }
        assertThat(expected).hasSize(50);
        assertThat(records).filteredOn(RawRecord.Malformed.class::isInstance).hasSize(2);
    }

    private Set<String> codes(RawRecord record) {
        return switch (record) {
            case RawRecord.Malformed malformed -> Set.of(Validator.RECORD + ":" + ErrorCode.MALFORMED_ROW);
            case RawRecord.Parsed parsed ->
                validator.validate(toFields(parsed)).stream()
                        .map(v -> v.field() + ":" + v.errorCode())
                        .collect(Collectors.toCollection(TreeSet::new));
        };
    }

    private Map<String, String> toFields(RawRecord.Parsed record) {
        Map<String, String> fields = new HashMap<>();
        for (FieldSpec field : schema.fields()) {
            fields.put(field.name(), record.valuesByHeader().get(field.sourceHeader()));
        }
        return fields;
    }

    private static List<RawRecord> read(String file) throws Exception {
        RawRecordReader reader = new RawRecordReader(new FileSystemResource(SAMPLES.resolve(file)));
        reader.open(new ExecutionContext());
        List<RawRecord> records = new ArrayList<>();
        for (RawRecord record = reader.read(); record != null; record = reader.read()) {
            records.add(record);
        }
        reader.close();
        return records;
    }
}
