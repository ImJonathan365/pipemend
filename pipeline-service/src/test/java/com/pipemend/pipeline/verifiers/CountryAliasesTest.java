package com.pipemend.pipeline.verifiers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pipemend.pipeline.schema.FieldRule.EnumRule;
import com.pipemend.pipeline.schema.SchemaLoadException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The loader of country-aliases.v1.yaml: the real file, and hand-written broken ones. */
class CountryAliasesTest {

    private static final EnumRule COUNTRY = new EnumRule("countries.v1", Set.of("Austria", "Australia", "Belgium"));

    @Test
    void theRealFileLoadsAndEveryAliasPointsToACatalogueCountry() {
        CountryAliases aliases = VerifierTestSupport.ALIASES;

        assertThat(aliases.size()).isPositive();
        assertThat(aliases.maps(CountryAliases.normalize("UK"), "United Kingdom"))
                .isTrue();
        assertThat(aliases.maps(CountryAliases.normalize("\u00D6sterreich"), "Austria"))
                .isTrue();
        assertThat(aliases.maps(CountryAliases.normalize("Austria"), "Australia"))
                .isFalse();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {"\u00D6sterreich|osterreich", "U.K.|uk", "' Great Britain '|greatbritain", "\u00C9ire|eire"})
    void normalizationIsLowercaseWithoutAccentsAndOnlyLettersAndDigits(String value, String normalized) {
        assertThat(CountryAliases.normalize(value)).isEqualTo(normalized);
    }

    @Test
    void aCountryOutsideTheCatalogueFailsToLoad() {
        assertThatThrownBy(() -> CountryAliases.load(yaml("\"Atlantis\": [\"AT\"]"), COUNTRY))
                .isInstanceOf(SchemaLoadException.class)
                .hasMessageContaining("Atlantis is not in countries.v1");
    }

    @Test
    void anAliasOfTwoCountriesFailsToLoad() {
        assertThatThrownBy(
                        () -> CountryAliases.load(yaml("\"Austria\": [\"AU\"]\n  \"Australia\": [\"a.u.\"]"), COUNTRY))
                .isInstanceOf(SchemaLoadException.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    void twoAliasesOfTheSameCountryThatNormalizeAlikeAreFine() {
        CountryAliases aliases = CountryAliases.load(yaml("\"Austria\": [\"AT\", \"a.t.\"]"), COUNTRY);

        assertThat(aliases.maps("at", "Austria")).isTrue();
    }

    @Test
    void anotherCatalogueFailsToLoad() {
        assertThatThrownBy(() -> CountryAliases.load(
                        "version: 1\ncatalogue: countries.v2.txt\naliases:\n  \"Austria\": [\"AT\"]\n", COUNTRY))
                .isInstanceOf(SchemaLoadException.class);
    }

    @Test
    void aCountryWithoutAListFailsToLoad() {
        assertThatThrownBy(() -> CountryAliases.load(yaml("\"Austria\": \"AT\""), COUNTRY))
                .isInstanceOf(SchemaLoadException.class);
    }

    private static String yaml(String aliases) {
        return "version: 1\ncatalogue: countries.v1.txt\naliases:\n  " + aliases + "\n";
    }
}
