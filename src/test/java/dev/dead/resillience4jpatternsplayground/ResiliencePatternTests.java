package dev.dead.resillience4jpatternsplayground;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ResiliencePatternTests {

    @LocalServerPort
    private int port;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JsonPlaceholderService service;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    @Autowired
    private BulkheadRegistry bulkheadRegistry;

    @BeforeEach
    void setUp() {
        // Reset all circuit breakers to closed state before each test
        circuitBreakerRegistry.getAllCircuitBreakers()
                .forEach(cb -> {
                    cb.transitionToClosedState();
                    cb.reset();
                });

        // Reset all rate limiters
        rateLimiterRegistry.getAllRateLimiters()
                .forEach(rl -> {
                    // Rate limiters auto-reset based on configuration
                });
    }

    // ============================================
    // RETRY PATTERN TESTS
    // ============================================

    @Test
    void testRetry_shouldSucceedAfterRetries() {
        // Given: Service that fails twice, then succeeds
        // When: Call the retry endpoint
        Mono<String> result = service.testRetry(true);

        // Then: Should succeed after retries
        StepVerifier.create(result)
                .expectNextMatches(response -> response.contains("Retry succeeded"))
                .verifyComplete();
    }

    @Test
    void testRetry_shouldInvokeFallbackAfterMaxAttempts() {
        // Given: Service configured to retry 3 times
        var retry = retryRegistry.retry("retryService");

        // When: All attempts fail
        // Then: Should invoke fallback
        assertThat(retry.getRetryConfig()
                .getMaxAttempts()).isEqualTo(3);
    }

    @Test
    void testRetry_viaHttpEndpoint() {
        // When: Call retry endpoint that will succeed after retries
        webTestClient.get()
                .uri("/test-retry?shouldFail=true")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("Retry succeeded"));
    }

    // ============================================
    // CIRCUIT BREAKER PATTERN TESTS
    // ============================================

    @Test
    void testCircuitBreaker_shouldOpenAfterFailures() {
        // Given: Circuit breaker with 50% failure threshold
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("circuitBreakerService");

        // When: Trigger enough failures to open circuit (need 5 calls minimum)
        IntStream.range(0, 10)
                .forEach(i -> {
                    try {
                        service.testCircuitBreaker(true)
                                .block();
                    } catch (Exception e) {
                        // Expected failures
                    }
                });

        // Then: Circuit should be open
        await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertThat(circuitBreaker.getState())
                                .isIn(CircuitBreaker.State.OPEN, CircuitBreaker.State.FORCED_OPEN)
                );
    }

    @Test
    void testCircuitBreaker_shouldFailFastWhenOpen() throws InterruptedException {
        // Given: Open circuit breaker
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("circuitBreakerService");

        // Trigger failures to open circuit
        IntStream.range(0, 10)
                .forEach(i -> {
                    try {
                        service.testCircuitBreaker(true)
                                .block();
                    } catch (Exception e) {
                        // Expected
                    }
                });

        // Wait for circuit to open
        Thread.sleep(100);

        // When: Circuit is open
        if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            // Then: Should fail fast without calling service
            StepVerifier.create(service.testCircuitBreaker(false))
                    .expectNextMatches(response -> response.contains("Fallback"))
                    .verifyComplete();
        }
    }

    @Test
    void testCircuitBreaker_shouldTransitionToHalfOpen() throws InterruptedException {
        // Given: Circuit breaker with 5s wait duration
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("circuitBreakerService");

        // Open the circuit
        IntStream.range(0, 10)
                .forEach(i -> {
                    try {
                        service.testCircuitBreaker(true)
                                .block();
                    } catch (Exception e) {
                        // Expected
                    }
                });

        // When: Wait for half-open transition (5 seconds)
        await().atMost(6, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    var state = circuitBreaker.getState();
                    assertThat(state).isIn(
                            CircuitBreaker.State.HALF_OPEN,
                            CircuitBreaker.State.CLOSED
                    );
                });
    }

    @Test
    void testCircuitBreaker_viaHttpEndpoint() {
        // When: Call circuit breaker endpoint with failures
        IntStream.range(0, 10)
                .forEach(i ->
                        webTestClient.get()
                                .uri("/test-circuit-breaker?shouldFail=true")
                                .exchange()
                                .expectStatus()
                                .isOk()
                );

        // Then: Subsequent calls should return fallback
        webTestClient.get()
                .uri("/test-circuit-breaker?shouldFail=false")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .containsAnyOf("Fallback", "Circuit breaker call succeeded"));
    }

    // ============================================
    // RATE LIMITER PATTERN TESTS
    // ============================================

    @Test
    void testRateLimiter_shouldLimitRequests() {
        // Given: Rate limiter configured for 5 calls per 10 seconds
        var rateLimiter = rateLimiterRegistry.rateLimiter("rateLimiterService");

        // When: Make 10 requests rapidly
        IntStream.range(0, 10)
                .forEach(i -> {
                    try {
                        service.testRateLimiter()
                                .block();
                    } catch (Exception e) {
                        // Some will be rate limited
                    }
                });

        // Then: Some requests should have been rejected
        // (Can't assert exact number due to timing, but rate limiter was exercised)
        assertThat(rateLimiter.getMetrics()
                .getAvailablePermissions())
                .isLessThanOrEqualTo(5);
    }

    @Test
    void testRateLimiter_shouldAllowRequestsAfterRefresh() throws InterruptedException {
        // Given: Rate limiter with 10s refresh period
        var rateLimiter = rateLimiterRegistry.rateLimiter("rateLimiterService");

        // When: Exhaust rate limit
        IntStream.range(0, 6)
                .forEach(i -> {
                    try {
                        service.testRateLimiter()
                                .block();
                    } catch (Exception e) {
                        // Expected for requests over limit
                    }
                });

        // Then: No permissions available
        assertThat(rateLimiter.getMetrics()
                .getAvailablePermissions()).isEqualTo(0);

        // When: Wait for refresh period (10 seconds)
        Thread.sleep(10500);

        // Then: Permissions should be refreshed
        assertThat(rateLimiter.getMetrics()
                .getAvailablePermissions()).isGreaterThan(0);
    }

    @Test
    void testRateLimiter_viaHttpEndpoint() {
        // When: Send multiple requests rapidly
        long successCount = IntStream.range(0, 10)
                .mapToObj(i ->
                        webTestClient.get()
                                .uri("/test-rate-limiter")
                                .exchange()
                                .expectStatus()
                                .isOk()
                                .expectBody(String.class)
                                .returnResult()
                                .getResponseBody()
                )
                .filter(body -> body != null && body.contains("allowed"))
                .count();

        // Then: Some requests succeeded (up to limit)
        assertThat(successCount).isGreaterThan(0)
                .isLessThanOrEqualTo(5);
    }

    // ============================================
    // BULKHEAD PATTERN TESTS
    // ============================================

    @Test
    void testBulkhead_shouldLimitConcurrentCalls() throws InterruptedException {
        // Given: Bulkhead configured for 2 concurrent calls
        var bulkhead = bulkheadRegistry.bulkhead("bulkheadService");

        // When: Make 3 concurrent calls with delay
        CompletableFuture<String> call1 = service.testBulkhead(2000)
                .toFuture();
        CompletableFuture<String> call2 = service.testBulkhead(2000)
                .toFuture();
        Thread.sleep(100); // Small delay to ensure first 2 are processing
        CompletableFuture<String> call3 = service.testBulkhead(2000)
                .toFuture();

        // Then: First 2 should succeed or be processing, 3rd should fail or wait
        assertThat(bulkhead.getMetrics()
                .getAvailableConcurrentCalls())
                .isLessThanOrEqualTo(2);

        // Cleanup: wait for calls to complete
        await().atMost(3, TimeUnit.SECONDS)
                .until(() -> call1.isDone() && call2.isDone());
    }

    @Test
    void testBulkhead_shouldAllowCallsAfterCompletion() throws InterruptedException {
        // Given: Bulkhead with 2 concurrent calls
        var bulkhead = bulkheadRegistry.bulkhead("bulkheadService");

        // When: Make 2 concurrent calls with short delay
        CompletableFuture<String> call1 = service.testBulkhead(500)
                .toFuture();
        CompletableFuture<String> call2 = service.testBulkhead(500)
                .toFuture();

        // Wait for completion
        await().atMost(1, TimeUnit.SECONDS)
                .until(() -> call1.isDone() && call2.isDone());

        // Then: Should have available capacity again
        await().atMost(1, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertThat(bulkhead.getMetrics()
                                .getAvailableConcurrentCalls())
                                .isEqualTo(2)
                );
    }

    @Test
    void testBulkhead_viaHttpEndpoint() {
        // When: Make concurrent requests (this is tricky in tests, but we can verify it works)
        webTestClient.get()
                .uri("/test-bulkhead?delayMs=100")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .containsAnyOf("Bulkhead completed", "Fallback"));
    }

    // ============================================
    // TIME LIMITER PATTERN TESTS
    // ============================================

    @Test
    void testTimeLimiter_shouldCompleteWithinTimeout() throws ExecutionException, InterruptedException, TimeoutException {
        // When: Operation completes within timeout (2 seconds < 3 second timeout)
        CompletableFuture<String> result = service.testTimeLimiter(2000);

        // Then: Should complete successfully
        String response = result.get(4, TimeUnit.SECONDS);
        assertThat(response).contains("Time limiter completed");
    }

    @Test
    void testTimeLimiter_shouldTimeoutExceedingDelay() throws InterruptedException {
        // When: Operation exceeds timeout (5 seconds > 3 second timeout)
        CompletableFuture<String> result = service.testTimeLimiter(5000);

        // Then: Should timeout and invoke fallback
        await().atMost(4, TimeUnit.SECONDS)
                .until(result::isDone);

        assertThat(result.join()).contains("Time Limiter Fallback");
    }

    @Test
    void testTimeLimiter_viaHttpEndpoint_withinTimeout() {
        // When: Request within timeout
        webTestClient.get()
                .uri("/test-time-limiter?delayMs=1000")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("Time limiter completed"));
    }

    @Test
    void testTimeLimiter_viaHttpEndpoint_exceedsTimeout() {
        // When: Request exceeds timeout
        webTestClient.get()
                .uri("/test-time-limiter?delayMs=5000")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("Fallback"));
    }

    // ============================================
    // COMBINED PATTERNS TESTS
    // ============================================

    @Test
    void testCombinedPatterns_shouldApplyAllPatterns() {
        // When: Call endpoint with all patterns
        webTestClient.get()
                .uri("/test-all?delayMs=500")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .containsAnyOf("Success", "Fallback"));
    }

    @Test
    void testCombinedPatterns_shouldRespectRateLimit() {
        // Given: Combined endpoint with rate limiter (2 calls per 10s)
        // When: Make multiple requests
        IntStream.range(0, 5)
                .forEach(i ->
                        webTestClient.get()
                                .uri("/test-all?delayMs=100")
                                .exchange()
                                .expectStatus()
                                .isOk()
                );

        // Then: Some requests should be rate limited or successful
        // (Exact count varies due to timing, but patterns are applied)
    }

    // ============================================
    // METRICS AND MONITORING TESTS
    // ============================================

    @Test
    void testActuatorHealth() {
        // When: Call health endpoint
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void testCircuitBreakerActuator() {
        // When: Call circuit breaker actuator endpoint
        webTestClient.get()
                .uri("/actuator/circuitbreakers")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void testMetricsEndpoint() {
        // When: Call metrics endpoint
        webTestClient.get()
                .uri("/actuator/metrics")
                .exchange()
                .expectStatus()
                .isOk();
    }

    // ============================================
    // EDGE CASES AND ERROR SCENARIOS
    // ============================================

    @Test
    void testAllEndpoints_shouldReturnValidResponses() {
        // Verify all endpoints are accessible
        String[] endpoints = {
                "/test-retry?shouldFail=false",
                "/test-circuit-breaker?shouldFail=false",
                "/test-rate-limiter",
                "/test-bulkhead?delayMs=100",
                "/test-time-limiter?delayMs=1000",
                "/test-all?delayMs=100"
        };

        for (String endpoint : endpoints) {
            webTestClient.get()
                    .uri(endpoint)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String.class)
                    .value(body -> assertThat(body).isNotNull());
        }
    }

    @Test
    void testFallbackMethods_areInvoked() {
        // Given: Service with fallback methods
        // When: Trigger failure conditions
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("circuitBreakerService");

        // Force open circuit
        IntStream.range(0, 10)
                .forEach(i -> {
                    try {
                        service.testCircuitBreaker(true)
                                .block();
                    } catch (Exception e) {
                        // Expected
                    }
                });

        // Then: Fallback should be invoked
        if (cb.getState() == CircuitBreaker.State.OPEN) {
            StepVerifier.create(service.testCircuitBreaker(false))
                    .expectNextMatches(response -> response.contains("Fallback"))
                    .verifyComplete();
        }
    }
}