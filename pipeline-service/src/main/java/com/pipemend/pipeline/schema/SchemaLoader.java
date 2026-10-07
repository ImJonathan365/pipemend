package com.pipemend.pipeline.schema;

import com.pipemend.pipeline.schema.FieldRule.DateTimeRule;
import com.pipemend.pipeline.schema.FieldRule.DecimalRule;
import com.pipemend.pipeline.schema.FieldRule.EnumRule;
import com.pipemend.pipeline.schema.FieldRule.IntegerRule;
import com.pipemend.pipeline.schema.FieldRule.StringRule;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads the declarative schema (docs/02 section 2). Any key or value it does not understand fails the load, so a
 * rule can never be ignored silently and a broken YAML stops the application at startup (AC-06.5).
 */
public final class SchemaLoader {

    /** Implemented in code by the validator: the YAML only carries their description. */
    public static final Set<String> BUSINESS_RULES = Set.of("BR-01", "BR-02");

    private static final Set<String> SCHEMA_KEYS =
            Set.of("name", "version", "nullTokens", "fields", "businessRules", "derived");
    private static final Set<String> COMMON_KEYS = Set.of("name", "sourceHeader", "type", "required");
    private static final Map<String, Set<String>> TYPE_KEYS = Map.of(
            "string", Set.of("pattern", "maxLength", "caseSensitive"),
            "integer", Set.of("min", "max", "notEqualTo"),
            "decimal", Set.of("scale", "decimalSeparator", "min", "max"),
            "datetime", Set.of("format", "min", "max"),
            "enum", Set.of("catalogue", "caseSensitive"));

    private SchemaLoader() {}

    /**
     * @param yaml the schema document
     * @param readSibling returns the content of a file next to the schema (the country catalogue)
     */
    public static SchemaDefinition load(String yaml, Function<String, String> readSibling) {
        Map<String, Object> root = map(parse(yaml), "schema");
        unknownKeys(root, SCHEMA_KEYS, "schema");

        List<FieldSpec> fields = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object entry : list(root.get("fields"), "fields")) {
            FieldSpec field = field(map(entry, "field"), readSibling);
            if (!names.add(field.name())) {
                throw new SchemaLoadException("duplicate field " + field.name());
            }
            fields.add(field);
        }
        requireType(fields, "invoice_no", StringRule.class);
        requireType(fields, "quantity", IntegerRule.class);

        Set<String> rules = new HashSet<>();
        for (Object entry : list(root.get("businessRules"), "businessRules")) {
            rules.add(string(map(entry, "business rule"), "code", "business rule"));
        }
        if (!rules.equals(BUSINESS_RULES)) {
            throw new SchemaLoadException("business rules " + rules + " differ from the implemented " + BUSINESS_RULES);
        }

        Set<String> nullTokens = new LinkedHashSet<>();
        for (Object token : list(root.get("nullTokens"), "nullTokens")) {
            if (!(token instanceof String text)) {
                throw new SchemaLoadException("null tokens must be strings, found " + token);
            }
            nullTokens.add(text);
        }

