package com.pipemend.pipeline.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipemend.pipeline.schema.FieldSpec;
import com.pipemend.pipeline.schema.SchemaDefinition;
import com.pipemend.pipeline.schema.SchemaTestSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

/**
 * Runs the validator over the committed samples. clean-1k is the Java side of the D40 cross-check on the Python
 * baseline filter; dirty-1k checks every labelled row against its expected codes (AC-19.4). Rows labelled
 * MALFORMED_ROW are the reader's job, so they are covered with the reader in the ingestion job.
 */
class SampleValidationTest {

    private static final Path SAMPLES = Path.of("..", "data", "samples");

    private final SchemaDefinition schema = SchemaTestSupport.realSchema();
    private final Validator validator = new Validator(schema, ValidatorTest.CLOCK);

    @Test
    void everyCleanBaselineRowIsValid() throws IOException {
        List<Map<String, String>> records = records("clean-1k.csv");

        assertThat(records).hasSize(1000);
        for (int i = 0; i < records.size(); i++) {
            assertThat(validator.validate(records.get(i))).as("row %d", i + 1).isEmpty();
        }
    }

    @Test
    void ac_19_4_everyLabelledRowProducesItsExpectedCodes() throws IOException {
        List<Map<String, String>> records = records("dirty-1k.csv");
        List<List<String>> labels = csv("dirty-1k.labels.csv");
        Map<Integer, Set<String>> expected = new TreeMap<>();
        for (List<String> label : labels.subList(1, labels.size())) {
            expected.computeIfAbsent(Integer.valueOf(label.get(0)), row -> new TreeSet<>())
                    .add(label.get(2) + ":" + label.get(5));
        }
        long malformed = expected.values().stream()
                .filter(codes -> codes.contains("_record:MALFORMED_ROW"))
                .count();

        int checked = 0;
        for (int i = 0; i < records.size(); i++) {
            Set<String> labelled = expected.getOrDefault(i + 1, Set.of());
            if (labelled.contains("_record:MALFORMED_ROW")) {
                continue;
            }
            Set<String> actual = validator.validate(records.get(i)).stream()
                    .map(v -> v.field() + ":" + v.errorCode())
                    .collect(Collectors.toCollection(TreeSet::new));
            assertThat(actual).as("row %d", i + 1).isEqualTo(labelled);
            checked++;
        }
        assertThat(expected).hasSize(50);
        assertThat(checked).isEqualTo(1000 - malformed);
    }

    private List<Map<String, String>> records(String file) throws IOException {
        List<List<String>> rows = csv(file);
        List<String> headers = rows.getFirst();
        Map<String, String> fieldByHeader = new HashMap<>();
        for (FieldSpec field : schema.fields()) {
            fieldByHeader.put(field.sourceHeader(), field.name());
        }
        List<Map<String, String>> records = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            Map<String, String> record = new HashMap<>();
            // A malformed row keeps its extra cells unmapped; the test skips it anyway.
            for (int c = 0; c < headers.size(); c++) {
                record.put(fieldByHeader.get(headers.get(c)), row.get(c));
            }
            records.add(record);
        }
        return records;
    }

    private static List<List<String>> csv(String file) throws IOException {
        return parse(Files.readString(SAMPLES.resolve(file), StandardCharsets.UTF_8));
    }

    /** Minimal RFC 4180 parser for the committed samples, which the generator writes with "\n" endings. */
    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c != '"') {
                    cell.append(c);
                } else if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',' || c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                if (c == '\n') {
                    rows.add(row);
                    row = new ArrayList<>();
                }
            } else {
                cell.append(c);
            }
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
