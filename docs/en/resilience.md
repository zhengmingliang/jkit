# Resilience (Retryer / CircuitBreaker)

Protocol-agnostic, zero-dependency fault-tolerance utilities that wrap any `Callable` / `Runnable`:

- `Retryer`: retries a failing task a limited number of times with exponential backoff + jitter;
- `CircuitBreaker`: opens after consecutive failures, lets a probe through after a cooldown, and closes again on success.

Typical uses: wrapping notification sending, external RPC, one-shot file operations — tasks where failures tend to persist for a while. Both live in the `com.alianga.jkit.resilience` package.

## 1. Retryer

```java
import com.alianga.jkit.resilience.Retryer;

// Defaults: 3 attempts, 200ms exponential backoff (cap 10s), ±20% jitter, retries all non-Error exceptions
String result = Retryer.retry(() -> notifyChannel.send(message));

// Custom configuration
Retryer.RetryConfig config = Retryer.defaults()
        .maxAttempts(5)
        .baseDelayMs(100L)
        .maxDelayMs(2000L)
        .jitter(0.3)
        .retryOn(IOException.class);   // only retry IO exceptions
String r2 = Retryer.retry(() -> sendOnce(), config);

// No-return task
Retryer.retry(() -> writeFile(path, data), config);
```

### Backoff and jitter

Wait time before the `attempt`-th retry (`attempt` starts at 1, so the first retry waits `baseDelayMs`):

```
delay = min(maxDelayMs, baseDelayMs * 2^(attempt-1))
delay = delay * (1 ± jitter)   // random jitter to avoid a thundering herd when upstream recovers
```

With `jitter = 0` the wait is fully deterministic (handy for tests). Exceptions for which `retryOn` returns `false` are not retried and are rethrown immediately; `Error`s are never retried by default.

### Interruption and exceptions

- After retries are exhausted, the **last** task exception is thrown (original type and stack preserved);
- If the sleeper is interrupted, the interrupt flag is restored and a `ResilienceException` is thrown;
- A `null` task throws `IllegalArgumentException`.

## 2. CircuitBreaker

```java
import com.alianga.jkit.resilience.CircuitBreaker;

// Each dependency owns its own instance (e.g. a specific notification channel)
CircuitBreaker breaker = new CircuitBreaker();

try {
    String r = breaker.run(() -> riskyCall());
} catch (CircuitBreakerOpenException e) {
    // Currently open — fall back
}
```

### State machine

| State | Behavior |
| --- | --- |
| `CLOSED` | Calls pass through; after `failureThreshold` consecutive failures, transitions to `OPEN` |
| `OPEN` | Calls are rejected with `CircuitBreakerOpenException`; after `cooldownMs` transitions to `HALF_OPEN` |
| `HALF_OPEN` | Exactly **one** probe is admitted at a time; other calls are rejected as open. `successThreshold` consecutive successes close it (further probes are admitted while the threshold is unmet), any failure re-opens |

```java
CircuitBreaker.Config config = new CircuitBreaker.Config()
        .failureThreshold(5)     // open after 5 consecutive failures
        .successThreshold(1)     // 1 success in half-open closes it
        .cooldownMs(30_000L);    // 30s cooldown before half-open probing
breaker.run(() -> riskyCall(), config);
```

A single success clears the failure counter while `CLOSED`, so occasional failures do not accumulate toward the threshold.

### Thread safety

Internal state uses atomic variables, so a single `CircuitBreaker` instance is safe under concurrent use. Each dependency that needs an independent policy should still hold its own instance.

## 3. Relationship to HTTP built-in resilience

`http.lb`'s `RetryPolicy` and `EndpointPool` circuit breaking are **HTTP request / load-balancing endpoint** specific and take effect automatically via the `HttpClient` `Builder`. This package's `Retryer` / `CircuitBreaker` are **protocol-agnostic** primitives, suitable for wrapping non-HTTP tasks (local file ops, message pushes, scheduled jobs), or for composition by higher-level components.
