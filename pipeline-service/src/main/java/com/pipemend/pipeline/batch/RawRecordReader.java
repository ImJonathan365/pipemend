package com.pipemend.pipeline.batch;

import com.pipemend.pipeline.validation.Whitespace;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.batch.infrastructure.item.file.mapping.PassThroughLineMapper;
import org.springframework.batch.infrastructure.item.file.separator.SimpleRecordSeparatorPolicy;
import org.springframework.core.io.Resource;

/**
 * Reads a UTF-8, comma-separated, strict RFC 4180 CSV with a header row (FR-01). Never throws because of a record's
 * content: a wrong column count or a quote in the middle of an unquoted field becomes {@link RawRecord.Malformed}.
 * Restartable: the position of the last committed record lives in the step's {@link ExecutionContext} (NFR-04).
 */
public final class RawRecordReader implements ItemStreamReader<RawRecord> {

    private static final char BOM = (char) 0xFEFF;
    private static final char QUOTE = '"';
    private static final char COMMA = ',';

    private final FlatFileItemReader<String> records;
    private @Nullable List<String> headers;

    public RawRecordReader(Resource source) {
        records = new FlatFileItemReader<>(source, new PassThroughLineMapper());
        records.setName("rawRecordReader");
        // The defaults would drop data lines starting with "#" and join a line ending in "\" to the next one.
        records.setComments(new String[0]);
        records.setRecordSeparatorPolicy(new QuotedRecordPolicy());
        records.setBufferedReaderFactory((resource, encoding) -> open(resource));
        records.setLinesToSkip(1);
        records.setSkippedLinesCallback(this::readHeader);
    }

    @Override
    public void open(ExecutionContext context) {
        headers = null;
        try {
            records.open(context);
        } catch (ItemStreamException e) {
            throw translate(e);
        }
        if (headers == null) {
            throw new InvalidFileFormatException("the file has no header row");
        }
    }

    @Override
    public @Nullable RawRecord read() throws Exception {
        String line;
        try {
            line = records.read();
        } catch (RuntimeException e) {
            throw translate(e);
        }
        if (line == null) {
            return null;
        }
        int number = records.getCurrentItemCount();
        Split split = split(line);
        if (split.status() == Status.STRAY_QUOTE) {
            return new RawRecord.Malformed(number, line, "A quote appears inside a field that does not start with one");
        }
        List<String> names = headers();
        if (split.fields().size() != names.size()) {
            return new RawRecord.Malformed(
                    number,
                    line,
                    "Expected " + names.size() + " columns, found "
                            + split.fields().size());
        }
        Map<String, String> byHeader = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            byHeader.put(names.get(i), split.fields().get(i));
        }
        return new RawRecord.Parsed(number, byHeader);
    }

    @Override
    public void update(ExecutionContext context) {
        records.update(context);
    }

    @Override
    public void close() {
        records.close();
    }

    /** The header row, available once the reader is open. */
    public List<String> headers() {
        if (headers == null) {
            throw new IllegalStateException("the reader is not open");
        }
        return headers;
    }

    private void readHeader(String line) {
        Split split = split(line);
        // OPEN_QUOTE cannot reach here: skipped lines also go through the record separator policy (D69).
        if (split.status() != Status.COMPLETE) {
            throw new InvalidFileFormatException("the header row has a quote that does not open or close a name");
        }
        if (split.fields().isEmpty()) {
            throw new InvalidFileFormatException("the header row is empty");
        }
        Set<String> seen = new HashSet<>();
        for (String name : split.fields()) {
            if (Whitespace.strip(name).isEmpty()) {
                throw new InvalidFileFormatException("the header row has an empty column name");
            }
            if (!seen.add(name)) {
                throw new InvalidFileFormatException("the header row repeats the column name '" + name + "'");
            }
        }
        headers = split.fields();
    }

    private enum Status {
        COMPLETE,
        OPEN_QUOTE,
        STRAY_QUOTE
    }

    private record Split(List<String> fields, Status status) {}

    /**
     * Splits one record by RFC 4180. A quote opens a quoted field only as the first character of a field; anywhere
     * else, or after a closing quote that is not followed by a comma, it is a stray quote. Both the record boundary
     * and the columns come from here, so a stray quote can never join physical lines.
     */
    private static Split split(String record) {
        List<String> fields = new ArrayList<>();
        // D60: an empty line has no columns at all.
        if (record.isEmpty()) {
            return new Split(fields, Status.COMPLETE);
        }
        StringBuilder field = new StringBuilder();
        int i = 0;
        while (true) {
            if (i < record.length() && record.charAt(i) == QUOTE) {
                i++;
                while (true) {
                    if (i == record.length()) {
                        return new Split(fields, Status.OPEN_QUOTE);
                    }
                    char c = record.charAt(i++);
                    if (c != QUOTE) {
                        field.append(c);
                    } else if (i < record.length() && record.charAt(i) == QUOTE) {
                        field.append(QUOTE);
                        i++;
                    } else {
                        break;
                    }
                }
                if (i < record.length() && record.charAt(i) != COMMA) {
                    return new Split(fields, Status.STRAY_QUOTE);
                }
            } else {
                while (i < record.length() && record.charAt(i) != COMMA) {
                    if (record.charAt(i) == QUOTE) {
                        return new Split(fields, Status.STRAY_QUOTE);
                    }
                    field.append(record.charAt(i++));
                }
            }
            fields.add(field.toString());
            field.setLength(0);
            if (i == record.length()) {
                return new Split(fields, Status.COMPLETE);
            }
            i++;
        }
    }

    private static BufferedReader open(Resource resource) throws IOException {
        CharsetDecoder utf8 = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), utf8));
        // Excel prepends a BOM; left in place it would turn the first header into "<BOM>Invoice" (AC-01.7).
        reader.mark(1);
        if (reader.read() != BOM) {
            reader.reset();
        }
        return reader;
    }

    private static RuntimeException translate(RuntimeException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidFileFormatException invalid) {
                return invalid;
            }
            if (cause instanceof CharacterCodingException) {
                return new InvalidFileFormatException("the file is not valid UTF-8", e);
            }
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            // Raised only while assembling a record (an unclosed quote), since the line mapper never fails.
            if (cause instanceof FlatFileParseException parse) {
                return new InvalidFileFormatException(
                        "a record cannot be delimited at line " + parse.getLineNumber() + ": " + parse.getMessage(), e);
            }
        }
        return e;
    }

    /** A record goes on to the next line only while a quoted field is open; a line break inside it stays "\n". */
    private static final class QuotedRecordPolicy extends SimpleRecordSeparatorPolicy {

        @Override
        public boolean isEndOfRecord(String record) {
            return split(record).status() != Status.OPEN_QUOTE;
        }

        @Override
        public String preProcess(String record) {
            return isEndOfRecord(record) ? record : record + "\n";
        }
    }
}
