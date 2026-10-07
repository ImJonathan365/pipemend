package com.pipemend.pipeline.validation;

import com.pipemend.pipeline.schema.FieldRule.DateTimeRule;
import com.pipemend.pipeline.schema.FieldRule.DecimalRule;
import com.pipemend.pipeline.schema.FieldRule.EnumRule;
import com.pipemend.pipeline.schema.FieldRule.IntegerRule;
import com.pipemend.pipeline.schema.FieldRule.StringRule;
import com.pipemend.pipeline.schema.FieldSpec;
import com.pipemend.pipeline.schema.SchemaDefinition;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates one record against the schema (FR-06), following the emission rules of docs/02 section 3.1. Pure: no
 * I/O and no Spring, so the same instance serves the first validation and the revalidation of FR-10.
 */
public final class Validator {

    public static final String RECORD = "_record";

    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final Pattern CANONICAL_INTEGER = Pattern.compile("-?\\d+");
    private static final String CURRENCY_SYMBOLS = "£$€";
    // Digits with one decimal separator, or groups of three split by a separator other than the decimal one.
    // "2.5.5" is neither, which is why docs/02 section 3.1 lists it as INVALID_TYPE.
    private static final Pattern NUMBER_SHAPE =
            Pattern.compile("\\d+(?:[.,]\\d+)?|\\d{1,3}(?<g>[., ])\\d{3}(?:\\k<g>\\d{3})*(?:(?!\\k<g>)[.,]\\d+)?");
    private static final Pattern DATE_SHAPE = Pattern.compile("(?=(?:\\D*\\d){6})[\\d/\\-.:T ]+");

    private final SchemaDefinition schema;
    private final Clock clock;
    private final Map<String, Map<String, Object>> expected = new HashMap<>();
    private final Map<String, Pattern> canonicalDecimals = new HashMap<>();

    public Validator(SchemaDefinition schema, Clock clock) {
        this.schema = schema;
        this.clock = clock;
        for (FieldSpec field : schema.fields()) {
            expected.put(field.name(), expectedFor(field));
            if (field.rule() instanceof DecimalRule rule) {
                canonicalDecimals.put(field.name(), Pattern.compile("-?\\d+(?:\\.\\d{1," + rule.scale() + "})?"));
            }
        }
    }

    /**
     * @param values canonical field name to raw value; an absent key or a {@code null} value means "no value"
     * @return every violation of the record (AC-06.2), at most one per field
     */
    public List<Violation> validate(Map<String, String> values) {
        List<Violation> violations = new ArrayList<>();
        Set<String> invalid = new HashSet<>();
        for (FieldSpec field : schema.fields()) {
            check(field, values.get(field.name())).ifPresent(violation -> {
                violations.add(violation);
                invalid.add(field.name());
            });
        }
        if (!invalid.contains("invoice_no") && !invalid.contains("quantity")) {
            checkBusinessRules(values, violations);
        }
        return List.copyOf(violations);
    }

    private Optional<Violation> check(FieldSpec field, String value) {
        if (value == null) {
            return field.required() ? Optional.of(missing(field, null)) : Optional.empty();
        }
        if (schema.nullTokens().contains(Whitespace.strip(value))) {
            if (field.required()) {
                return Optional.of(missing(field, value));
            }
            // In an optional field only the empty string is null; other tokens are validated as text.
            if (value.isEmpty()) {
                return Optional.empty();
            }
        }
        return switch (field.rule()) {
            case StringRule rule -> checkString(field, rule, value);
            case IntegerRule rule -> checkInteger(field, rule, value);
            case DecimalRule rule -> checkDecimal(field, rule, value);
            case DateTimeRule rule -> checkDateTime(field, rule, value);
            case EnumRule rule -> checkEnum(field, rule, value);
        };
    }

