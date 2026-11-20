package com.pipemend.pipeline.triage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** AI_REDACT_FIELDS and AI_SEND_FULL_RECORD (NFR-15) as application.yml binds them. */
class AiPrivacyPropertiesTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Binding.class)
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiPrivacyProperties.class)
    static class Binding {}

    @Test
    void nfr_15_theDefaultsRedactTheCustomerAndSendTheFullRecord() {
        context.run(started -> assertThat(started.getBean(AiPrivacyProperties.class))
                .isEqualTo(new AiPrivacyProperties(List.of("customer_id"), true)));
    }

    @Test
    void nfr_15_aCommaSeparatedListBindsToSeveralFields() {
        context.withPropertyValues("AI_REDACT_FIELDS=customer_id,description", "AI_SEND_FULL_RECORD=false")
                .run(started -> assertThat(started.getBean(AiPrivacyProperties.class))
                        .isEqualTo(new AiPrivacyProperties(List.of("customer_id", "description"), false)));
    }

    @Test
    void d235_anEmptyVariableRedactsNothing() {
        context.withPropertyValues("AI_REDACT_FIELDS=")
                .run(started -> assertThat(
                                started.getBean(AiPrivacyProperties.class).redactFields())
                        .isEmpty());
    }
}
