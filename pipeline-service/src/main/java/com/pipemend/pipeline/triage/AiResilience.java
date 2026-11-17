package com.pipemend.pipeline.triage;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

/**
 * The retry and the circuit breaker of NFR-07 (docs/03 l.99-102) and what each one counts (docs/06 A.5). docs/03 gives
 * these values no environment variable, so they are constants.
 */
public final class AiResilience {

    static final int MAX_ATTEMPTS = 3;
    static final Duration FIRST_BACKOFF = Duration.ofMillis(500);
    static final double BACKOFF_MULTIPLIER = 2;

    static final int WINDOW_CALLS = 20;
    static final int MINIMUM_CALLS = 10;
    static final float FAILURE_RATE_PERCENT = 50;
    static final float SLOW_CALL_RATE_PERCENT = 50;
    static final Duration SLOW_CALL = Duration.ofSeconds(20);
    static final Duration OPEN = Duration.ofSeconds(30);
    static final int HALF_OPEN_CALLS = 3;

    private static final Set<Integer> RETRIED_STATUSES = Set.of(502, 503, 504);
    private static final Logger log = LoggerFactory.getLogger(AiResilience.class);

    private AiResilience() {}

    /** One per ai-service, shared by every batch: the jobs run one at a time (docs/03 NFR-10). */
    public static CircuitBreaker circuitBreaker() {
        CircuitBreaker breaker = CircuitBreaker.of("ai-service", circuitBreakerConfig());
        breaker.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                log.warn("The circuit breaker towards ai-service is open for {}", OPEN);
            }
        });
        return breaker;
    }

    static CircuitBreakerConfig circuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindow(WINDOW_CALLS, MINIMUM_CALLS, CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .failureRateThreshold(FAILURE_RATE_PERCENT)
                .slowCallRateThreshold(SLOW_CALL_RATE_PERCENT)
                .slowCallDurationThreshold(SLOW_CALL)
                .waitDurationInOpenState(OPEN)
                .permittedNumberOfCallsInHalfOpenState(HALF_OPEN_CALLS)
                .recordResult(AiResilience::countsAsFailure)
                .build();
    }

    /** Exceptions are never retried: the attempt turns every transport failure into a result. */
    static <T> RetryConfig retryConfig(Predicate<T> retryable) {
        return RetryConfig.<T>custom()
                .maxAttempts(MAX_ATTEMPTS)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(FIRST_BACKOFF, BACKOFF_MULTIPLIER))
                .retryOnResult(retryable)
                .retryOnException(e -> false)
                .build();
    }

    /** docs/06 A.5: every Unavailable outcome counts; an invalid answer or an exhausted budget does not. */
    static boolean countsAsFailure(@Nullable Object outcome) {
        return outcome instanceof TriageOutcome.Unavailable;
    }

    /** docs/03 l.99: a refused connection or a connect timeout; never a read timeout or a cut connection. */
    static boolean retries(Throwable transportCause) {
        return transportCause instanceof ConnectException || transportCause instanceof HttpConnectTimeoutException;
    }

    /** docs/03 l.99: 502, 503 or 504 without a problem+json body, whatever it carries. */
    static boolean retries(int status, @Nullable MediaType contentType) {
        return RETRIED_STATUSES.contains(status)
                && (contentType == null || !MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(contentType));
    }
}
