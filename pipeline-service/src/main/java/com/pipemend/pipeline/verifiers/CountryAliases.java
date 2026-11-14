package com.pipemend.pipeline.verifiers;

import com.pipemend.pipeline.schema.FieldRule.EnumRule;
import com.pipemend.pipeline.schema.SchemaLoadException;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * schemas/country-aliases.v1.yaml, read only. It fails to load, and so the application fails to start, when a country
 * is not in the catalogue, an alias normalizes to two countries or to nothing: an ambiguous alias would accept two
 * readings (ADR-0008).
 */
public final class CountryAliases {

    private final Set<String> catalogue;
    private final Map<String, String> countryByAlias;

    private CountryAliases(Set<String> catalogue, Map<String, String> countryByAlias) {
        this.catalogue = catalogue;
        this.countryByAlias = countryByAlias;
    }

    public static CountryAliases load(String yaml, EnumRule country) {
        Map<?, ?> root = asMap(parse(yaml), "the document");
        // The schema keeps the catalogue name without its extension.
        if (!(country.catalogueName() + ".txt").equals(root.get("catalogue"))) {
            throw new SchemaLoadException(
                    "country aliases: catalogue " + root.get("catalogue") + " is not " + country.catalogueName());
        }
        Map<String, String> countryByAlias = new HashMap<>();
        asMap(root.get("aliases"), "aliases").forEach((name, aliases) -> {
            if (!(name instanceof String countryName) || !country.values().contains(countryName)) {
                throw new SchemaLoadException("country aliases: " + name + " is not in " + country.catalogueName());
            }
            if (!(aliases instanceof List<?> list)) {
                throw new SchemaLoadException("country aliases: " + countryName + " needs a list of aliases");
            }
            for (Object alias : list) {
                if (!(alias instanceof String text)) {
                    throw new SchemaLoadException("country aliases: " + countryName + " has an alias that is not text");
                }
                String normalized = normalize(text);
                // An alias in another script normalizes to nothing, and "?" or "--" would then map to its country.
                if (normalized.isEmpty()) {
                    throw new SchemaLoadException(
                            "country aliases: an alias of " + countryName + " normalizes to an empty value");
                }
                String previous = countryByAlias.putIfAbsent(normalized, countryName);
                if (previous != null && !previous.equals(countryName)) {
                    throw new SchemaLoadException(
                            "country aliases: " + text + " is ambiguous between " + previous + " and " + countryName);
                }
            }
        });
        return new CountryAliases(country.values(), Map.copyOf(countryByAlias));
    }

    /**
     * docs/02 section 4: lowercase with Locale.ROOT, accents removed, and only [a-z0-9] kept. NFD splits an accented
     * letter into the letter and its mark, and the mark goes with the rest of [^a-z0-9].
     */
    public static String normalize(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("[^a-z0-9]", "");
    }

    public Set<String> catalogue() {
        return catalogue;
    }

    /** @param normalizedAlias already passed through {@link #normalize} */
    public boolean maps(String normalizedAlias, String country) {
        return country.equals(countryByAlias.get(normalizedAlias));
    }

    int size() {
        return countryByAlias.size();
    }

    private static Object parse(String yaml) {
        try {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (YAMLException e) {
            throw new SchemaLoadException("invalid country aliases YAML: " + e.getMessage(), e);
        }
    }

    private static Map<?, ?> asMap(Object value, String what) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new SchemaLoadException("country aliases: " + what + " must be a mapping");
        }
        return map;
    }
}
