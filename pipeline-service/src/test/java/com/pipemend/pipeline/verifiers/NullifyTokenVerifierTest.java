package com.pipemend.pipeline.verifiers;

import static com.pipemend.pipeline.verifiers.VerifierTestSupport.input;
import static org.assertj.core.api.Assertions.assertThat;

import com.pipemend.pipeline.triage.contract.Operation;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** NULLIFY_TOKEN of docs/02 section 4, with the null tokens of sales_transaction.v1.yaml. */
class NullifyTokenVerifierTest {

    private final NullifyTokenVerifier verifier = new NullifyTokenVerifier(VerifierTestSupport.SCHEMA);

    static Stream<String> nullTokens() {
        return VerifierTestSupport.SCHEMA.nullTokens().stream();
    }

    @ParameterizedTest
    @MethodSource("nullTokens")
    void ac_10_1_everyNullTokenOfAnOptionalFieldBecomesNull(String token) {
        assertThat(verify("customer_id", token, null)).isEqualTo(VerificationResult.success());
        assertThat(verify("customer_id", " " + token + "\t", null)).isEqualTo(VerificationResult.success());
    }

    @Test
    void ac_10_1_aValueThatIsNotANullTokenIsKept() {
        assertThat(verify("customer_id", "17850", null).code()).isEqualTo(VerificationCode.VALUE_MISMATCH);
        assertThat(verify("customer_id", "none", null).code()).isEqualTo(VerificationCode.VALUE_MISMATCH);
    }

    @Test
    void ac_10_1_theProposedValueMustBeNull() {
        assertThat(verify("customer_id", "N/A", "").code()).isEqualTo(VerificationCode.VALUE_MISMATCH);
    }

    @Test
    void ac_10_1_aRequiredFieldIsNeverNullified() {
        assertThat(verify("country", "N/A", null).code()).isEqualTo(VerificationCode.VALUE_MISMATCH);
    }

    private VerificationResult verify(String field, String original, String proposed) {
        return verifier.verify(input(field, original, Operation.NULLIFY_TOKEN, proposed, 0.99));
    }
}
