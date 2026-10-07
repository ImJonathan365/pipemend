package com.pipemend.pipeline.validation;

/**
 * Whitespace as docs/02 section 2 defines it: only U+0020 and tab. {@link String#strip()} would also remove other
 * Unicode spaces, and NBSP must stay so that the record goes to quarantine.
 */
public final class Whitespace {

    private Whitespace() {}

    public static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }
}
