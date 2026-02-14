package dev.dead.resillience4jpatternsplayground;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootApplication
public class Resillience4jPatternsPlaygroundApplication {
    public static void main(String[] args) {
        SpringApplication.run(Resillience4jPatternsPlaygroundApplication.class, args);
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}

@RestController
class TestController {
    private final JsonPlaceholderService service;

    public TestController(JsonPlaceholderService service) {
        this.service = service;
    }

    @GetMapping("/test-all")
    public Mono<String> testAll(
            @RequestParam(defaultValue = "0") long delayMs) {
        return service.getPostsWithAllPatterns(delayMs);
    }

    @GetMapping("/test-retry")
    public Mono<String> testRetry(
            @RequestParam(defaultValue = "false") boolean shouldFail) {
        return service.testRetry(shouldFail);
    }

    @GetMapping("/test-circuit-breaker")
    public Mono<String> testCircuitBreaker(
            @RequestParam(defaultValue = "false") boolean shouldFail) {
        return service.testCircuitBreaker(shouldFail);
    }

    @GetMapping("/test-rate-limiter")
    public Mono<String> testRateLimiter() {
        return service.testRateLimiter();
    }

    @GetMapping("/test-bulkhead")
    public Mono<String> testBulkhead(
            @RequestParam(defaultValue = "1000") long delayMs) {
        return service.testBulkhead(delayMs);
    }

    @GetMapping("/test-time-limiter")
    public CompletableFuture<String> testTimeLimiter(
            @RequestParam(defaultValue = "1000") long delayMs) {
        return service.testTimeLimiter(delayMs);
    }
}

@Service
class JsonPlaceholderService {
    private final WebClient webClient;
    private final AtomicInteger retryCounter = new AtomicInteger(0);
    private final AtomicInteger circuitBreakerCounter = new AtomicInteger(0);

    public JsonPlaceholderService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .baseUrl("https://jsonplaceholder.typicode.com")
                .build();
    }

    // ============================================
    // ALL PATTERNS COMBINED (for demo purposes)
    // ============================================
    @Retry(name = "mainService", fallbackMethod = "fallbackMono")
    @CircuitBreaker(name = "mainService", fallbackMethod = "fallbackMono")
    @RateLimiter(name = "mainService", fallbackMethod = "fallbackMono")
    @Bulkhead(name = "mainService", fallbackMethod = "fallbackMono")
    public Mono<String> getPostsWithAllPatterns(long sleepMs) {
        return Mono.delay(Duration.ofMillis(sleepMs))
                .flatMap(tick -> this.webClient.get()
                        .uri("/posts/1")
                        .retrieve()
                        .bodyToMono(String.class)
                        .map(res -> "✅ Success with " + sleepMs + "ms delay: " + res.substring(0, Math.min(50, res.length())))
                );
    }

    // ============================================
    // RETRY PATTERN
    // ============================================
    @Retry(name = "retryService", fallbackMethod = "fallbackMono")
    public Mono<String> testRetry(boolean shouldFail) {
        int attempt = retryCounter.incrementAndGet();
        System.out.println("🔄 Retry attempt: " + attempt);

        if (shouldFail && attempt < 3) {
            return Mono.error(new RuntimeException("Simulated failure on attempt " + attempt));
        }

        retryCounter.set(0); // Reset for next test
        return Mono.just("✅ Retry succeeded after " + attempt + " attempt(s)");
    }

    // ============================================
    // CIRCUIT BREAKER PATTERN
    // ============================================
    @CircuitBreaker(name = "circuitBreakerService", fallbackMethod = "fallbackMono")
    public Mono<String> testCircuitBreaker(boolean shouldFail) {
        int attempt = circuitBreakerCounter.incrementAndGet();
        System.out.println("⚡ Circuit breaker attempt: " + attempt);

        if (shouldFail) {
            return Mono.error(new RuntimeException("Simulated circuit breaker failure " + attempt));
        }

        return Mono.just("✅ Circuit breaker call succeeded (attempt " + attempt + ")");
    }

    // ============================================
    // RATE LIMITER PATTERN
    // ============================================
    @RateLimiter(name = "rateLimiterService", fallbackMethod = "fallbackMono")
    public Mono<String> testRateLimiter() {
        long timestamp = System.currentTimeMillis();
        System.out.println("🚦 Rate limiter called at: " + timestamp);
        return Mono.just("✅ Rate limiter allowed request at " + timestamp);
    }

    // ============================================
    // BULKHEAD PATTERN
    // ============================================
    @Bulkhead(name = "bulkheadService", fallbackMethod = "fallbackMono")
    public Mono<String> testBulkhead(long delayMs) {
        System.out.println("🏊 Bulkhead: Processing request with " + delayMs + "ms delay");
        return Mono.delay(Duration.ofMillis(delayMs))
                .map(tick -> "✅ Bulkhead completed after " + delayMs + "ms");
    }

    // ============================================
    // TIME LIMITER PATTERN (requires CompletableFuture)
    // ============================================
    @TimeLimiter(name = "timeLimiterService", fallbackMethod = "fallbackCompletableFuture")
    public CompletableFuture<String> testTimeLimiter(long delayMs) {
        System.out.println("⏱️ Time limiter: Starting operation with " + delayMs + "ms delay");
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(delayMs);
                return "✅ Time limiter completed in " + delayMs + "ms";
            } catch (InterruptedException e) {
                Thread.currentThread()
                        .interrupt();
                throw new RuntimeException("Interrupted", e);
            }
        });
    }

    // ============================================
    // FALLBACK METHODS
    // ============================================

    // Fallback for Mono methods
    public Mono<String> fallbackMono(Throwable t) {
        return Mono.just("❌ Fallback triggered: " + t.getClass()
                .getSimpleName() + " - " + t.getMessage());
    }

    public Mono<String> fallbackMono(long delayMs, Throwable t) {
        return Mono.just("❌ Fallback (delay=" + delayMs + "ms): " + t.getClass()
                .getSimpleName() + " - " + t.getMessage());
    }

    public Mono<String> fallbackMono(boolean shouldFail, Throwable t) {
        return Mono.just("❌ Fallback (shouldFail=" + shouldFail + "): " + t.getClass()
                .getSimpleName() + " - " + t.getMessage());
    }

    // Fallback for CompletableFuture methods
    public CompletableFuture<String> fallbackCompletableFuture(long delayMs,
                                                               Throwable t) {
        return CompletableFuture.completedFuture("❌ Time Limiter Fallback: " + t.getClass()
                .getSimpleName() + " - Operation exceeded timeout");
    }
}