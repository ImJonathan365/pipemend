package com.pipemend.pipeline.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.pipemend.pipeline.triage.contract.Operation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** AC-09.4 against the real application.yml: the documented defaults, and fail-fast on a bad configuration. */
class PolicyPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Bound.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PolicyProperties.class)
    static class Bound {}

    @Test
    void ac_09_4_theDefaultsAreThoseOfDocs04AndTheCatalogOfDocs02() {
        runner.run(context -> assertThat(context.getBean(PolicyProperties.class))
                .isEqualTo(new PolicyProperties(0.85, 0.90, CorrectionCatalog.FIELDS)));
    }

    @Test
    void ac_09_4_theCatalogIsTheTableOfDocs02Section4() {
        assertThat(CorrectionCatalog.FIELDS)
                .isEqualTo(Map.of(
                        Operation.TRIM, Set.of("invoice_no", "stock_code", "country", "customer_id"),
                        Operation.NORMALIZE_CASE, Set.of("country", "invoice_no"),
                        Operation.PARSE_DATE, Set.of("invoice_date"),
                        Operation.PARSE_NUMBER, Set.of("unit_price", "quantity", "customer_id"),
                        Operation.MAP_TO_ENUM, Set.of("country"),
                        Operation.NULLIFY_TOKEN, Set.of("customer_id")));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "min-confidence=0.49",
                "min-confidence=1.01",
                "enum-min-confidence=0.49",
                "enum-min-confidence=1.01",
                "min-confidence=NaN"
            })
    void ac_09_4_aThresholdOutsideTheRangeStopsTheApplication(String property) {
        runner.withPropertyValues("pipemend.policy." + property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(messages(context.getStartupFailure())).anyMatch(message -> message.contains("[0.5, 1]"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.5", "1.0"})
    void ac_09_4_theEdgesOfTheRangeAreAccepted(String threshold) {
        runner.withPropertyValues(
                        "pipemend.policy.min-confidence=" + threshold,
                        "pipemend.policy.enum-min-confidence=" + threshold)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void ac_09_4_aFieldOutsideTheCatalogStopsTheApplication() {
        runner.withPropertyValues("pipemend.policy.allowlist[PARSE_DATE]=invoice_date,unit_price")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(messages(context.getStartupFailure()))
                            .anyMatch(message -> message.contains("does not allow PARSE_DATE on unit_price"));
                });
    }

    @Test
    void ac_09_4_anOperationOutsideTheCatalogStopsTheApplication() {
        runner.withPropertyValues("pipemend.policy.allowlist[RENAME_COLUMN]=country")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void ac_09_4_theAllowlistCanBeNarrowed() {
        runner.withPropertyValues("pipemend.policy.allowlist[TRIM]=country").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PolicyProperties.class).allowlist())
                    .containsEntry(Operation.TRIM, Set.of("country"));
        });
    }

    private static List<String> messages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.add(String.valueOf(t.getMessage()));
        }
        return messages;
    }
}