    private Optional<Violation> checkString(FieldSpec field, StringRule rule, String value) {
        if (rule.pattern() != null && !rule.pattern().matcher(value).matches()) {
            return violation(
                    field, ErrorCode.PATTERN_MISMATCH, value, "Value does not match pattern " + rule.pattern(), null);
        }
        if (rule.maxLength() != null && value.length() > rule.maxLength()) {
            return violation(
                    field,
                    ErrorCode.MAX_LENGTH_EXCEEDED,
                    value,
                    "Length " + value.length() + " exceeds the maximum of " + rule.maxLength(),
                    null);
        }
        return Optional.empty();
    }

    private Optional<Violation> checkInteger(FieldSpec field, IntegerRule rule, String value) {
        if (!CANONICAL_INTEGER.matcher(value).matches()) {
            RuntimeException failure = parseFailure(() -> Long.parseLong(value));
            return notParsed(field, value, numberShape(Whitespace.strip(value), false), "an integer", failure);
        }
        BigInteger number = new BigInteger(value);
        boolean inRange = number.compareTo(BigInteger.valueOf(rule.min())) >= 0
                && number.compareTo(BigInteger.valueOf(rule.max())) <= 0;
        if (!inRange) {
            return outOfRange(field, value, "[" + rule.min() + ", " + rule.max() + "]");
        }
        if (rule.notEqualTo() != null && number.longValueExact() == rule.notEqualTo()) {
            return violation(field, ErrorCode.OUT_OF_RANGE, value, "Value " + value + " is not allowed", null);
        }
        return Optional.empty();
    }

    private Optional<Violation> checkDecimal(FieldSpec field, DecimalRule rule, String value) {
        if (!canonicalDecimals.get(field.name()).matcher(value).matches()) {
            RuntimeException failure = parseFailure(() -> new BigDecimal(value));
            String kind = "a decimal with '.' and at most " + rule.scale() + " decimals";
            return notParsed(field, value, numberShape(Whitespace.strip(value), true), kind, failure);
        }
        BigDecimal number = new BigDecimal(value);
        if (number.compareTo(rule.min()) < 0 || number.compareTo(rule.max()) > 0) {
            return outOfRange(
                    field,
                    value,
                    "[" + rule.min().toPlainString() + ", " + rule.max().toPlainString() + "]");
        }
        return Optional.empty();
    }

    private Optional<Violation> checkDateTime(FieldSpec field, DateTimeRule rule, String value) {
        LocalDateTime moment;
        try {
            moment = LocalDateTime.parse(value, rule.formatter());
        } catch (DateTimeParseException e) {
            boolean format = DATE_SHAPE.matcher(Whitespace.strip(value)).matches();
            ErrorCode code = format ? ErrorCode.INVALID_FORMAT : ErrorCode.INVALID_TYPE;
            return violation(field, code, value, e.getMessage(), e.getClass().getName());
        }
        LocalDateTime max = rule.max() != null ? rule.max() : LocalDateTime.now(clock);
        if (moment.isBefore(rule.min()) || moment.isAfter(max)) {
            return outOfRange(field, value, "[" + rule.min() + ", " + max + "]");
        }
        return Optional.empty();
    }

    private Optional<Violation> checkEnum(FieldSpec field, EnumRule rule, String value) {
        if (rule.values().contains(value)) {
            return Optional.empty();
        }
        String message = "Value '" + value + "' is not in catalog " + rule.catalogueName();
        return violation(field, ErrorCode.INVALID_ENUM_VALUE, value, message, null);
    }

    private void checkBusinessRules(Map<String, String> values, List<Violation> violations) {
        String invoice = values.get("invoice_no");
        long quantity = Long.parseLong(values.get("quantity"));
        boolean cancellation = invoice.startsWith("C");
        if (cancellation && quantity >= 0) {
            violations.add(
                    businessRule("BR-01", "invoice " + invoice + " is a cancellation but quantity is " + quantity));
        }
        if (!cancellation && quantity <= 0) {
            violations.add(
                    businessRule("BR-02", "invoice " + invoice + " is not a cancellation but quantity is " + quantity));
        }
    }

