# 韧性抽包（Retryer / CircuitBreaker）

通用、零依赖的故障容错工具，与 HTTP 解耦，可包裹任意 `Callable` / `Runnable`：

- `Retryer`：失败按指数退避 + 抖动重试有限次；
- `CircuitBreaker`：连续失败达到阈值后熔断，冷却后半开探测，成功则恢复。

典型用途：包裹 notify 的消息发送、外部 RPC、一次性文件操作等"失败会持续一段时间"的任务。两者都放在 `com.alianga.jkit.resilience` 包下。

## 1. Retryer 重试

```java
import com.alianga.jkit.resilience.Retryer;

// 默认配置：3 次尝试、200ms 指数退避（上限 10s）、±20% 抖动，重试所有非 Error 异常
String result = Retryer.retry(() -> notifyChannel.send(message));

// 自定义配置
Retryer.RetryConfig config = Retryer.defaults()
        .maxAttempts(5)
        .baseDelayMs(100L)
        .maxDelayMs(2000L)
        .jitter(0.3)
        .retryOn(IOException.class);   // 只重试 IO 异常
String r2 = Retryer.retry(() -> sendOnce(), config);

// 无返回值任务
Retryer.retry(() -> writeFile(path, data), config);
```

### 退避与抖动

第 `attempt` 次重试前的等待时间：

```
delay = min(maxDelayMs, baseDelayMs * 2^(attempt-1))
delay = delay * (1 ± jitter)   // 随机抖动，避免上游恢复瞬间惊群
```

`attempt` 从 1 开始（即首次失败后等 `baseDelayMs`）。`jitter = 0` 时不做抖动，等待完全确定，便于测试。`retryOn` 返回 `false` 的异常不会重试，直接抛出；默认不重试 `Error`。

### 中断与异常

- 重试耗尽后抛出**最后一次**任务异常（保留原始类型和堆栈）；
- 休眠被 `Thread.interrupted` 时，恢复中断标志并抛出 `ResilienceException`；
- 任务为 `null` 时抛出 `IllegalArgumentException`。

## 2. CircuitBreaker 熔断

```java
import com.alianga.jkit.resilience.CircuitBreaker;

// 每个依赖持有自己的实例（例如某个通知渠道）
CircuitBreaker breaker = new CircuitBreaker();

try {
    String r = breaker.run(() -> riskyCall());
} catch (CircuitBreakerOpenException e) {
    // 当前已熔断，走降级逻辑
}
```

### 状态机

| 状态 | 行为 |
| --- | --- |
| `CLOSED` | 正常放行；连续失败达到 `failureThreshold` 转入 `OPEN` |
| `OPEN` | 直接拒绝，抛出 `CircuitBreakerOpenException`；冷却 `cooldownMs` 后转 `HALF_OPEN` |
| `HALF_OPEN` | 同一时刻只放行**一个**探测请求，其余按熔断拒绝；`successThreshold` 次连续成功转 `CLOSED`（未达阈值会继续放行下一个探测），任一次失败重新 `OPEN` |

```java
CircuitBreaker.Config config = new CircuitBreaker.Config()
        .failureThreshold(5)     // 连续失败 5 次熔断
        .successThreshold(1)     // 半开状态 1 次成功即恢复
        .cooldownMs(30_000L);    // 熔断 30s 后进入半开探测
breaker.run(() -> riskyCall(), config);
```

一次成功会把 `CLOSED` 状态下的失败计数清零，因此偶发失败不会累积到熔断阈值。

### 线程安全

内部状态使用原子变量，单个 `CircuitBreaker` 实例可在多线程下安全使用；但每个需要独立熔断策略的依赖应当持有自己的实例。

## 3. 与 HTTP 内置容错的关系

`http.lb` 中的 `RetryPolicy` 与 `EndpointPool` 熔断是**绑定 HTTP 请求/负载均衡端点**的实现，随 `HttpClient` 的 `Builder` 配置自动生效。本包的 `Retryer` / `CircuitBreaker` 是**与协议无关**的通用原语，适合包裹非 HTTP 任务（如本地文件操作、消息推送、定时作业），或被上层组件按需组合使用。
