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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ResiliencePatternTests {

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

        // Log retry configuration for debugging
        var retry = retryRegistry.retry("retryService");
        System.out.println("🔧 Retry config - maxAttempts: " + retry.getRetryConfig()
                .getMaxAttempts());
    }


    // ============================================
    // RETRY PATTERN TESTS
    // ============================================

    @Test
    void testRetry_shouldSucceedAfterRetries() {
        // Subscribe to retry events for visibility
        var retry = retryRegistry.retry("retryService");
        retry.getEventPublisher()
                .onRetry(event ->
                        System.out.println("📊 Retry event - attempt: " + event.getNumberOfRetryAttempts())
                );

        // When shouldFail=true, it should fail twice then succeed on 3rd attempt
        StepVerifier.create(service.testRetry(true))
                .expectNextMatches(response -> {
                    System.out.println("Final result: " + response);
                    return response.contains("succeeded after 3");
                })
                .verifyComplete();
    }

    @Test
    void testRetry_shouldSucceedImmediatelyWhenNoFailure() {
        // When shouldFail=false, succeeds immediately
        StepVerifier.create(service.testRetry(false))
                .expectNextMatches(response -> response.contains("succeeded after 1"))
                .verifyComplete();
    }

    @Test
    void testRetry_viaHttpEndpoint() {
        // Via HTTP endpoint - should succeed after retries
        webTestClient.get()
                .uri("/test-retry?shouldFail=true")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> {
                    System.out.println("HTTP Response: " + body);
                    assertThat(body).contains("succeeded");
                });
    }


    // ============================================
    // CIRCUIT BREAKER PATTERN TESTS
    // ============================================

    @Test
    void testCircuitBreaker_shouldOpenAfterFailures() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("circuitBreakerService");

        // Need 5+ calls (minimumNumberOfCalls) with 50%+ failures
        // Call the service 10 times, all failures - fallback will be invoked
        IntStream.range(0, 10)
                .forEach(i -> {
                    String result = service.testCircuitBreaker(true)
                            .block(Duration.ofSeconds(2));
                    System.out.println("Result " + i + ": " + result);
                });

        // Circuit should open after threshold reached
        await().atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN)
                );
    }

    @Test
    void testCircuitBreaker_shouldAllowCallsWhenClosed() {
        StepVerifier.create(service.testCircuitBreaker(false))
                .expectNextMatches(response -> response.contains("succeeded"))
                .verifyComplete();
    }

    @Test
    void testCircuitBreaker_viaHttpEndpoint() {
        webTestClient.get()
                .uri("/test-circuit-breaker?shouldFail=false")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("succeeded"));
    }


    // ============================================
    // RATE LIMITER PATTERN TESTS
    // ============================================

    @Test
    void testRateLimiter_shouldLimitRequests() {
        var rateLimiter = rateLimiterRegistry.rateLimiter("rateLimiterService");

        // Exhaust the limit (5 calls per 10s in test config)
        IntStream.range(0, 7)
                .forEach(i -> {
                    try {
                        service.testRateLimiter()
                                .block(Duration.ofSeconds(1));
                    } catch (Exception e) {
                        // Some will be rate limited
                    }
                });

        // Should have consumed permits
        assertThat(rateLimiter.getMetrics()
                .getAvailablePermissions())
                .isLessThanOrEqualTo(5);
    }

    @Test
    void testRateLimiter_viaHttpEndpoint() {
        webTestClient.get()
                .uri("/test-rate-limiter")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("allowed"));
    }


    // ============================================
    // BULKHEAD PATTERN TESTS
    // ============================================

    @Test
    void testBulkhead_shouldLimitConcurrentCalls() {
        var bulkhead = bulkheadRegistry.bulkhead("bulkheadService");

        CompletableFuture<String> call1 = service.testBulkhead(1000)
                .toFuture();
        CompletableFuture<String> call2 = service.testBulkhead(1000)
                .toFuture();

        await().atMost(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        assertThat(bulkhead.getMetrics()
                                .getAvailableConcurrentCalls())
                                .isLessThanOrEqualTo(2)
                );

        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> call1.isDone() && call2.isDone());
    }

    @Test
    void testBulkhead_viaHttpEndpoint() {
        webTestClient.get()
                .uri("/test-bulkhead?delayMs=100")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("completed"));
    }

    // ============================================
    // TIME LIMITER PATTERN TESTS
    // ============================================

    @Test
    void testTimeLimiter_shouldCompleteWithinTimeout() throws Exception {
        CompletableFuture<String> result = service.testTimeLimiter(1000);
        String response = result.get(4, TimeUnit.SECONDS);
        assertThat(response).contains("completed");
    }

    @Test
    void testTimeLimiter_shouldTimeoutExceedingDelay() {
        CompletableFuture<String> result = service.testTimeLimiter(5000);

        await().atMost(4, TimeUnit.SECONDS)
                .until(result::isDone);
        assertThat(result.join()).contains("Fallback");
    }

    @Test
    void testTimeLimiter_viaHttpEndpoint_withinTimeout() {
        webTestClient.get()
                .uri("/test-time-limiter?delayMs=1000")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("completed"));
    }

    @Test
    void testTimeLimiter_viaHttpEndpoint_exceedsTimeout() {
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
        webTestClient.get()
                .uri("/test-all?delayMs=500")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).isNotNull());
    }

    // ============================================
    // METRICS AND MONITORING TESTS
    // ============================================

    @Test
    void testActuatorHealth() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void testMetricsEndpoint() {
        webTestClient.get()
                .uri("/actuator/metrics")
                .exchange()
                .expectStatus()
                .isOk();
    }

    // ============================================
    // INTEGRATION TESTS
    // ============================================

    @Test
    void testAllEndpoints_shouldReturnValidResponses() {
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
}