        return new SchemaDefinition(
                string(root, "name", "schema"),
                integer(root, "version", "schema"),
                Set.copyOf(nullTokens),
                List.copyOf(fields));
    }

    private static Object parse(String yaml) {
        try {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (YAMLException e) {
            throw new SchemaLoadException("invalid schema YAML: " + e.getMessage(), e);
        }
    }

    private static FieldSpec field(Map<String, Object> spec, Function<String, String> readSibling) {
        String name = string(spec, "name", "field");
        String type = string(spec, "type", name);
        Set<String> allowed = TYPE_KEYS.get(type);
        if (allowed == null) {
            throw new SchemaLoadException(name + ": unsupported type " + type);
        }
        Set<String> keys = new HashSet<>(COMMON_KEYS);
        keys.addAll(allowed);
        unknownKeys(spec, keys, name);
        if (spec.containsKey("caseSensitive") && !Boolean.TRUE.equals(spec.get("caseSensitive"))) {
            throw new SchemaLoadException(name + ": only caseSensitive: true is supported");
        }

        FieldRule rule = switch (type) {
            case "string" -> stringRule(spec, name);
            case "integer" ->
                new IntegerRule(
                        integer(spec, "min", name),
                        integer(spec, "max", name),
                        spec.containsKey("notEqualTo") ? (long) integer(spec, "notEqualTo", name) : null);
            case "decimal" -> decimalRule(spec, name);
            case "datetime" -> dateTimeRule(spec, name);
            default -> enumRule(spec, name, readSibling);
        };
        Object required = spec.get("required");
        if (!(required instanceof Boolean flag)) {
            throw new SchemaLoadException(name + ": required must be true or false");
        }
        return new FieldSpec(name, string(spec, "sourceHeader", name), flag, rule);
    }

    private static StringRule stringRule(Map<String, Object> spec, String name) {
        Pattern pattern = null;
        if (spec.containsKey("pattern")) {
            try {
                pattern = Pattern.compile(string(spec, "pattern", name));
            } catch (PatternSyntaxException e) {
                throw new SchemaLoadException(name + ": invalid pattern", e);
            }
        }
        Integer maxLength = spec.containsKey("maxLength") ? integer(spec, "maxLength", name) : null;
        return new StringRule(pattern, maxLength);
    }

    private static DecimalRule decimalRule(Map<String, Object> spec, String name) {
        if (!".".equals(spec.get("decimalSeparator"))) {
            throw new SchemaLoadException(name + ": only decimalSeparator '.' is supported");
        }
        int scale = integer(spec, "scale", name);
        if (scale < 1) {
            throw new SchemaLoadException(name + ": scale must be positive");
        }
        return new DecimalRule(scale, decimal(spec, "min", name), decimal(spec, "max", name));
    }

    private static DateTimeRule dateTimeRule(Map<String, Object> spec, String name) {
        String format = string(spec, "format", name);
        DateTimeFormatter formatter;
        try {
            // With STRICT, "yyyy" (year-of-era) needs an era to resolve; "uuuu" does not (docs/02 section 4).
            formatter = DateTimeFormatter.ofPattern(format.replace('y', 'u')).withResolverStyle(ResolverStyle.STRICT);
        } catch (IllegalArgumentException e) {
            throw new SchemaLoadException(name + ": invalid format " + format, e);
        }
        LocalDateTime min = dateTime(string(spec, "min", name), formatter, name);
        String max = string(spec, "max", name);
        return new DateTimeRule(format, formatter, min, "now".equals(max) ? null : dateTime(max, formatter, name));
    }

    private static EnumRule enumRule(Map<String, Object> spec, String name, Function<String, String> readSibling) {
        String catalogue = string(spec, "catalogue", name);
        List<String> lines = readSibling
                .apply(catalogue)
                .lines()
                .filter(line -> !line.isEmpty())
                .toList();
        Set<String> values = Set.copyOf(lines);
        if (values.isEmpty() || values.size() != lines.size()) {
            throw new SchemaLoadException(name + ": catalogue " + catalogue + " is empty or has duplicates");
        }
        return new EnumRule(catalogue.replaceFirst("\\.txt$", ""), values);
    }

    private static void requireType(List<FieldSpec> fields, String name, Class<? extends FieldRule> type) {
        boolean present = fields.stream().anyMatch(f -> f.name().equals(name) && type.isInstance(f.rule()));
        if (!present) {
            throw new SchemaLoadException("business rules need field " + name + " of rule " + type.getSimpleName());
        }
    }

    private static void unknownKeys(Map<String, Object> map, Set<String> allowed, String where) {
        Set<String> unknown = new HashSet<>(map.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new SchemaLoadException(where + ": unknown keys " + unknown);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String where) {
        if (!(value instanceof Map<?, ?> map) || !map.keySet().stream().allMatch(String.class::isInstance)) {
            throw new SchemaLoadException(where + " must be a mapping");
        }
        return (Map<String, Object>) map;
    }

    private static List<?> list(Object value, String where) {
        if (!(value instanceof List<?> list)) {
            throw new SchemaLoadException(where + " must be a list");
        }
        return list;
    }

    private static String string(Map<String, Object> map, String key, String where) {
        if (!(map.get(key) instanceof String text) || text.isEmpty()) {
            throw new SchemaLoadException(where + ": " + key + " must be a non-empty string");
        }
        return text;
    }

    private static int integer(Map<String, Object> map, String key, String where) {
        if (!(map.get(key) instanceof Integer number)) {
            throw new SchemaLoadException(where + ": " + key + " must be an integer");
        }
        return number;
    }

    private static BigDecimal decimal(Map<String, Object> map, String key, String where) {
        Object value = map.get(key);
        if (!(value instanceof Integer || value instanceof Double)) {
            throw new SchemaLoadException(where + ": " + key + " must be a number");
        }
        return new BigDecimal(value.toString());
    }

    private static LocalDateTime dateTime(String value, DateTimeFormatter formatter, String where) {
        try {
            return LocalDateTime.parse(value, formatter);
        } catch (DateTimeParseException e) {
            throw new SchemaLoadException(where + ": " + value + " does not match the field format", e);
        }
    }
}
