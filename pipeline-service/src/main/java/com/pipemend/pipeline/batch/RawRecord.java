package com.pipemend.pipeline.batch;

import java.util.Map;

/** One logical CSV record as read, before any validation. {@code sourceRowNumber} is 1-based without the header. */
public sealed interface RawRecord {

    int sourceRowNumber();

    /** @param valuesByHeader raw values keyed by header, in file order */
    record Parsed(int sourceRowNumber, Map<String, String> valuesByHeader) implements RawRecord {}

    /** A record whose column count differs from the header's (AC-01.5); kept verbatim, never padded or cut. */
    record Malformed(int sourceRowNumber, String rawLine, int expectedColumns, int actualColumns)
            implements RawRecord {}
}
