# Resilience4j Patterns - Test Suite

This directory contains comprehensive tests for all Resilience4j patterns.

## Running Tests

### Run all tests

```bash
mvn test
```

### Run specific test class

```bash
mvn test -Dtest=ResiliencePatternTests
```

### Run specific test method

```bash
mvn test -Dtest=ResiliencePatternTests#testRetry_shouldSucceedAfterRetries
```

### Run with verbose output

```bash
mvn test -X
```

## Test Coverage

### 1. Context Loading Tests

- **Resillience4jPatternsPlaygroundApplicationTests**
    - Verifies Spring context loads with all Resilience4j configurations
    - Tests that all beans are properly wired

### 2. Pattern-Specific Tests

- **ResiliencePatternTests** - Comprehensive test suite covering:

#### Retry Pattern Tests

- ✅ `testRetry_shouldSucceedAfterRetries` - Verifies retry mechanism works
- ✅ `testRetry_shouldInvokeFallbackAfterMaxAttempts` - Tests fallback invocation
- ✅ `testRetry_viaHttpEndpoint` - Tests via HTTP endpoint

#### Circuit Breaker Pattern Tests

- ✅ `testCircuitBreaker_shouldOpenAfterFailures` - Verifies circuit opens after threshold
- ✅ `testCircuitBreaker_shouldFailFastWhenOpen` - Tests fast-fail behavior
- ✅ `testCircuitBreaker_shouldTransitionToHalfOpen` - Tests state transitions
- ✅ `testCircuitBreaker_viaHttpEndpoint` - Tests via HTTP endpoint

#### Rate Limiter Pattern Tests

- ✅ `testRateLimiter_shouldLimitRequests` - Verifies rate limiting works
- ✅ `testRateLimiter_shouldAllowRequestsAfterRefresh` - Tests refresh period
- ✅ `testRateLimiter_viaHttpEndpoint` - Tests via HTTP endpoint

#### Bulkhead Pattern Tests

- ✅ `testBulkhead_shouldLimitConcurrentCalls` - Verifies concurrency limits
- ✅ `testBulkhead_shouldAllowCallsAfterCompletion` - Tests slot release
- ✅ `testBulkhead_viaHttpEndpoint` - Tests via HTTP endpoint

#### Time Limiter Pattern Tests

- ✅ `testTimeLimiter_shouldCompleteWithinTimeout` - Tests successful completion
- ✅ `testTimeLimiter_shouldTimeoutExceedingDelay` - Tests timeout behavior
- ✅ `testTimeLimiter_viaHttpEndpoint_withinTimeout` - Tests via HTTP (success)
- ✅ `testTimeLimiter_viaHttpEndpoint_exceedsTimeout` - Tests via HTTP (timeout)

#### Combined Patterns Tests

- ✅ `testCombinedPatterns_shouldApplyAllPatterns` - Tests multiple patterns together
- ✅ `testCombinedPatterns_shouldRespectRateLimit` - Tests pattern interaction

#### Monitoring Tests

- ✅ `testActuatorHealth` - Verifies health endpoint
- ✅ `testCircuitBreakerActuator` - Tests circuit breaker metrics endpoint
- ✅ `testMetricsEndpoint` - Verifies metrics endpoint

#### Integration Tests

- ✅ `testAllEndpoints_shouldReturnValidResponses` - Tests all HTTP endpoints
- ✅ `testFallbackMethods_areInvoked` - Verifies fallback mechanisms

## Test Configuration

Tests use `application-test.yml` with optimized settings for faster execution:

- Shorter timeouts (2s instead of 10s)
- Smaller sliding windows
- Faster refresh periods
- Reduced wait durations

## Test Dependencies

The following test dependencies are included:

```xml
<!-- Core Spring Boot Test -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>

        <!-- Reactor Test Support -->
<dependency>
<groupId>io.projectreactor</groupId>
<artifactId>reactor-test</artifactId>
<scope>test</scope>
</dependency>

        <!-- Resilience4j Test Utilities -->
<dependency>
<groupId>io.github.resilience4j</groupId>
<artifactId>resilience4j-test</artifactId>
<scope>test</scope>
</dependency>

        <!-- Awaitility for Async Testing -->
<dependency>
<groupId>org.awaitility</groupId>
<artifactId>awaitility</artifactId>
<scope>test</scope>
</dependency>
```

## Test Utilities Used

### 1. StepVerifier (Reactor Test)

Used for testing reactive Mono/Flux streams:

```java
StepVerifier.create(result)
    .

expectNextMatches(response ->response.

contains("success"))
        .

verifyComplete();
```

### 2. Awaitility

Used for testing asynchronous behavior with timeouts:

```java
await().

atMost(5,TimeUnit.SECONDS)
    .

untilAsserted(() ->

assertThat(circuitBreaker.getState()).

isEqualTo(State.OPEN)
    );
```

### 3. WebTestClient

Used for testing HTTP endpoints:

