package com.pipemend.pipeline.triage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;

/** The values of NFR-07 (docs/03 l.99-102) and the two rules of docs/06 A.5, row by row. */
class AiResilienceTest {

    @Test
    void nfr_07_theCircuitBreakerHasTheValuesOfDocs03() {
        CircuitBreakerConfig config = AiResilience.circuitBreakerConfig();

        assertThat(config.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
        assertThat(config.getSlidingWindowSize()).isEqualTo(20);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50);
        assertThat(config.getSlowCallRateThreshold()).isEqualTo(50);
        assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(20));
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(30_000L);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
    }

    @Test
    void nfr_07_theRetryMakesThreeAttemptsWaiting500MsThen1s() {
        RetryConfig config = AiResilience.retryConfig(attempt -> true);

        assertThat(config.getMaxAttempts()).isEqualTo(3);
        assertThat(config.getIntervalBiFunction().apply(1, null)).isEqualTo(500L);
        assertThat(config.getIntervalBiFunction().apply(2, null)).isEqualTo(1000L);
        assertThat(config.getExceptionPredicate().test(new IllegalStateException()))
                .isFalse();
    }

    @Test
    void nfr_07_tenSlowCallsOpenTheCircuitEvenIfTheyAnswered() {
        CircuitBreaker breaker = AiResilience.circuitBreaker();

        for (int i = 0; i < AiResilience.MINIMUM_CALLS; i++) {
            assertThat(breaker.tryAcquirePermission()).isTrue();
            breaker.onResult(21, TimeUnit.SECONDS, new TriageOutcome.InvalidResponse("slow but not an outage"));
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void nfr_07_callsUnderTheSlowThresholdThatAreNotOutagesKeepItClosed() {
        CircuitBreaker breaker = AiResilience.circuitBreaker();

        for (int i = 0; i < AiResilience.WINDOW_CALLS; i++) {
            assertThat(breaker.tryAcquirePermission()).isTrue();
            breaker.onResult(19, TimeUnit.SECONDS, new TriageOutcome.InvalidResponse("fast enough"));
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void docs06A5_onlyAnUnavailableOutcomeCountsAsAFailure() {
        assertThat(AiResilience.countsAsFailure(new TriageOutcome.Unavailable("x")))
                .isTrue();
        assertThat(AiResilience.countsAsFailure(new TriageOutcome.InvalidResponse("x")))
                .isFalse();
        assertThat(AiResilience.countsAsFailure(new TriageOutcome.BudgetExhausted("x")))
                .isFalse();
        assertThat(AiResilience.countsAsFailure(null)).isFalse();
    }

    @Test
    void docs03_l99_onlyARefusedConnectionOrAConnectTimeoutIsRetried() {
        assertThat(AiResilience.retries(new ConnectException("refused"))).isTrue();
        assertThat(AiResilience.retries(new HttpConnectTimeoutException("connect")))
                .isTrue();
        assertThat(AiResilience.retries(new HttpTimeoutException("read"))).isFalse();
        assertThat(AiResilience.retries(new IOException("cut"))).isFalse();
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(
            nullValues = "null",
            value = {
                "502, text/html, true",
                "503, text/html, true",
                "504, text/html, true",
                "503, application/json, true",
                "503, null, true",
                "502, application/problem+json, false",
                "503, application/problem+json, false",
                "504, application/problem+json, false",
                "500, text/plain, false",
                "500, application/problem+json, false",
                "429, text/plain, false",
                "422, application/problem+json, false",
                "200, application/json, false"
            })
    void docs06A5_onlyA502503Or504WithoutProblemJsonIsRetried(int status, @Nullable String type, boolean retried) {
        assertThat(AiResilience.retries(status, type == null ? null : MediaType.parseMediaType(type)))
                .isEqualTo(retried);
    }
}
