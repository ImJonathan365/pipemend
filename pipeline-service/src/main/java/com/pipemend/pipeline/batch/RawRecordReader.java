package com.pipemend.pipeline.batch;

import com.pipemend.pipeline.validation.Whitespace;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
import org.springframework.batch.infrastructure.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.infrastructure.item.file.transform.IncorrectTokenCountException;
import org.springframework.core.io.Resource;

/**
 * Reads a UTF-8, comma-separated, RFC 4180 CSV with a header row (FR-01). Never throws because of a record's content:
 * a wrong column count becomes {@link RawRecord.Malformed}. Restartable: the position of the last committed record
 * lives in the step's {@link ExecutionContext} (NFR-04).
 */
public final class RawRecordReader implements ItemStreamReader<RawRecord> {

    private static final char BOM = '﻿';

    private final FlatFileItemReader<String> records;
    private final DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
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
        List<String> values;
        try {
            values = List.of(tokenizer.tokenize(line).getValues());
        } catch (IncorrectTokenCountException e) {
            return new RawRecord.Malformed(number, line, e.getExpectedCount(), e.getActualCount());
        }
        Map<String, String> byHeader = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            byHeader.put(headers().get(i), values.get(i));
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
        List<String> names = List.of(new DelimitedLineTokenizer().tokenize(line).getValues());
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (Whitespace.strip(name).isEmpty()) {
                throw new InvalidFileFormatException("the header row has an empty column name");
            }
            if (!seen.add(name)) {
                throw new InvalidFileFormatException("the header row repeats the column name '" + name + "'");
            }
        }
        headers = names;
        tokenizer.setNames(names.toArray(String[]::new));
    }

    private static BufferedReader open(Resource resource) throws IOException {
        CharsetDecoder utf8 = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), utf8));
        // Excel prepends a BOM; left in place it would turn the first header into "﻿Invoice" (AC-01.7).
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

    /**
     * A record ends when its quotes are balanced. Unlike the default policy, a trailing backslash means nothing, and
     * a line break inside quotes is kept as "\n".
     */
    private static final class QuotedRecordPolicy extends SimpleRecordSeparatorPolicy {

        @Override
        public boolean isEndOfRecord(String record) {
            return record.chars().filter(c -> c == '"').count() % 2 == 0;
        }

        @Override
        public String preProcess(String record) {
            return isEndOfRecord(record) ? record : record + "\n";
        }
    }
}
