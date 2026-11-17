package com.pipemend.pipeline.verifiers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pipemend.pipeline.triage.contract.Operation;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class VerifiersTest {

    @ParameterizedTest
    @EnumSource(Operation.class)
    void everyOperationOfTheAllowlistHasItsVerifier(Operation operation) {
        Verifiers verifiers = Verifiers.of(VerifierTestSupport.SCHEMA, VerifierTestSupport.ALIASES, 0.90);

        assertThat(verifiers.forOperation(operation)).isNotNull();
    }

    @Test
    void aTableWithAnOperationMissingDoesNotBuild() {
        assertThatThrownBy(() -> new Verifiers(Map.of(Operation.TRIM, new TrimVerifier())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PARSE_DATE");
    }
}
