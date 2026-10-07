package com.pipemend.pipeline.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pipemend.pipeline.schema.FieldRule.DateTimeRule;
import com.pipemend.pipeline.schema.FieldRule.DecimalRule;
import com.pipemend.pipeline.schema.FieldRule.EnumRule;
import com.pipemend.pipeline.schema.FieldRule.IntegerRule;
import com.pipemend.pipeline.schema.FieldRule.StringRule;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SchemaLoaderTest {

    private static final String MINIMAL = """
            name: test
            version: 1
            nullTokens: ["", "N/A"]
            fields:
              - name: invoice_no
                sourceHeader: Invoice
                type: string
                required: true
                pattern: "^[CA]?\\\\d{6}$"
              - name: quantity
                sourceHeader: Quantity
                type: integer
                required: true
                min: -10
                max: 10
            businessRules:
              - code: BR-01
                description: "x"
              - code: BR-02
                description: "y"
            """;

    @Test
    void loadsTheFrozenSchemaV1() {
        SchemaDefinition schema = SchemaTestSupport.realSchema();

        assertThat(schema.name()).isEqualTo("sales_transaction");
        assertThat(schema.version()).isEqualTo(1);
        assertThat(schema.fields())
                .extracting(FieldSpec::name)
                .containsExactly(
                        "invoice_no",
                        "stock_code",
                        "description",
                        "quantity",
                        "invoice_date",
                        "unit_price",
                        "customer_id",
                        "country");
        assertThat(schema.nullTokens()).containsExactlyInAnyOrder("", "N/A", "NA", "null", "NULL", "None", "-", "?");
        assertThat(schema.field("quantity").rule()).isEqualTo(new IntegerRule(-100000, 100000, 0L));
        assertThat(schema.field("unit_price").rule())
                .isEqualTo(new DecimalRule(2, BigDecimal.ZERO, new BigDecimal("100000")));
        assertThat(((StringRule) schema.field("description").rule()).maxLength())
                .isEqualTo(255);
        DateTimeRule date = (DateTimeRule) schema.field("invoice_date").rule();
        assertThat(date.min()).isEqualTo(LocalDateTime.of(2009, 1, 1, 0, 0));
        assertThat(date.max()).isNull();
        EnumRule country = (EnumRule) schema.field("country").rule();
        assertThat(country.catalogueName()).isEqualTo("countries.v1");
        assertThat(country.values())
                .hasSize(41)
                .contains("United Kingdom", "EIRE")
                .doesNotContain("Unspecified");
        assertThat(schema.field("customer_id").required()).isFalse();
    }

    @Test
    void minimalSchemaLoads() {
        assertThat(SchemaLoader.load(MINIMAL, name -> "").fields()).hasSize(2);
    }

    static Stream<Arguments> brokenSchemas() {
        return Stream.of(
                Arguments.of("unknown top-level key", "version: 1", "version: 1\nextra: true"),
                Arguments.of("unknown field key", "max: 10", "max: 10\n    unique: true"),
                Arguments.of("unsupported type", "type: integer", "type: float"),
                Arguments.of("invalid regex", "d{6}$", "d{6"),
                Arguments.of("unknown business rule", "code: BR-02", "code: BR-03"),
                Arguments.of("missing business rule", "  - code: BR-02\n    description: \"y\"\n", ""),
                Arguments.of("required not a boolean", "required: true\n    min", "required: maybe\n    min"),
                Arguments.of("invoice_no missing", "name: invoice_no", "name: invoice"),
                Arguments.of("malformed YAML", "fields:", "fields: [unclosed"));
    }

    // AC-06.5: anything the validator would not apply makes the load fail instead of being ignored.
    @ParameterizedTest(name = "AC-06.5 {0}")
    @MethodSource("brokenSchemas")
    void rejectsSchemasItCannotApply(String reason, String from, String to) {
        String broken = MINIMAL.replace(from, to);
        assertThat(broken).as(reason).isNotEqualTo(MINIMAL);

        assertThatThrownBy(() -> SchemaLoader.load(broken, name -> "")).isInstanceOf(SchemaLoadException.class);
    }

    @Test
    void rejectsCaseInsensitiveFields() {
        String broken =
                MINIMAL.replace("required: true\n    pattern", "required: true\n    caseSensitive: false\n    pattern");

        assertThatThrownBy(() -> SchemaLoader.load(broken, name -> ""))
                .isInstanceOf(SchemaLoadException.class)
                .hasMessageContaining("caseSensitive");
    }

    @Test
    void rejectsAnEmptyOrDuplicatedCatalogue() {
        String withCountry = MINIMAL.replace("businessRules:", """
                  - name: country
                    sourceHeader: Country
                    type: enum
                    required: true
                    catalogue: c.txt
                businessRules:""");

        assertThat(SchemaLoader.load(withCountry, name -> "A\nB\n")
                        .field("country")
                        .rule())
                .isEqualTo(new EnumRule("c", Set.of("A", "B")));
        assertThatThrownBy(() -> SchemaLoader.load(withCountry, name -> "\n")).isInstanceOf(SchemaLoadException.class);
        assertThatThrownBy(() -> SchemaLoader.load(withCountry, name -> "A\nA\n"))
                .isInstanceOf(SchemaLoadException.class);
    }
}
