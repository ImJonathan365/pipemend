package com.pipemend.pipeline.validation;

public final class Text {

    private Text() {}

    /**
     * Cuts by code points, as ai-service counts them. By {@code char} a cut could split a surrogate pair, and a lone
     * surrogate cannot be encoded in UTF-8.
     */
    public static String truncate(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}
