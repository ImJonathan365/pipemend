package com.pipemend.pipeline.batch;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/** One logical CSV record as read, before any validation. {@code sourceRowNumber} is 1-based without the header. */
public sealed interface RawRecord {

    int sourceRowNumber();

    /** @param valuesByHeader raw values keyed by header, in file order */
    record Parsed(int sourceRowNumber, Map<String, String> valuesByHeader) implements RawRecord {}

    /**
     * A record with the wrong column count or a stray quote (AC-01.5); kept verbatim, never padded, cut or joined.
     *
     * @param expectedColumns the columns of the header
     * @param receivedColumns null when a stray quote leaves the line without columns to count (D96)
     */
    record Malformed(
            int sourceRowNumber,
            String rawLine,
            String reason,
            int expectedColumns,
            @Nullable Integer receivedColumns) implements RawRecord {}
}
