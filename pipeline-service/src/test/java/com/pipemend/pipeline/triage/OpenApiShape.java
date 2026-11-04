package com.pipemend.pipeline.triage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks JSON against a schema of contracts/ai-service.openapi.json, with only the keywords that spec uses. Pydantic
 * generated it from the models that answer 422, so what passes here is what ai-service accepts. No new dependency.
 */
final class OpenApiShape {

    static final Path CONTRACTS = Path.of("..", "contracts");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> KNOWN = Set.of(
            "$ref",
            "anyOf",
            "type",
            "enum",
            "properties",
            "required",
            "additionalProperties",
            "items",
            "minItems",
            "maxItems",
            "minLength",
            "maxLength",
            "minimum",
            "format",
            "title",
            "description");

    private final JsonNode schemas;

    OpenApiShape() throws Exception {
        schemas = JSON.readTree(Files.readString(CONTRACTS.resolve("ai-service.openapi.json")))
                .get("components")
                .get("schemas");
    }

    JsonNode schema(String name) {
        return schemas.get(name);
    }

    List<String> errors(JsonNode value, String schemaName) {
        List<String> errors = new ArrayList<>();
        check(value, schema(schemaName), schemaName, errors);
        return errors;
    }

    private void check(JsonNode value, JsonNode schema, String path, List<String> errors) {
        for (String keyword : schema.propertyNames()) {
            if (!KNOWN.contains(keyword)) {
                throw new IllegalStateException("keyword " + keyword + " is not implemented, at " + path);
            }
        }
        if (schema.has("$ref")) {
            String name = schema.get("$ref").asString().replace("#/components/schemas/", "");
            check(value, schema(name), path, errors);
            return;
        }
        if (schema.has("anyOf")) {
            boolean matches = StreamSupport.stream(schema.get("anyOf").spliterator(), false)
                    .anyMatch(option -> {
                        List<String> found = new ArrayList<>();
                        check(value, option, path, found);
                        return found.isEmpty();
                    });
            if (!matches) {
                errors.add(path + ": matches no option of " + schema.get("anyOf"));
            }
            return;
        }
        if (schema.has("type") && !hasType(value, schema.get("type").asString())) {
            errors.add(path + ": " + value.getNodeType() + " is not "
                    + schema.get("type").asString());
            return;
        }
        if (schema.has("enum")
                && StreamSupport.stream(schema.get("enum").spliterator(), false).noneMatch(value::equals)) {
            errors.add(path + ": " + value + " is not in the enum");
        }
        if (value.isString()) {
            String text = value.asString();
            int length = text.codePointCount(0, text.length());
            if (schema.has("maxLength") && length > schema.get("maxLength").asInt()) {
                errors.add(path + ": longer than " + schema.get("maxLength"));
            }
            if (schema.has("minLength") && length < schema.get("minLength").asInt()) {
                errors.add(path + ": shorter than " + schema.get("minLength"));
            }
        }
        if (value.isNumber()
                && schema.has("minimum")
                && value.asDouble() < schema.get("minimum").asDouble()) {
            errors.add(path + ": below " + schema.get("minimum"));
        }
        if (value.isArray()) {
            if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) {
                errors.add(path + ": more items than " + schema.get("maxItems"));
            }
            if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) {
                errors.add(path + ": fewer items than " + schema.get("minItems"));
            }
            int i = 0;
            for (JsonNode item : value) {
                check(item, schema.get("items"), path + "[" + i++ + "]", errors);
            }
        }
        if (value.isObject()) {
            checkObject(value, schema, path, errors);
        }
    }

    private void checkObject(JsonNode value, JsonNode schema, String path, List<String> errors) {
        if (schema.has("required")) {
            for (JsonNode required : schema.get("required")) {
                if (!value.has(required.asString())) {
                    errors.add(path + ": missing " + required.asString());
                }
            }
        }
        JsonNode additional = schema.get("additionalProperties");
        for (String key : value.propertyNames()) {
            String child = path + "." + key;
            if (schema.has("properties") && schema.get("properties").has(key)) {
                check(value.get(key), schema.get("properties").get(key), child, errors);
            } else if (additional != null && additional.isObject()) {
                check(value.get(key), additional, child, errors);
            } else if (additional != null && !additional.asBoolean()) {
                errors.add(child + ": unknown key");
            }
        }
    }

    private static boolean hasType(JsonNode value, String type) {
        return switch (type) {
            case "string" -> value.isString();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            case "null" -> value.isNull();
            default -> throw new IllegalStateException("type " + type + " is not implemented");
        };
    }
}