```java
webTestClient.get()
    .

uri("/test-retry?shouldFail=true")
    .

exchange()
    .

expectStatus().

isOk()
    .

expectBody(String .class)
    .

value(body ->

assertThat(body).

contains("success"));
```

### 4. AssertJ

Used for fluent assertions:

```java
assertThat(circuitBreaker.getState()).

isEqualTo(State.CLOSED);
```

## Common Test Patterns

### Testing Circuit Breaker State Transitions

```java
// 1. Trigger failures to open circuit
IntStream.range(0,10).

forEach(i ->{
        try{
        service.

call().

block();
    }catch(
Exception e){
        // Expected
        }
        });

// 2. Wait for state change
await().

atMost(2,TimeUnit.SECONDS)
    .

untilAsserted(() ->

assertThat(cb.getState()).

isEqualTo(State.OPEN)
    );
```

### Testing Rate Limiting

```java
// 1. Exhaust rate limit
IntStream.range(0,10).

forEach(i ->{
        try{
        service.

call().

block();
    }catch(
Exception e){
        // Some will be rate limited
        }
        });

// 2. Verify permissions exhausted
assertThat(rateLimiter.getMetrics().

getAvailablePermissions())
        .

isEqualTo(0);

// 3. Wait for refresh
Thread.

sleep(refreshPeriodMs);

// 4. Verify permissions restored
assertThat(rateLimiter.getMetrics().

getAvailablePermissions())
        .

isGreaterThan(0);
```

### Testing Concurrent Bulkhead

```java
// 1. Start concurrent operations
CompletableFuture<String> call1 = service.call(2000)
                .toFuture();
CompletableFuture<String> call2 = service.call(2000)
        .toFuture();
Thread.

sleep(100);

CompletableFuture<String> call3 = service.call(2000)
        .toFuture();

// 2. Verify concurrency limit
assertThat(bulkhead.getMetrics().

getAvailableConcurrentCalls())
        .

isLessThanOrEqualTo(maxConcurrent);

// 3. Wait for completion
await().

atMost(3,TimeUnit.SECONDS)
    .

until(() ->call1.

isDone() &&call2.

isDone());
```

## Troubleshooting Test Failures

### Tests timing out

- Increase timeouts in `application-test.yml`
- Check if external services (jsonplaceholder) are accessible
- Use `-X` flag for verbose Maven output

### Flaky tests

- Some tests involve timing and state transitions
- May occasionally fail due to system load
- Re-run failed tests to verify consistency

### Circuit breaker not opening

- Ensure `minimumNumberOfCalls` threshold is met
- Check that failure rate exceeds threshold
- Verify sliding window configuration

### Rate limiter tests failing

- Ensure sufficient wait time for refresh period
- Check system clock isn't drifting
- Verify rate limit configuration in test properties

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Tests
on: [ push, pull_request ]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - uses: actions/setup-java@v2
        with:
          java-version: '21'
      - run: mvn test
```

### Maven Surefire Configuration

Already configured in `pom.xml` via spring-boot-maven-plugin.

## Test Reports

After running tests, reports are available at:

- **Surefire Reports:** `target/surefire-reports/`
- **HTML Reports:** `target/site/surefire-report.html`

Generate HTML report:

```bash
mvn surefire-report:report
```

## Code Coverage

To generate code coverage report with JaCoCo:

```bash
mvn test jacoco:report
```

View report at: `target/site/jacoco/index.html`

## Best Practices

1. **Reset state between tests** - Use `@BeforeEach` to reset circuit breakers and other stateful components
2. **Use appropriate timeouts** - Configure realistic timeouts for async operations
3. **Test both success and failure paths** - Verify fallbacks are invoked
4. **Test state transitions** - Especially for circuit breakers
5. **Use meaningful assertions** - Test the actual behavior, not just that code runs
6. **Isolate external dependencies** - Tests shouldn't rely on external services being available

## Writing New Tests

When adding new tests:

1. Add test method to `ResiliencePatternTests`
2. Use descriptive test names: `test{Pattern}_{should|when}{Behavior}`
3. Follow AAA pattern: Arrange, Act, Assert
4. Add appropriate timeouts for async operations
5. Clean up resources in `@BeforeEach` or `@AfterEach`
6. Document expected behavior in comments

Example:

```java

@Test
void testNewPattern_shouldBehaveCorrectly() {
    // Given: Initial setup
    var pattern = registry.pattern("myPattern");

    // When: Perform action
    var result = service.call();

    // Then: Verify behavior
    assertThat(result).isNotNull();
}
```

## Further Reading

- [Spring Boot Testing](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)
- [Reactor Test Documentation](https://projectreactor.io/docs/test/release/api/)
- [Resilience4j Testing](https://resilience4j.readme.io/docs/examples)
- [AssertJ Documentation](https://assertj.github.io/doc/)
- [Awaitility Guide](https://github.com/awaitility/awaitility/wiki/Usage)