package com.pipemend.pipeline.schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.regex.Pattern;

/** Type-specific rules of a field, as declared in the schema YAML. */
public sealed interface FieldRule {

    record StringRule(Pattern pattern, Integer maxLength) implements FieldRule {}

    record IntegerRule(long min, long max, Long notEqualTo) implements FieldRule {}

    record DecimalRule(int scale, BigDecimal min, BigDecimal max) implements FieldRule {}

    /** {@code max == null} means "now", read from the injected clock. */
    record DateTimeRule(String format, DateTimeFormatter formatter, LocalDateTime min, LocalDateTime max)
            implements FieldRule {}

    record EnumRule(String catalogueName, Set<String> values) implements FieldRule {}
}
