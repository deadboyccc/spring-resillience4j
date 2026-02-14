# Resilience4j Patterns Playground

A comprehensive Spring Boot demo application showcasing all Resilience4j patterns with working examples, detailed
documentation, and testing guides.

---

## 📋 Table of Contents

1. [Project Overview](#project-overview)
2. [Quick Start](#quick-start)
3. [Project Structure](#project-structure)
4. [Resilience4j Patterns](#resilience4j-patterns)
    - [Retry Pattern](#1--retry-pattern)
    - [Circuit Breaker Pattern](#2--circuit-breaker-pattern)
    - [Rate Limiter Pattern](#3--rate-limiter-pattern)
    - [Bulkhead Pattern](#4--bulkhead-pattern)
    - [Time Limiter Pattern](#5--time-limiter-pattern)
5. [Testing Guide](#testing-guide)
6. [Configuration Reference](#configuration-reference)
7. [Pattern Deep Dive](#pattern-deep-dive)
8. [Monitoring & Debugging](#monitoring--debugging)
9. [Best Practices](#best-practices)
10. [Troubleshooting](#troubleshooting)
11. [Real-World Use Cases](#real-world-use-cases)
12. [Dependencies](#dependencies)

---

## Project Overview

### What This Project Includes

✅ **All Resilience4j Patterns** - Retry, Circuit Breaker, Rate Limiter, Bulkhead, Time Limiter  
✅ **Individual Test Endpoints** - Test each pattern separately  
✅ **Combined Pattern Testing** - See patterns work together  
✅ **Complete Configuration Examples** - Production-ready YAML configs  
✅ **Comprehensive Documentation** - Everything you need to understand and use Resilience4j  
✅ **Monitoring Setup** - Actuator endpoints and metrics  
✅ **Real-World Examples** - Production-ready code patterns

### Core Principles

Resilience4j is a lightweight fault tolerance library inspired by Netflix Hystrix, designed for Java 8+ and functional
programming.

1. **Lightweight:** No external dependencies (except Vavr)
2. **Modular:** Use only what you need
3. **Functional:** Works with lambda expressions
4. **Reactive:** First-class support for Project Reactor and RxJava
5. **Type-safe:** Strong type safety

---

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.6+

### Run the Application

```bash
# Clone or download the project
cd resilience4j-patterns-playground

# Build
mvn clean install

# Run
mvn spring-boot:run

# Or run directly
java -jar target/resilience4j-patterns-playground-0.0.1-SNAPSHOT.jar
```

Application starts on: **http://localhost:8080**

### Quick Test

```bash
# Test all patterns together
curl "http://localhost:8080/test-all?delayMs=500"

# Test individual patterns
curl "http://localhost:8080/test-retry?shouldFail=true"
curl "http://localhost:8080/test-circuit-breaker?shouldFail=true"
curl "http://localhost:8080/test-rate-limiter"
curl "http://localhost:8080/test-bulkhead?delayMs=3000"
curl "http://localhost:8080/test-time-limiter?delayMs=2000"
```

---

## Project Structure

```
├── Resillience4jPatternsPlaygroundApplication.java  # Main application with all patterns
├── application.yml                                   # Configuration for all patterns
├── pom.xml                                          # Dependencies
└── README.md                                        # This comprehensive guide
```

---

## Resilience4j Patterns

### 1. 🔄 Retry Pattern

#### Overview

**Purpose:** Automatically retry failed operations with configurable delays

**When to Use:**

- Transient failures (network blips, temporary service unavailability)
- Database connection timeouts
- API rate limit errors (with exponential backoff)
- Distributed system communication

#### Basic Usage

```java

@Retry(name = "myService", fallbackMethod = "fallback")
public Mono<String> operation() {
    return externalService.call();
}

public Mono<String> fallback(Throwable t) {
    return Mono.just("Fallback response");
}
```

#### Configuration

```yaml
resilience4j:
  retry:
    instances:
      myService:
        maxAttempts: 3                          # Try up to 3 times
        waitDuration: 1s                        # Wait 1s between retries
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2         # 1s -> 2s -> 4s
        exponentialMaxWaitDuration: 10s         # Cap at 10s max
        retryExceptions:
          - java.net.ConnectException
          - java.net.SocketTimeoutException
        ignoreExceptions:
          - java.lang.IllegalArgumentException
```

#### How It Works

**Retry Flow:**

1. Attempt 1: Execute operation
2. If fails → wait for configured duration
3. Attempt 2: Execute operation
4. If fails → wait (with exponential backoff if enabled)
5. Attempt 3: Execute operation
6. If fails → call fallback

**Internal Algorithm:**

```java
// Pseudo-code
public <T> T execute(Supplier<T> supplier) {
    int attempt = 0;
    while (attempt < maxAttempts) {
        try {
            return supplier.get();
        } catch (Exception e) {
            attempt++;
            if (attempt >= maxAttempts || !shouldRetry(e)) {
                throw e;
            }
            Thread.sleep(calculateWaitDuration(attempt));
        }
    }
}

private long calculateWaitDuration(int attempt) {
    if (enableExponentialBackoff) {
        return Math.min(
                waitDuration * Math.pow(exponentialBackoffMultiplier, attempt - 1),
                exponentialMaxWaitDuration
        );
    }
    return waitDuration;
}
```

#### Testing

```bash
# Test retry with failures (fails twice, succeeds on 3rd attempt)
curl "http://localhost:8080/test-retry?shouldFail=true"

# Expected Response:
# ✅ Retry succeeded after 3 attempt(s)

# Check logs to see retry attempts:
# 🔄 Retry attempt: 1
# 🔄 Retry attempt: 2
# 🔄 Retry attempt: 3
```

#### Real-World Example

```java

@Retry(name = "paymentService", fallbackMethod = "processPaymentFallback")
public Mono<PaymentResponse> processPayment(PaymentRequest request) {
    return paymentGateway.charge(request)
            .doOnError(e -> log.warn("Payment attempt failed: {}", e.getMessage()));
}

public Mono<PaymentResponse> processPaymentFallback(PaymentRequest request, Throwable t) {
    return queueForManualReview(request)
            .map(ticket -> PaymentResponse.pending(ticket));
}
```

#### Common Pitfalls

1. **Retrying non-idempotent operations:** Can cause duplicate charges/emails
2. **Not using exponential backoff:** Can overwhelm recovering services
3. **Infinite retries:** Always set maxAttempts
4. **Ignoring retry costs:** Each retry consumes resources

---

### 2. ⚡ Circuit Breaker Pattern

#### Overview

**Purpose:** Prevent calls to a failing service, giving it time to recover

**When to Use:**

- Protecting against cascading failures
- Service dependencies are unreliable
- Want to fail fast instead of waiting
- Need to prevent resource exhaustion

#### States Explained

```
CLOSED (Normal Operation)
   ↓ (failure rate > threshold)
OPEN (Fast Fail)
   ↓ (wait duration expires)
HALF_OPEN (Testing)
   ↓ (success) or ↓ (failure)
CLOSED         OPEN
```

**CLOSED State:**

- All requests pass through
- Tracks success/failure rates
- Normal operation

**OPEN State:**

- All requests immediately fail (fail-fast)
- No calls to downstream service
- Saves resources and prevents cascading failures

**HALF_OPEN State:**

- Limited test requests allowed
- If successful → transition to CLOSED
- If failed → back to OPEN

#### Basic Usage

```java

@CircuitBreaker(name = "myService", fallbackMethod = "fallback")
public Mono<String> operation() {
    return externalService.call();
}

public Mono<String> fallback(Throwable t) {
    return Mono.just("Circuit breaker fallback");
}
```

#### Configuration

```yaml
resilience4j:
  circuitbreaker:
    instances:
      myService:
        slidingWindowType: COUNT_BASED              # or TIME_BASED
        slidingWindowSize: 10                       # Track last 10 calls
        minimumNumberOfCalls: 5                     # Need 5 calls before evaluating
        failureRateThreshold: 50                    # Open if 50% fail
        slowCallRateThreshold: 50                   # Open if 50% are slow
        slowCallDurationThreshold: 5000ms           # Call is "slow" if > 5s
        waitDurationInOpenState: 5s                 # Stay OPEN for 5s
        permittedNumberOfCallsInHalfOpenState: 2    # Allow 2 test calls
        automaticTransitionFromOpenToHalfOpenEnabled: true
        recordExceptions:
          - java.net.ConnectException
        ignoreExceptions:
          - com.myapp.BusinessException
```

#### How It Works

**Internal Algorithm:**

```java
// Simplified implementation
public class CircuitBreaker {
    private State state = State.CLOSED;
    private final RingBitSet slidingWindow = new RingBitSet(windowSize);
    private int failureCount = 0;

    public <T> T execute(Supplier<T> supplier) {
        if (state == State.OPEN) {
            if (shouldAttemptTransition()) {
                state = State.HALF_OPEN;
            } else {
                throw new CircuitBreakerOpenException();
            }
        }

        try {
            T result = supplier.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onError(e);
            throw e;
        }
    }

    private void onSuccess() {
        slidingWindow.add(true);
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            failureCount = 0;
        }
    }

    private void onError(Exception e) {
        slidingWindow.add(false);
        failureCount++;

        if (failureCount >= minimumNumberOfCalls) {
            double failureRate = (double) failureCount / slidingWindow.size();
            if (failureRate >= failureRateThreshold / 100.0) {
                state = State.OPEN;
                openedAt = System.currentTimeMillis();
            }
        }
    }
}
```

#### Testing

```bash
# Step 1: Trigger failures to open circuit
for i in {1..10}; do
  curl "http://localhost:8080/test-circuit-breaker?shouldFail=true"
  echo ""
done

# Step 2: Verify circuit is open (immediate failure, no delay)
curl "http://localhost:8080/test-circuit-breaker?shouldFail=false"
# Expected: ❌ Fallback: CallNotPermittedException - Circuit breaker is OPEN

# Step 3: Wait for circuit to half-open (5 seconds)
sleep 5

# Step 4: Send successful request to close circuit
curl "http://localhost:8080/test-circuit-breaker?shouldFail=false"
# Expected: ✅ Circuit breaker call succeeded

# Step 5: Continue sending successful requests
for i in {1..5}; do
  curl "http://localhost:8080/test-circuit-breaker?shouldFail=false"
  echo ""
done
# Circuit transitions back to CLOSED state
```

#### Metrics to Monitor

```bash
# Check circuit breaker state
curl http://localhost:8080/actuator/circuitbreakers

# Monitor metrics
curl http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.calls
curl http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.failure.rate
```

#### Real-World Example

```java

@CircuitBreaker(name = "externalApi", fallbackMethod = "getFromCache")
public Mono<ProductData> getProductFromExternalApi(String productId) {
    return webClient.get()
            .uri("/products/{id}", productId)
            .retrieve()
            .bodyToMono(ProductData.class)
            .timeout(Duration.ofSeconds(5))
            .doOnError(e -> log.error("External API failed: {}", e.getMessage()));
}

public Mono<ProductData> getFromCache(String productId, Throwable t) {
    log.warn("Circuit breaker fallback: using cached data for {}", productId);
    return cacheService.get(productId)
            .switchIfEmpty(Mono.error(new ProductNotFoundException(productId)));
}
```

#### Common Pitfalls

1. **Too aggressive thresholds:** Circuit opens unnecessarily
2. **Too lenient thresholds:** Circuit doesn't open when it should
3. **Not enough sliding window:** Need sufficient data for accurate rate
4. **Ignoring HALF_OPEN state:** Should have different handling
5. **No fallback:** Circuit opens but application has no alternative

---

### 3. 🚦 Rate Limiter Pattern

#### Overview

**Purpose:** Limit the number of calls to a service within a time window

**When to Use:**

- Protecting APIs from overload
- Respecting third-party API rate limits
- Fair usage policies (prevent one user monopolizing resources)
- Cost control (limit expensive operations)

#### Types of Rate Limiting

**Sliding Window Algorithm (Resilience4j uses this):**

```
Time:     0s   1s   2s   3s   4s   5s
Window:   [----5s sliding window----]
Limit:    5 calls at any point in 5s
```

Better distribution, no boundary bursts

#### Basic Usage

```java

@RateLimiter(name = "myService", fallbackMethod = "fallback")
public Mono<String> operation() {
    return externalService.call();
}

public Mono<String> fallback(Throwable t) {
    return Mono.just("Rate limit exceeded");
}
```

#### Configuration

```yaml
resilience4j:
  ratelimiter:
    instances:
      myService:
        limitForPeriod: 5                       # Allow 5 calls
        limitRefreshPeriod: 10s                 # Every 10 seconds
        timeoutDuration: 0                      # Don't wait, reject immediately
```

#### How It Works

**Token Bucket Algorithm:**

```java
// Simplified implementation
public class RateLimiter {
    private final int limitForPeriod;
    private final Duration refreshPeriod;
    private AtomicInteger availablePermissions;
    private Instant lastRefreshTime;

    public boolean acquirePermission() {
        refreshIfNeeded();

        if (availablePermissions.get() > 0) {
            availablePermissions.decrementAndGet();
            return true;
        }
        return false;  // Rate limit exceeded
    }

    private void refreshIfNeeded() {
        Instant now = Instant.now();
        if (Duration.between(lastRefreshTime, now)
                .compareTo(refreshPeriod) >= 0) {
            availablePermissions.set(limitForPeriod);
            lastRefreshTime = now;
        }
    }
}
```

#### Testing

```bash
# Send 10 requests rapidly (only first 5 succeed)
for i in {1..10}; do
  curl "http://localhost:8080/test-rate-limiter"
  echo " - Request $i"
done

# Expected Response:
# ✅ Rate limiter allowed request - Request 1
# ✅ Rate limiter allowed request - Request 2
# ✅ Rate limiter allowed request - Request 3
# ✅ Rate limiter allowed request - Request 4
# ✅ Rate limiter allowed request - Request 5
# ❌ Fallback: RequestNotPermitted - Request 6
# ❌ Fallback: RequestNotPermitted - Request 7
# ...

# Wait 10 seconds for rate limit to refresh
sleep 10

# Try again - should work
curl "http://localhost:8080/test-rate-limiter"
# Expected: ✅ Rate limiter allowed request
```

#### Real-World Example

```java

@RateLimiter(name = "aiService", fallbackMethod = "rateLimitExceeded")
public Mono<AiResponse> generateText(String prompt) {
    return aiClient.complete(prompt)
            .doOnNext(response -> metrics.recordApiCall());
}

public Mono<AiResponse> rateLimitExceeded(String prompt, Throwable t) {
    return Mono.just(AiResponse.builder()
            .text("Rate limit exceeded. Please try again in a few seconds.")
            .cached(true)
            .build());
}
```

#### Advanced: Per-User Rate Limiting

```java

@Service
public class UserAwareRateLimiter {
    private final RateLimiterRegistry registry;

    public Mono<Response> processRequest(String userId, Request request) {
        // Create/get rate limiter for this specific user
        RateLimiter limiter = registry.rateLimiter(userId,
                RateLimiterConfig.custom()
                        .limitForPeriod(100)
                        .limitRefreshPeriod(Duration.ofHours(1))
                        .build()
        );

        return Mono.fromCallable(() -> doProcess(request))
                .transformDeferred(RateLimiterOperator.of(limiter));
    }
}
```

---

### 4. 🏊 Bulkhead Pattern

#### Overview

**Purpose:** Isolate resources to prevent resource exhaustion

**When to Use:**

- Preventing one failing service from consuming all resources
- Need to guarantee resources for critical operations
- Want to limit concurrent executions
- Isolating different service calls

#### Types

**1. Semaphore Bulkhead (Thread-based)**

```yaml
maxConcurrentCalls: 10  # Max 10 threads can execute simultaneously
```

**2. ThreadPool Bulkhead**

```yaml
maxThreadPoolSize: 5
coreThreadPoolSize: 2
queueCapacity: 100
```

#### Basic Usage

```java

@Bulkhead(name = "myService", fallbackMethod = "fallback")
public Mono<String> operation() {
    return externalService.call();
}

public Mono<String> fallback(Throwable t) {
    return Mono.just("Bulkhead is full");
}
```

#### Configuration

```yaml
resilience4j:
  bulkhead:
    instances:
      myService:
        maxConcurrentCalls: 2                   # Max 2 concurrent calls
        maxWaitDuration: 100ms                  # Wait up to 100ms for a slot

  thread-pool-bulkhead:
    instances:
      myService:
        maxThreadPoolSize: 4
        coreThreadPoolSize: 2
        queueCapacity: 50
        keepAliveDuration: 20ms
```

#### How It Works

**Resource Isolation:**

```
Without Bulkhead:
[Thread Pool - 100 threads]
├─ Service A calls: 95 threads (DOS attack)
└─ Service B calls: 5 threads (starved!)

With Bulkhead:
[Thread Pool - 100 threads]
├─ Service A Bulkhead: max 50 threads
│   └─ Service A calls: 50 threads (contained)
└─ Service B Bulkhead: max 50 threads
    └─ Service B calls: 50 threads (protected!)
```

**Semaphore Implementation:**

```java
// Simplified
public class SemaphoreBulkhead {
    private final Semaphore semaphore;
    private final Duration maxWaitDuration;

    public <T> T execute(Supplier<T> supplier) throws BulkheadFullException {
        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(maxWaitDuration.toMillis(), MILLISECONDS);
            if (!acquired) {
                throw new BulkheadFullException("Bulkhead is full");
            }
            return supplier.get();
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }
}
```

#### Testing

```bash
# Test 1: Within limit (2 concurrent)
# Open 2 terminal windows and run simultaneously:

# Terminal 1:
curl "http://localhost:8080/test-bulkhead?delayMs=5000"

# Terminal 2 (immediately after):
curl "http://localhost:8080/test-bulkhead?delayMs=5000"

# Both should succeed (may take 5 seconds each)

# Test 2: Exceed limit (3+ concurrent)
# Open 3 terminals and run simultaneously:

# Terminal 1:
curl "http://localhost:8080/test-bulkhead?delayMs=5000"

# Terminal 2:
curl "http://localhost:8080/test-bulkhead?delayMs=5000"

# Terminal 3 (immediately after):
curl "http://localhost:8080/test-bulkhead?delayMs=5000"

# First 2 succeed, 3rd gets:
# ❌ Fallback: BulkheadFullException - Bulkhead is full
```

#### Automated Test

```bash
# Send 5 requests in parallel with 3-second delays
for i in {1..5}; do
  curl "http://localhost:8080/test-bulkhead?delayMs=3000" &
done
wait

# First 2 will process, rest will fail fast
```

#### When to Use Which Type

**Semaphore Bulkhead:**

- ✅ Non-blocking I/O (reactive, async)
- ✅ Memory-efficient
- ✅ Fast context switching
- ❌ Doesn't provide true isolation

**ThreadPool Bulkhead:**

- ✅ True thread isolation
- ✅ Blocking I/O operations
- ✅ CPU-bound tasks
- ❌ Higher memory overhead
- ❌ Context switching cost

#### Real-World Example

```java

@Configuration
public class BulkheadConfig {

    @Bean
    public BulkheadRegistry bulkheadRegistry() {
        return BulkheadRegistry.of(Map.of(
                "database", BulkheadConfig.custom()
                        .maxConcurrentCalls(10)
                        .maxWaitDuration(Duration.ofMillis(500))
                        .build(),
                "externalApi", BulkheadConfig.custom()
                        .maxConcurrentCalls(5)
                        .maxWaitDuration(Duration.ofMillis(100))
                        .build()
        ));
    }
}

@Service
public class DataService {

    @Bulkhead(name = "database", fallbackMethod = "queryFallback")
    public Mono<List<User>> queryUsers(String filter) {
        return userRepository.findByFilter(filter);
    }

    @Bulkhead(name = "externalApi", fallbackMethod = "apiFallback")
    public Mono<ExternalData> fetchFromApi(String id) {
        return externalClient.get(id);
    }
}
```

---

### 5. ⏱️ Time Limiter Pattern

#### Overview

**Purpose:** Limit the time an operation can run before timing out

**When to Use:**

- Unbounded operations that might hang
- Operations with unpredictable duration
- Need to guarantee response time SLAs
- Preventing resource locks

#### Important: Reactive vs Blocking

```java
// ❌ WRONG: TimeLimiter doesn't work with Mono
@TimeLimiter(name = "myService")
public Mono<String> operation() {
    return Mono.delay(Duration.ofSeconds(5))
            .map(i -> "Done");
}

// ✅ CORRECT: Use with CompletableFuture
@TimeLimiter(name = "myService")
public CompletableFuture<String> operation() {
    return CompletableFuture.supplyAsync(() -> {
        // blocking operation
        return "Done";
    });
}

// ✅ ALTERNATIVE: Use reactive timeout()
public Mono<String> operation() {
    return Mono.delay(Duration.ofSeconds(5))
            .timeout(Duration.ofSeconds(3))
            .onErrorResume(TimeoutException.class, e ->
                    Mono.just("Timeout fallback"));
}
```

#### Basic Usage

```java

@TimeLimiter(name = "myService", fallbackMethod = "fallback")
public CompletableFuture<String> operation() {
    return CompletableFuture.supplyAsync(() -> {
        // long-running operation
        return result;
    });
}

public CompletableFuture<String> fallback(Throwable t) {
    return CompletableFuture.completedFuture("Timeout fallback");
}
```

#### Configuration

```yaml
resilience4j:
  timelimiter:
    instances:
      myService:
        timeoutDuration: 3s                     # Timeout after 3 seconds
        cancelRunningFuture: true               # Cancel the Future on timeout
```

#### How It Works

**Internal Algorithm:**

```java
// Simplified
public class TimeLimiter {
    private final Duration timeout;
    private final ScheduledExecutorService scheduler;

    public <T> CompletableFuture<T> execute(Supplier<CompletableFuture<T>> supplier) {
        CompletableFuture<T> future = supplier.get();

        CompletableFuture<T> timeoutFuture = new CompletableFuture<>();
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            timeoutFuture.completeExceptionally(new TimeoutException());
            if (cancelRunningFuture) {
                future.cancel(true);
            }
        }, timeout.toMillis(), MILLISECONDS);

        future.whenComplete((result, error) -> {
            timeoutTask.cancel(false);
            if (error != null) {
                timeoutFuture.completeExceptionally(error);
            } else {
                timeoutFuture.complete(result);
            }
        });

        return timeoutFuture;
    }
}
```

#### Testing

```bash
# Test 1: Within timeout (succeeds)
curl "http://localhost:8080/test-time-limiter?delayMs=2000"
# Expected (after 2 seconds):
# ✅ Time limiter completed in 2000ms

# Test 2: Exceeds timeout (fallback)
curl "http://localhost:8080/test-time-limiter?delayMs=5000"
# Expected (after 3 seconds):
# ❌ Time Limiter Fallback: TimeoutException - Operation exceeded timeout

# Test 3: Edge cases
curl "http://localhost:8080/test-time-limiter?delayMs=3000"  # At boundary
curl "http://localhost:8080/test-time-limiter?delayMs=2900"  # Just under
curl "http://localhost:8080/test-time-limiter?delayMs=10000" # Well over
```

#### Real-World Example

```java

@Service
public class ReportService {

    @TimeLimiter(name = "reportGeneration", fallbackMethod = "queueForAsyncGeneration")
    public CompletableFuture<Report> generateReport(ReportRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            // CPU-intensive report generation
            return reportGenerator.generate(request);
        });
    }

    public CompletableFuture<Report> queueForAsyncGeneration(ReportRequest request, Throwable t) {
        // Report generation takes too long, queue it
        String jobId = jobQueue.enqueue(request);
        return CompletableFuture.completedFuture(
                Report.pending(jobId, "Report queued for background processing")
        );
    }
}
```

---

## Testing Guide

### Testing All Patterns Together

```bash
# This endpoint has ALL patterns applied
curl "http://localhost:8080/test-all?delayMs=500"

# Expected: ✅ Success with 500ms delay: {...}
```

### Advanced Testing Scenarios

#### Scenario 1: Cascading Failures

```bash
# 1. Saturate bulkhead
for i in {1..5}; do curl "http://localhost:8080/test-bulkhead?delayMs=10000" & done

# 2. While saturated, make more calls to trigger circuit breaker
for i in {1..10}; do curl "http://localhost:8080/test-circuit-breaker?shouldFail=true"; done

# 3. Circuit should open, protecting the service
```

#### Scenario 2: Recovery Testing

```bash
# 1. Break the circuit
for i in {1..10}; do curl "http://localhost:8080/test-circuit-breaker?shouldFail=true"; done

# 2. Wait for half-open state (5 seconds)
sleep 5

# 3. Send successful request to close circuit
curl "http://localhost:8080/test-circuit-breaker?shouldFail=false"

# 4. Verify circuit is closed
for i in {1..5}; do curl "http://localhost:8080/test-circuit-breaker?shouldFail=false"; done
```

#### Scenario 3: Load Testing

```bash
# Use Apache Bench
ab -n 1000 -c 10 "http://localhost:8080/test-rate-limiter"

# Or with hey
hey -n 1000 -c 10 http://localhost:8080/test-rate-limiter

# Or with curl in parallel
seq 1 100 | xargs -P 20 -I {} curl -s "http://localhost:8080/test-rate-limiter"
```

#### Scenario 4: Combined Patterns Testing

```bash
# Test Scenario: Rate Limiter + Retry
for i in {1..5}; do
  curl "http://localhost:8080/test-all?delayMs=100"
  echo " - Request $i"
done
# First 2 succeed (rate limit), next 3 fail fast

# Test Scenario: Bulkhead + Circuit Breaker
# Terminal 1-3: Saturate bulkhead
for i in {1..3}; do
  curl "http://localhost:8080/test-all?delayMs=5000" &
done

# Terminal 4-10: Trigger circuit breaker
for i in {4..10}; do
  curl "http://localhost:8080/test-all?delayMs=100" &
done
wait
```

---

## Configuration Reference

### Complete Configuration Example

```yaml
spring:
  application:
    name: resilience4j-patterns-playground

resilience4j:
  # ==================== RETRY ====================
  retry:
    instances:
      # Main service instance
      mainService:
        maxAttempts: 3
        waitDuration: 1s
        enableExponentialBackoff: false
        retryExceptions:
          - java.lang.RuntimeException

      # Retry service with exponential backoff
      retryService:
        maxAttempts: 3
        waitDuration: 500ms
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
        retryExceptions:
          - java.lang.RuntimeException

  # ==================== CIRCUIT BREAKER ====================
  circuitbreaker:
    instances:
      # Main service instance
      mainService:
        slidingWindowSize: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
        minimumNumberOfCalls: 3
        automaticTransitionFromOpenToHalfOpenEnabled: true

      # Circuit breaker service with custom settings
      circuitBreakerService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 5s
        permittedNumberOfCallsInHalfOpenState: 2
        minimumNumberOfCalls: 5
        automaticTransitionFromOpenToHalfOpenEnabled: true
        recordExceptions:
          - java.lang.RuntimeException
        ignoreExceptions:
          - java.lang.IllegalArgumentException

  # ==================== RATE LIMITER ====================
  ratelimiter:
    instances:
      # Main service instance
      mainService:
        limitForPeriod: 2
        limitRefreshPeriod: 10s
        timeoutDuration: 0

      # Rate limiter service
      rateLimiterService:
        limitForPeriod: 5
        limitRefreshPeriod: 10s
        timeoutDuration: 0ms

  # ==================== BULKHEAD ====================
  bulkhead:
    instances:
      # Main service instance
      mainService:
        maxConcurrentCalls: 3
        maxWaitDuration: 0

      # Bulkhead service
      bulkheadService:
        maxConcurrentCalls: 2
        maxWaitDuration: 100ms

  # ==================== TIME LIMITER ====================
  timelimiter:
    instances:
      # Main service instance
      mainService:
        timeoutDuration: 2s
        cancelRunningFuture: true

      # Time limiter service
      timeLimiterService:
        timeoutDuration: 3s
        cancelRunningFuture: true

# ==================== LOGGING ====================
logging:
  level:
    io.github.resilience4j: DEBUG
    dev.dead.resillience4jpatternsplayground: DEBUG
```

### Quick Copy-Paste Configs

#### Ultra-Reliable Service

```yaml
resilience4j:
  retry:
    instances:
      ultraReliable:
        maxAttempts: 5
        waitDuration: 2s
        enableExponentialBackoff: true
  circuitbreaker:
    instances:
      ultraReliable:
        slidingWindowSize: 20
        failureRateThreshold: 30
        waitDurationInOpenState: 30s
  ratelimiter:
    instances:
      ultraReliable:
        limitForPeriod: 100
        limitRefreshPeriod: 1s
  bulkhead:
    instances:
      ultraReliable:
        maxConcurrentCalls: 10
```

#### Fast-Fail Service

```yaml
resilience4j:
  circuitbreaker:
    instances:
      fastFail:
        slidingWindowSize: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 5s
  timelimiter:
    instances:
      fastFail:
        timeoutDuration: 1s
```

#### Rate-Limited API

```yaml
resilience4j:
  ratelimiter:
    instances:
      externalApi:
        limitForPeriod: 10
        limitRefreshPeriod: 1s
        timeoutDuration: 0
  retry:
    instances:
      externalApi:
        maxAttempts: 3
        waitDuration: 1s
  circuitbreaker:
    instances:
      externalApi:
        slidingWindowSize: 10
        failureRateThreshold: 60
        waitDurationInOpenState: 20s
```

---

## Pattern Deep Dive

### Combining Patterns

#### Annotation Order (Outside to Inside)

```java

@Retry           // 1. Outermost - retries everything
@CircuitBreaker  // 2. Opens if rate limited
@RateLimiter     // 3. Throttles requests
@Bulkhead        // 4. Controls concurrency
public Mono<String> operation() { ...}

// Note: TimeLimiter requires CompletableFuture, not Mono
```

#### Common Combinations

**Critical Service (High Availability)**

```java

@Retry
@CircuitBreaker
@RateLimiter
@Bulkhead
public Mono<String> criticalOperation() { ...}
```

**Non-Critical Service (Fail Fast)**

```java

@CircuitBreaker
@TimeLimiter
public CompletableFuture<String> nonCriticalOperation() { ...}
```

**High-Volume Service (Protection)**

```java

@RateLimiter
@Bulkhead
@CircuitBreaker
public Mono<String> highVolumeOperation() { ...}
```

### Implementation Internals

#### How Annotations Work

```java
// 1. Spring AOP creates proxy
@Service
public class MyService {
    @Retry(name = "myRetry")
    public String doSomething() { ...}
}

// 2. Proxy intercepts method calls
public class MyServiceProxy extends MyService {
    private final RetryRegistry retryRegistry;

    @Override
    public String doSomething() {
        Retry retry = retryRegistry.retry("myRetry");
        return retry.executeSupplier(() -> super.doSomething());
    }
}
```

#### Reactive Integration

```java
// Manual decoration (alternative to annotations)
public Mono<String> operation() {
    return Mono.fromSupplier(() -> "result")
            .transformDeferred(RetryOperator.of(retry))
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .transformDeferred(RateLimiterOperator.of(rateLimiter))
            .transformDeferred(BulkheadOperator.of(bulkhead))
            .timeout(Duration.ofSeconds(5));
}
```

---

## Monitoring & Debugging

### Actuator Endpoints

```bash
# Health check (includes resilience4j status)
curl http://localhost:8080/actuator/health

# Circuit breaker metrics
curl http://localhost:8080/actuator/circuitbreakers

# Circuit breaker events
curl http://localhost:8080/actuator/circuitbreakerevents

# Rate limiter metrics
curl http://localhost:8080/actuator/ratelimiters

# Bulkhead metrics
curl http://localhost:8080/actuator/bulkheads

# Prometheus metrics
curl http://localhost:8080/actuator/prometheus

# Specific metrics
curl http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.calls
curl http://localhost:8080/actuator/metrics/resilience4j.retry.calls
curl http://localhost:8080/actuator/metrics/resilience4j.ratelimiter.available.permissions
curl http://localhost:8080/actuator/metrics/resilience4j.bulkhead.available.concurrent.calls
```

### Enable Actuator Endpoints

Add to `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics,circuitbreakers,circuitbreakerevents,ratelimiters,bulkheads
  endpoint:
    health:
      show-details: always
  health:
    circuitbreakers:
      enabled: true
    ratelimiters:
      enabled: true
```

### Metrics Configuration

```java

@Configuration
public class MetricsConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCustomizer() {
        return registry -> {
            CircuitBreakerRegistry cbRegistry = ...;
            RetryRegistry retryRegistry = ...;

            // Export circuit breaker metrics
            CircuitBreakerMetrics.ofCircuitBreakerRegistry(cbRegistry)
                    .bindTo(registry);

            // Export retry metrics
            RetryMetrics.ofRetryRegistry(retryRegistry)
                    .bindTo(registry);

            // Custom alerts
            cbRegistry.getAllCircuitBreakers()
                    .forEach(cb -> {
                        cb.getEventPublisher()
                                .onStateTransition(event -> {
                                    if (event.getStateTransition()
                                            .getToState() == State.OPEN) {
                                        alerting.sendAlert("Circuit opened: " + cb.getName());
                                    }
                                });
                    });
        };
    }
}
```

### Key Metrics to Monitor

| Pattern         | Key Metrics                                                                    |
|-----------------|--------------------------------------------------------------------------------|
| Retry           | `retry.calls`, `retry.calls.failed`                                            |
| Circuit Breaker | `circuitbreaker.state`, `circuitbreaker.failure.rate`                          |
| Rate Limiter    | `ratelimiter.available.permissions`, `ratelimiter.waiting.threads`             |
| Bulkhead        | `bulkhead.available.concurrent.calls`, `bulkhead.max.allowed.concurrent.calls` |
| Time Limiter    | `timelimiter.calls`, `timelimiter.calls.timeout`                               |

---

## Best Practices

### 1. Configuration Tuning

```yaml
# ❌ BAD: One-size-fits-all
resilience4j:
  retry:
    instances:
      default:
        maxAttempts: 3

# ✅ GOOD: Per-service configuration
resilience4j:
  retry:
    instances:
      paymentService: # Critical, retry aggressively
        maxAttempts: 5
        waitDuration: 2s
      analyticsService: # Non-critical, fail fast
        maxAttempts: 2
        waitDuration: 100ms
```

### 2. Fallback Strategies

```java
// ❌ BAD: Generic fallback
public Mono<Data> fallback(Throwable t) {
    return Mono.empty();  // What went wrong?
}

// ✅ GOOD: Specific fallbacks with context
public Mono<Data> fallback(String userId, SearchParams params, Throwable t) {
    log.warn("Search failed for user={}, params={}, error={}",
            userId, params, t.getMessage());

    if (t instanceof RateLimitExceededException) {
        return getCachedResults(userId);
    } else if (t instanceof CircuitBreakerOpenException) {
        return getDefaultResults();
    } else {
        metrics.recordSearchFailure(t.getClass()
                .getSimpleName());
        return Mono.error(new ServiceUnavailableException());
    }
}
```

### 3. Testing

```java

@SpringBootTest
class ResilienceTest {

    @Autowired
    private CircuitBreakerRegistry cbRegistry;

    @Test
    void testCircuitBreakerOpens() {
        CircuitBreaker cb = cbRegistry.circuitBreaker("testService");

        // Transition to open
        CircuitBreakerTestUtils.transitionToOpenState(cb);

        assertThat(cb.getState()).isEqualTo(State.OPEN);

        // Verify fast-fail
        assertThatThrownBy(() -> service.call())
                .isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void testRateLimiter() {
        // Make limitForPeriod + 1 calls
        IntStream.range(0, 6)
                .forEach(i -> service.call());

        // Verify rejection
        assertThatThrownBy(() -> service.call())
                .isInstanceOf(RequestNotPermitted.class);
    }
}
```

### 4. General Guidelines

1. **Start Simple** - Implement one pattern at a time
2. **Monitor Everything** - Use metrics to understand behavior
3. **Test Failures** - Practice chaos engineering
4. **Tune Based on Data** - Use real metrics, not guesses
5. **Document Decisions** - Why these thresholds?
6. **Provide Fallbacks** - Always have a backup plan
7. **Consider UX** - What does user see when patterns trigger?
8. **Think About Cost** - Each pattern adds overhead

### When to Use Each Pattern

| Pattern             | Use When                  | Don't Use When       |
|---------------------|---------------------------|----------------------|
| **Retry**           | Transient failures        | Non-idempotent ops   |
| **Circuit Breaker** | Service dependencies      | Single failures      |
| **Rate Limiter**    | Protect from overload     | Internal services    |
| **Bulkhead**        | Resource isolation needed | Single-threaded apps |
| **Time Limiter**    | Unbounded operations      | Reactive streams     |

---

## Troubleshooting

### Issue: Fallback not called

**Problem:** Fallback method is not being invoked when expected

**Solution:** Ensure fallback method signature matches:

- Same return type (Mono<String> or CompletableFuture<String>)
- Same parameters + Throwable
- Must be in same class or specify full method path

```java
// Original method
public Mono<String> operation(String param) { ...}

// Fallback MUST match + Throwable
public Mono<String> fallback(String param, Throwable t) { ...}
```

### Issue: TimeLimiter doesn't work with Mono

**Problem:** TimeLimiter annotation has no effect on reactive methods

**Solution:** TimeLimiter only works with CompletableFuture/Future

```java
// ❌ WRONG
@TimeLimiter(name = "myService")
public Mono<String> operation() { ...}

// ✅ CORRECT
@TimeLimiter(name = "myService")
public CompletableFuture<String> operation() { ...}

// ✅ ALTERNATIVE for Reactive
public Mono<String> operation() {
    return Mono.delay(Duration.ofSeconds(5))
            .timeout(Duration.ofSeconds(3));
}
```

### Issue: Circuit breaker doesn't open

**Problem:** Circuit breaker stays closed despite failures

**Solution:** Check configuration requirements:

```yaml
circuitbreaker:
  instances:
    myService:
      minimumNumberOfCalls: 5      # Need 5 calls before evaluation
      slidingWindowSize: 10        # Must be >= minimumNumberOfCalls
      failureRateThreshold: 50     # Need 50% failure rate
```

Make sure:

1. You've made at least `minimumNumberOfCalls` requests
2. Failure rate exceeds `failureRateThreshold`
3. `slidingWindowSize` is appropriate for your use case

### Issue: Rate limiter not resetting

**Problem:** Rate limiter doesn't allow new requests after waiting

**Solution:** Must wait full refresh period

```yaml
ratelimiter:
  instances:
    myService:
      limitRefreshPeriod: 10s  # Must wait full 10 seconds
```

Check:

- System time isn't drifting
- Actually waiting the full `limitRefreshPeriod`
- No timezone issues

### Issue: Bulkhead always full

**Problem:** Bulkhead rejects all requests

**Solution:** Check concurrent call settings and wait duration

```yaml
bulkhead:
  instances:
    myService:
      maxConcurrentCalls: 2      # Very low, increase if needed
      maxWaitDuration: 100ms     # May need to increase
```

Consider:

- Increasing `maxConcurrentCalls`
- Increasing `maxWaitDuration` to allow queueing
- Check if operations are taking too long

### Issue: Retries not working

**Problem:** Operations fail without retry attempts

**Solution:** Check exception configuration

```yaml
retry:
  instances:
    myService:
      retryExceptions: # Only retries these
        - java.net.ConnectException
      ignoreExceptions: # Never retries these
        - java.lang.IllegalArgumentException
```

Make sure:

- Your exception is in `retryExceptions` or not in `ignoreExceptions`
- `maxAttempts` is > 1

### Issue: Patterns not being applied

**Problem:** Annotations seem to have no effect

**Solution:** Check these requirements:

1. **Spring AOP enabled:**

```xml

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

2. **Method must be public**
3. **Class must be a Spring bean** (@Service, @Component, etc.)
4. **Can't call annotated method from within same class** (AOP proxy limitation)

```java
// ❌ WRONG - Won't work
@Service
public class MyService {
    public void publicMethod() {
        internalRetryMethod();  // AOP won't intercept this
    }

    @Retry
    private void internalRetryMethod() { ...}
}

// ✅ CORRECT
@Service
public class MyService {
    @Retry
    public void retryMethod() { ...}
}
```

---

## Real-World Use Cases

### Use Case 1: E-Commerce Checkout

```java

@Service
public class CheckoutService {

    // Payment gateway - retry for transient failures
    @Retry(name = "paymentGateway")
    @CircuitBreaker(name = "paymentGateway", fallbackMethod = "queuePayment")
    @TimeLimiter(name = "paymentGateway")
    public CompletableFuture<PaymentResult> processPayment(Order order) {
        return paymentClient.charge(order);
    }

    // Inventory check - fail fast with rate limit
    @RateLimiter(name = "inventory")
    @Bulkhead(name = "inventory")
    public Mono<InventoryStatus> checkInventory(List<String> skus) {
        return inventoryService.checkAvailability(skus);
    }

    // Email notification - non-critical, aggressive circuit breaker
    @CircuitBreaker(
            name = "emailService",
            fallbackMethod = "queueEmail",
            recordExceptions = {SmtpException.class}
    )
    public Mono<Void> sendOrderConfirmation(Order order) {
        return emailService.send(order.getCustomerEmail(),
                orderConfirmationTemplate(order));
    }

    public CompletableFuture<PaymentResult> queuePayment(Order order, Throwable t) {
        String jobId = paymentQueue.enqueue(order);
        return CompletableFuture.completedFuture(
                PaymentResult.queued(jobId)
        );
    }

    public Mono<Void> queueEmail(Order order, Throwable t) {
        emailQueue.enqueue(order);
        return Mono.empty();
    }
}
```

### Use Case 2: Microservices API Gateway

```java

@Service
public class ApiGateway {

    // User service - critical, comprehensive protection
    @Retry(name = "userService")
    @CircuitBreaker(name = "userService", fallbackMethod = "getCachedUser")
    @RateLimiter(name = "userService")
    @Bulkhead(name = "userService")
    public Mono<User> getUser(String userId) {
        return userServiceClient.getUser(userId);
    }

    // Recommendations - non-critical, fail fast
    @CircuitBreaker(name = "recommendations", fallbackMethod = "getDefaultRecommendations")
    @TimeLimiter(name = "recommendations")
    public CompletableFuture<List<Product>> getRecommendations(String userId) {
        return mlServiceClient.getRecommendations(userId);
    }

    // Analytics - best effort, no retry
    @CircuitBreaker(name = "analytics", fallbackMethod = "ignoreAnalytics")
    public Mono<Void> trackEvent(Event event) {
        return analyticsClient.track(event);
    }

    public Mono<User> getCachedUser(String userId, Throwable t) {
        return cacheService.getUser(userId)
                .switchIfEmpty(Mono.just(User.anonymous()));
    }

    public CompletableFuture<List<Product>> getDefaultRecommendations(String userId, Throwable t) {
        return CompletableFuture.completedFuture(defaultProducts);
    }

    public Mono<Void> ignoreAnalytics(Event event, Throwable t) {
        log.debug("Analytics unavailable, event dropped");
        return Mono.empty();
    }
}
```

### Use Case 3: Data Pipeline

```java

@Service
public class DataPipeline {

    // External API - rate limited by provider
    @RateLimiter(name = "externalApi")  // Match provider's limits
    @Retry(name = "externalApi")        // Handle 429 responses
    @CircuitBreaker(name = "externalApi")
    public Mono<ApiData> fetchFromExternalApi(String resource) {
        return externalClient.get(resource);
    }

    // Database writes - bulkhead to prevent overwhelming DB
    @Bulkhead(name = "databaseWrites")
    @Retry(name = "databaseWrites")     // Retry deadlocks
    public Mono<Void> writeBatch(List<Record> records) {
        return repository.saveAll(records);
    }

    // Heavy transformation - time limit to prevent runaway processes
    @TimeLimiter(name = "transformation", fallbackMethod = "skipTransformation")
    @Bulkhead(name = "transformation")  // CPU isolation
    public CompletableFuture<TransformedData> transform(RawData data) {
        return CompletableFuture.supplyAsync(() ->
                expensiveTransformation(data)
        );
    }

    public CompletableFuture<TransformedData> skipTransformation(RawData data, Throwable t) {
        log.warn("Transformation timeout, skipping: {}", data.getId());
        return CompletableFuture.completedFuture(TransformedData.skipped(data));
    }
}
```

### Pattern Combinations Summary

```
Critical Service (High Availability):
├─ Retry (handle transient failures)
├─ Circuit Breaker (prevent cascading)
├─ Rate Limiter (respect limits)
└─ Bulkhead (isolate resources)

Non-Critical Service (Fail Fast):
├─ Circuit Breaker (fail fast)
└─ Time Limiter (prevent hanging)

High-Volume Service (Protection):
├─ Rate Limiter (throttle)
├─ Bulkhead (isolate)
└─ Circuit Breaker (protect)
```

---

## Dependencies

### Maven (pom.xml)

```xml

<properties>
    <java.version>17</java.version>
    <resilience4j.version>2.1.0</resilience4j.version>
</properties>

<dependencies>
<!-- Spring Boot Starters -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>

<!-- Resilience4j Dependencies -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>${resilience4j.version}</version>
</dependency>

<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-reactor</artifactId>
    <version>${resilience4j.version}</version>
</dependency>

<!-- Individual Pattern Modules -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-circuitbreaker</artifactId>
    <version>${resilience4j.version}</version>
</dependency>

<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-retry</artifactId>
    <version>${resilience4j.version}</version>
</dependency>

<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-ratelimiter</artifactId>
    <version>${resilience4j.version}</version>
</dependency>

<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-bulkhead</artifactId>
    <version>${resilience4j.version}</version>
</dependency>

<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-timelimiter</artifactId>
    <version>${resilience4j.version}</version>
</dependency>

<!-- Actuator for monitoring -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
</dependencies>
```

### Gradle (build.gradle)

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-webflux'
    implementation 'org.springframework.boot:spring-boot-starter-aop'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.1.0'
    implementation 'io.github.resilience4j:resilience4j-reactor:2.1.0'
    implementation 'io.github.resilience4j:resilience4j-circuitbreaker:2.1.0'
    implementation 'io.github.resilience4j:resilience4j-retry:2.1.0'
    implementation 'io.github.resilience4j:resilience4j-ratelimiter:2.1.0'
    implementation 'io.github.resilience4j:resilience4j-bulkhead:2.1.0'
    implementation 'io.github.resilience4j:resilience4j-timelimiter:2.1.0'

    implementation 'io.micrometer:micrometer-registry-prometheus'
}
```

---

## Decision Tree

```
Is this a critical operation?
├─ YES
│   └─ Use: Retry + CircuitBreaker + RateLimiter + Bulkhead
└─ NO
    └─ Is it time-sensitive?
        ├─ YES → Use: TimeLimiter + CircuitBreaker
        └─ NO → Use: CircuitBreaker only

Does it call external service?
├─ YES
│   └─ Has rate limits? → Add RateLimiter
│   └─ Unreliable? → Add Retry + CircuitBreaker
└─ NO
    └─ CPU intensive? → Add Bulkhead + TimeLimiter

Could it cascade failures?
└─ YES → Add CircuitBreaker + Bulkhead

High volume expected?
└─ YES → Add RateLimiter + Bulkhead
```

---

## Key Takeaways

1. **Start with Circuit Breaker** - Most important pattern for microservices
2. **Add Retry for transient failures** - Network blips, timeouts
3. **Use Rate Limiter for external APIs** - Respect provider limits
4. **Apply Bulkhead for resource isolation** - Prevent one bad service from killing all
5. **Add TimeLimiter for unbounded operations** - Prevent hangs (use CompletableFuture!)
6. **Always provide fallbacks** - Don't just fail
7. **Monitor metrics** - Know when patterns trigger
8. **Test failure scenarios** - Chaos engineering
9. **Tune based on data** - Not guesses
10. **Document decisions** - Why these thresholds?

---

## Further Reading

- [Official Resilience4j Documentation](https://resilience4j.readme.io/)
- [GitHub Repository](https://github.com/resilience4j/resilience4j)
- [Spring Boot Integration Guide](https://resilience4j.readme.io/docs/getting-started-3)
- [Micrometer Metrics Integration](https://resilience4j.readme.io/docs/micrometer)
- [Release It! by Michael Nygard](https://pragprog.com/titles/mnee2/release-it-second-edition/)
- [Building Microservices by Sam Newman](https://www.oreilly.com/library/view/building-microservices-2nd/9781492034018/)
- [Site Reliability Engineering by Google](https://sre.google/books/)

---

## License

This is a demo/educational project. Feel free to use and modify as needed.

---

**Happy Testing! 🚀**