    private static Violation businessRule(String rule, String detail) {
        return new Violation(
                RECORD,
                null,
                ErrorCode.BUSINESS_RULE_VIOLATION,
                null,
                Map.of("rule", rule),
                rule + ": " + detail,
                null);
    }

    /** INVALID_FORMAT vs INVALID_TYPE for numbers (docs/02 section 3.1, rule 3); applied to the stripped value. */
    private static boolean numberShape(String stripped, boolean currencyAllowed) {
        String text = stripped;
        boolean signed = startsWithSign(text);
        if (signed) {
            text = text.substring(1);
        }
        if (currencyAllowed && !text.isEmpty()) {
            if (CURRENCY_SYMBOLS.indexOf(text.charAt(0)) >= 0) {
                text = text.substring(1);
            } else if (CURRENCY_SYMBOLS.indexOf(text.charAt(text.length() - 1)) >= 0) {
                text = text.substring(0, text.length() - 1);
            }
            if (!signed && startsWithSign(text)) {
                text = text.substring(1);
            }
            text = Whitespace.strip(text);
        }
        return NUMBER_SHAPE.matcher(text).matches();
    }

    private static boolean startsWithSign(String text) {
        return text.startsWith("-") || text.startsWith("+");
    }

    private Optional<Violation> notParsed(
            FieldSpec field, String value, boolean format, String kind, RuntimeException failure) {
        ErrorCode code = format ? ErrorCode.INVALID_FORMAT : ErrorCode.INVALID_TYPE;
        if (failure != null) {
            return violation(
                    field, code, value, failure.getMessage(), failure.getClass().getName());
        }
        return violation(field, code, value, "Value is not " + kind + " in canonical form", null);
    }

    private static RuntimeException parseFailure(Runnable parse) {
        try {
            parse.run();
            return null;
        } catch (NumberFormatException e) {
            return e;
        }
    }

    private Optional<Violation> outOfRange(FieldSpec field, String value, String range) {
        return violation(field, ErrorCode.OUT_OF_RANGE, value, "Value " + value + " is outside " + range, null);
    }

    private Violation missing(FieldSpec field, String value) {
        return violation(
                        field, ErrorCode.MISSING_REQUIRED_FIELD, value, "Required field is empty or a null token", null)
                .orElseThrow();
    }

    private Optional<Violation> violation(
            FieldSpec field, ErrorCode code, String value, String message, String exceptionType) {
        String technical = message == null ? code.name() : message;
        if (technical.length() > MAX_MESSAGE_LENGTH) {
            technical = technical.substring(0, MAX_MESSAGE_LENGTH);
        }
        return Optional.of(new Violation(
                field.name(), field.sourceHeader(), code, value, expected.get(field.name()), technical, exceptionType));
    }

    private static Map<String, Object> expectedFor(FieldSpec field) {
        Map<String, Object> keys = new LinkedHashMap<>();
        switch (field.rule()) {
            case StringRule rule -> {
                keys.put("type", "string");
                if (rule.pattern() != null) {
                    keys.put("pattern", rule.pattern().pattern());
                }
                if (rule.maxLength() != null) {
                    keys.put("maxLength", rule.maxLength());
                }
            }
            case IntegerRule rule -> {
                keys.put("type", "integer");
                keys.put("min", Long.toString(rule.min()));
                keys.put("max", Long.toString(rule.max()));
            }
            case DecimalRule rule -> {
                keys.put("type", "decimal");
                keys.put("scale", rule.scale());
                keys.put("min", rule.min().toPlainString());
                keys.put("max", rule.max().toPlainString());
                keys.put("decimalSeparator", ".");
            }
            case DateTimeRule rule -> {
                keys.put("type", "datetime");
                keys.put("format", rule.format());
                keys.put("min", rule.min().format(rule.formatter()));
                if (rule.max() != null) {
                    keys.put("max", rule.max().format(rule.formatter()));
                }
            }
            case EnumRule rule -> {
                keys.put("type", "enum");
                keys.put("allowedValues", rule.values().stream().sorted().toList());
            }
        }
        return Collections.unmodifiableMap(keys);
    }
}
