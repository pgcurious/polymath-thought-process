# Chapter 4: The Write Path: Ingesting Chaos

> "The interface between order and chaos is where engineering happens."

## The Front Door Problem

Imagine you're running a nightclub. Your club can comfortably hold 1,000 people. But on New Year's Eve, 10,000 people show up.

You have three options:

1. **Let everyone in** → Club collapses, fire hazard, people get hurt
2. **Lock the doors** → Angry crowd, reputation damaged, revenue lost
3. **Manage the flow** → Queue outside, check IDs, regulate entry rate

Option 3 is obviously correct. But look at what it requires:

- **Validation**: Check IDs (reject underage, fakes)
- **Rate limiting**: Let in X people per minute
- **Queueing**: Where do people wait? How long? FIFO or priority?
- **Backpressure**: When full, communicate "slow down" to the queue
- **Graceful degradation**: What do you do when the line is 2 hours long?

**Your notification ingestion layer faces the exact same problem.**

1,000 services want to send notifications. Some send 1 per second. Some send 100,000 per second. Some send invalid data. Some retry failed requests forever. Some send duplicate requests.

**Your job**: Accept the good, reject the bad, and protect the system from the ugly.

## The Naive API (and Why It Fails)

Let's start with the obvious solution:

```java
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    @Autowired
    private NotificationService notificationService;

    @PostMapping
    public ResponseEntity<NotificationResponse> create(
            @RequestBody NotificationRequest request) {

        Notification notification = notificationService.create(request);
        return ResponseEntity.ok(new NotificationResponse(notification.getId()));
    }
}
```

```java
public class NotificationService {

    @Autowired
    private NotificationRepository repository;

    @Autowired
    private NotificationPublisher publisher;

    public Notification create(NotificationRequest request) {
        // Validate
        validate(request);

        // Save to database
        Notification notification = repository.save(
            request.toNotification()
        );

        // Publish to queue for routing
        publisher.publish(notification);

        return notification;
    }
}
```

**At 10 requests/second**: Works perfectly.

**At 1,000 requests/second**: Still fine, maybe some GC pressure.

**At 100,000 requests/second**: Database connection pool exhausted, API times out, cascading failures.

**At 1,000,000 requests/second**: Complete meltdown.

**What went wrong?**

## The Seven Sins of the Naive Approach

### Sin 1: Synchronous Database Writes

```java
// This blocks the request thread
Notification notification = repository.save(request.toNotification());
```

**Problem**: At 1M req/sec, if each DB write takes 5ms, you need:

```
1,000,000 req/sec × 0.005 sec/req = 5,000 concurrent threads
```

**Reality**: Servers have ~100-1000 threads. Database has maybe 100 connections.

**Result**: Rejected requests, timeouts, cascading failures.

### Sin 2: No Request Deduplication

What happens if a client sends the same request 3 times?

```
POST /notifications
{ "user_id": 123, "event_type": "message", "text": "Hi!" }

POST /notifications
{ "user_id": 123, "event_type": "message", "text": "Hi!" }

POST /notifications
{ "user_id": 123, "event_type": "message", "text": "Hi!" }
```

**Result**: User gets 3 identical notifications. Poor UX. Wasted resources.

### Sin 3: No Rate Limiting

A buggy service sends 1 million notifications/second for a single user.

**Result**: That user's notifications dominate the queue. Other users starve.

### Sin 4: Weak Validation

```java
void validate(NotificationRequest request) {
    if (request.getUserId() == null) {
        throw new ValidationException("user_id required");
    }
}
```

**What's missing?**
- Is user_id valid? (Does the user exist?)
- Is event_type recognized?
- Is payload size reasonable? (Prevents 100MB notification payloads)
- Is request_id present? (Needed for idempotency)

### Sin 5: No Backpressure

When the system is overloaded, it keeps accepting requests until it crashes.

**Better approach**: Return `503 Service Unavailable` or `429 Too Many Requests` to tell clients "slow down."

### Sin 6: No Batching

Processing 1M individual requests is expensive. Processing 1000 batches of 1000 requests each is much cheaper.

**Why?** Amortized costs:
- Database: Batch insert is 10-100× faster than individual inserts
- Network: Fewer round trips
- Parsing: Shared overhead across batch

### Sin 7: No Observability

When things go wrong, you can't answer:
- What's the error rate?
- Which services are sending bad requests?
- Where's the bottleneck?

## Thought Experiment #1: The Sensory System Analogy

Your brain receives ~11 million bits of sensory information per second:
- Eyes: ~10 million bits/sec
- Skin: ~1 million bits/sec
- Ears: ~100,000 bits/sec

But your conscious mind processes only ~40 bits/sec.

**How does your nervous system handle this 275,000:1 reduction without "crashing"?**

### 1. Pre-filtering (Validation)

Your retina doesn't send every photon to your brain. It pre-processes:
- Edge detection (high contrast areas)
- Motion detection (changing pixels)
- Pattern recognition (faces, text)

**Lesson**: Validate and filter at the edge, before expensive processing.

### 2. Priority Routing (Triage)

Pain signals get priority over background touch.

Sharp sounds get priority over ambient noise.

**Lesson**: Not all inputs are equal. Route high-priority items differently.

### 3. Adaptation (Rate Limiting)

Enter a bright room: Overwhelming for 2 seconds, then you adapt.

Constant background noise: After 5 minutes, you don't notice it.

**Lesson**: Adjust sensitivity based on load. When overloaded, increase threshold.

### 4. Compression (Batching)

Your brain doesn't process individual photons. It processes "scenes" at ~60 Hz.

**Lesson**: Batch inputs into chunks for efficient processing.

### 5. Dropping Data (Load Shedding)

In fight-or-flight mode, you stop processing non-critical inputs (hunger, cold, etc.).

**Lesson**: When overloaded, explicitly drop low-priority data.

**Can we design our ingestion layer like a nervous system?**

## The Evolved Write Path: Architecture

Here's the redesigned ingestion layer:

```
┌─────────────────────────────────────────────────────────────┐
│                        API Gateway                          │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Rate Limiter│  │ Deduplication│  │  Validation  │      │
│  └──────┬──────┘  └──────┬───────┘  └──────┬───────┘      │
│         │                 │                  │               │
│         └─────────────────┴──────────────────┘              │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                    Ingestion Service                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │   Batching   │  │ Load Shedding│  │ Backpressure │     │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘     │
└─────────┼──────────────────┼──────────────────┼─────────────┘
          │                  │                  │
          └──────────────────┴──────────────────┘
                          │
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                    Message Queue (Kafka)                    │
│         ┌────────┬────────┬────────┬────────┐              │
│         │ P0     │ P1     │ P2     │ P3     │              │
│         │ (Crit) │ (High) │ (Med)  │ (Low)  │              │
└─────────┴────────┴────────┴────────┴────────┴──────────────┘
```

Let's build each component.

## Component 1: Rate Limiting

### The Token Bucket Algorithm

```java
public class TokenBucketRateLimiter {
    private final long capacity;           // Max tokens
    private final long refillRate;         // Tokens per second
    private final AtomicLong tokens;       // Current tokens
    private final AtomicLong lastRefill;   // Last refill timestamp

    public TokenBucketRateLimiter(long capacity, long refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = new AtomicLong(capacity);
        this.lastRefill = new AtomicLong(System.nanoTime());
    }

    public boolean tryConsume(long tokensNeeded) {
        refill();

        long current = tokens.get();
        while (current >= tokensNeeded) {
            if (tokens.compareAndSet(current, current - tokensNeeded)) {
                return true; // Success
            }
            current = tokens.get(); // Retry due to race
        }

        return false; // Rate limited
    }

    private void refill() {
        long now = System.nanoTime();
        long last = lastRefill.get();
        long elapsed = now - last;

        long tokensToAdd = (elapsed * refillRate) / 1_000_000_000L;

        if (tokensToAdd > 0) {
            long current = tokens.get();
            long newTokens = Math.min(capacity, current + tokensToAdd);

            if (tokens.compareAndSet(current, newTokens)) {
                lastRefill.set(now);
            }
        }
    }
}
```

**Usage:**

```java
// Limit per service: 10,000 requests/second
Map<String, TokenBucketRateLimiter> limiters = new ConcurrentHashMap<>();

@PostMapping
public ResponseEntity<?> create(
        @RequestHeader("X-Service-ID") String serviceId,
        @RequestBody NotificationRequest request) {

    TokenBucketRateLimiter limiter = limiters.computeIfAbsent(
        serviceId,
        id -> new TokenBucketRateLimiter(10_000, 10_000)
    );

    if (!limiter.tryConsume(1)) {
        return ResponseEntity.status(429)
            .header("Retry-After", "1")
            .body("Rate limit exceeded");
    }

    // Process request...
}
```

**Benefits:**
- Allows bursts (up to capacity)
- Smooth long-term rate
- Simple and fast

**Distributed rate limiting:**

For multi-instance deployment, use Redis:

```java
public class DistributedRateLimiter {
    private final RedisTemplate<String, String> redis;

    public boolean tryConsume(String key, long limit, long window) {
        String script =
            "local count = redis.call('INCR', KEYS[1])\n" +
            "if count == 1 then\n" +
            "  redis.call('EXPIRE', KEYS[1], ARGV[1])\n" +
            "end\n" +
            "return count";

        Long count = redis.execute(
            new DefaultRedisScript<>(script, Long.class),
            Collections.singletonList(key),
            String.valueOf(window)
        );

        return count != null && count <= limit;
    }
}
```

## Component 2: Deduplication

### The Idempotency Key Pattern

```java
public class IdempotencyService {
    private final Cache<String, NotificationResponse> cache;

    public IdempotencyService() {
        this.cache = Caffeine.newBuilder()
            .maximumSize(1_000_000)
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build();
    }

    public Optional<NotificationResponse> getCached(String requestId) {
        return Optional.ofNullable(cache.getIfPresent(requestId));
    }

    public void cache(String requestId, NotificationResponse response) {
        cache.put(requestId, response);
    }
}
```

**Usage:**

```java
@PostMapping
public ResponseEntity<?> create(@RequestBody NotificationRequest request) {

    String requestId = request.getRequestId();
    if (requestId == null) {
        return ResponseEntity.badRequest()
            .body("request_id is required");
    }

    // Check cache
    Optional<NotificationResponse> cached =
        idempotencyService.getCached(requestId);

    if (cached.isPresent()) {
        return ResponseEntity.ok(cached.get());
    }

    // Process (guaranteed first time)
    NotificationResponse response = processNotification(request);

    // Cache result
    idempotencyService.cache(requestId, response);

    return ResponseEntity.ok(response);
}
```

**The subtle challenge**: What if two identical requests arrive simultaneously?

```java
// Thread 1: checks cache (miss) → processes
// Thread 2: checks cache (miss) → processes
// Both process the same request!
```

**Solution: Distributed lock:**

```java
public NotificationResponse createIdempotent(NotificationRequest request) {
    String requestId = request.getRequestId();

    // Check cache
    Optional<NotificationResponse> cached = cache.getCached(requestId);
    if (cached.isPresent()) {
        return cached.get();
    }

    // Acquire distributed lock
    RLock lock = redisson.getLock("idempotency:" + requestId);

    try {
        // Try to acquire lock (wait up to 100ms)
        if (lock.tryLock(100, TimeUnit.MILLISECONDS)) {
            try {
                // Double-check cache (maybe other thread completed)
                cached = cache.getCached(requestId);
                if (cached.isPresent()) {
                    return cached.get();
                }

                // Process (guaranteed unique)
                NotificationResponse response = processNotification(request);

                // Cache result
                cache.cache(requestId, response);

                return response;

            } finally {
                lock.unlock();
            }
        } else {
            // Couldn't acquire lock, retry or fail
            throw new ConcurrentModificationException(
                "Duplicate request being processed"
            );
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
    }
}
```

## Component 3: Validation

### Multi-Layer Validation

```java
public class NotificationValidator {

    // Layer 1: Structural validation
    public void validateStructure(NotificationRequest request) {
        if (request.getUserId() == null || request.getUserId().isEmpty()) {
            throw new ValidationException("user_id is required");
        }

        if (request.getEventType() == null) {
            throw new ValidationException("event_type is required");
        }

        if (request.getPayload() == null) {
            throw new ValidationException("payload is required");
        }

        // Prevent abuse
        if (request.getPayload().toString().length() > 100_000) {
            throw new ValidationException(
                "payload exceeds maximum size (100KB)"
            );
        }
    }

    // Layer 2: Semantic validation
    public void validateSemantics(NotificationRequest request) {
        // Is event_type recognized?
        if (!EventType.isValid(request.getEventType())) {
            throw new ValidationException(
                "Unknown event_type: " + request.getEventType()
            );
        }

        // Does event_type match payload structure?
        EventType type = EventType.valueOf(request.getEventType());
        if (!type.getPayloadSchema().matches(request.getPayload())) {
            throw new ValidationException(
                "Payload doesn't match schema for " + type
            );
        }
    }

    // Layer 3: Business validation (async, best-effort)
    public CompletableFuture<Void> validateBusiness(
            NotificationRequest request) {

        return CompletableFuture.runAsync(() -> {
            // Does user exist? (with timeout and fallback)
            try {
                boolean userExists = userService.exists(
                    request.getUserId(),
                    Duration.ofMillis(50) // Fast timeout
                );

                if (!userExists) {
                    // Log warning but don't reject (eventual consistency)
                    logger.warn("Notification for non-existent user: {}",
                        request.getUserId());
                }
            } catch (TimeoutException e) {
                // User service slow/down, allow notification through
                logger.warn("User validation timed out, proceeding anyway");
            }
        });
    }
}
```

**Key insight**: Different validations have different SLAs:
- Structural: Must pass, <1ms
- Semantic: Must pass, <10ms
- Business: Best-effort, <50ms

## Component 4: Batching

### The Batching Buffer

```java
public class BatchingIngestionService {
    private final BlockingQueue<NotificationRequest> buffer;
    private final ScheduledExecutorService scheduler;
    private final NotificationPublisher publisher;

    private static final int BATCH_SIZE = 1000;
    private static final long BATCH_TIMEOUT_MS = 100;

    public BatchingIngestionService() {
        this.buffer = new LinkedBlockingQueue<>(100_000);
        this.scheduler = Executors.newScheduledThreadPool(1);

        // Flush batches periodically
        scheduler.scheduleAtFixedRate(
            this::flushBatch,
            BATCH_TIMEOUT_MS,
            BATCH_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        );
    }

    public CompletableFuture<Void> accept(NotificationRequest request) {
        boolean added = buffer.offer(request);

        if (!added) {
            // Buffer full, apply backpressure
            return CompletableFuture.failedFuture(
                new BackpressureException("Ingestion buffer full")
            );
        }

        // Check if batch is ready
        if (buffer.size() >= BATCH_SIZE) {
            flushBatch();
        }

        return CompletableFuture.completedFuture(null);
    }

    private void flushBatch() {
        List<NotificationRequest> batch = new ArrayList<>(BATCH_SIZE);
        buffer.drainTo(batch, BATCH_SIZE);

        if (batch.isEmpty()) {
            return;
        }

        // Publish batch to Kafka
        publisher.publishBatch(batch);

        metrics.recordBatchSize(batch.size());
    }
}
```

**Trade-off**: Batching adds latency (up to BATCH_TIMEOUT_MS) for efficiency.

**Configuration:**
- High-priority notifications: Small batches (10-100), short timeout (10ms)
- Low-priority notifications: Large batches (1000-10000), long timeout (1000ms)

## Component 5: Backpressure and Load Shedding

### The Circuit Breaker Pattern

```java
public class CircuitBreaker {
    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final AtomicReference<State> state;
    private final AtomicInteger failureCount;
    private final AtomicLong lastFailureTime;

    private static final int FAILURE_THRESHOLD = 5;
    private static final long TIMEOUT_MS = 60_000; // 1 minute

    public CircuitBreaker() {
        this.state = new AtomicReference<>(State.CLOSED);
        this.failureCount = new AtomicInteger(0);
        this.lastFailureTime = new AtomicLong(0);
    }

    public <T> T execute(Callable<T> operation) throws Exception {
        if (state.get() == State.OPEN) {
            // Check if timeout has elapsed
            if (System.currentTimeMillis() - lastFailureTime.get() > TIMEOUT_MS) {
                state.set(State.HALF_OPEN);
            } else {
                throw new CircuitBreakerOpenException(
                    "Circuit breaker is OPEN"
                );
            }
        }

        try {
            T result = operation.call();

            // Success
            if (state.get() == State.HALF_OPEN) {
                state.set(State.CLOSED);
                failureCount.set(0);
            }

            return result;

        } catch (Exception e) {
            handleFailure();
            throw e;
        }
    }

    private void handleFailure() {
        lastFailureTime.set(System.currentTimeMillis());

        int failures = failureCount.incrementAndGet();

        if (failures >= FAILURE_THRESHOLD) {
            state.set(State.OPEN);
            logger.error("Circuit breaker opened after {} failures", failures);
        }
    }
}
```

**Usage:**

```java
private final CircuitBreaker databaseCircuit = new CircuitBreaker();

public void processNotification(NotificationRequest request) {
    try {
        databaseCircuit.execute(() -> {
            return notificationRepository.save(request.toNotification());
        });
    } catch (CircuitBreakerOpenException e) {
        // Database is down, use fallback
        logger.error("Database circuit open, using async fallback");
        queueForLaterProcessing(request);
    }
}
```

### Load Shedding by Priority

```java
public class LoadSheddingService {
    private final AtomicLong systemLoad; // 0-100

    public boolean shouldAccept(NotificationRequest request) {
        long load = systemLoad.get();

        // Always accept critical notifications
        if (request.getPriority() == Priority.CRITICAL) {
            return true;
        }

        // Shed load based on priority and system load
        return switch (request.getPriority()) {
            case HIGH -> load < 90;
            case MEDIUM -> load < 70;
            case LOW -> load < 50;
            default -> load < 30;
        };
    }

    // Update load based on metrics
    @Scheduled(fixedRate = 1000)
    public void updateLoad() {
        // Check queue depth, CPU, memory
        long queueDepth = buffer.size();
        long maxQueue = buffer.remainingCapacity() + buffer.size();

        long calculatedLoad = (queueDepth * 100) / maxQueue;
        systemLoad.set(calculatedLoad);
    }
}
```

## Interview Question: The Thundering Herd

**Scenario**: It's Black Friday. At exactly 12:00:00 AM, your e-commerce site sends "Sale started!" notifications to 100 million users.

All 100 million notification requests arrive at your ingestion API within 10 seconds.

**That's 10 million requests per second—10× your normal peak.**

**Question 1**: What happens to your system?

**Question 2**: How do you prevent complete failure?

**Question 3**: How do you ensure high-priority notifications (order confirmations, security alerts) still get through?

<details>
<summary>Solution Approach</summary>

**What happens (without protection):**
- API servers overwhelmed → reject requests → clients retry → more load → death spiral
- Database connection pool exhausted → timeouts → cascading failures
- Message queue filled → backlog grows → latency increases exponentially

**Prevention strategies:**

**1. Jitter the requests (client-side):**
```java
// Server sends scheduled time with jitter window
{
  "scheduled_time": "2024-12-01T00:00:00Z",
  "jitter_window_seconds": 300  // 5 minute window
}

// Client randomizes within window
long jitterMs = ThreadLocalRandom.current().nextLong(0, 300_000);
scheduleAt(scheduledTime.plusMillis(jitterMs));
```

**2. Priority queues (server-side):**
```java
// Separate queues by priority
KafkaProducer priorityProducer = new KafkaProducer();
priorityProducer.send(new ProducerRecord<>(
    "notifications-" + request.getPriority().name().toLowerCase(),
    request
));
```

**3. Admission control:**
```java
if (systemLoad > 80 && request.getPriority() != Priority.CRITICAL) {
    return ResponseEntity.status(503)
        .header("Retry-After", calculateBackoff())
        .body("System overloaded, retry later");
}
```

**4. Graceful degradation:**
```java
// Skip expensive validations during overload
if (systemLoad > 90) {
    skipBusinessValidation(request);
}
```
</details>

## If I Built This Again

**What I'd do differently**: Start with Kafka from day 1. I initially used a database queue ("SELECT * FROM notifications WHERE status = 'pending'"). Terrible idea. Databases are not queues.

**What I learned**: Backpressure is not failure—it's a feature. Rejecting requests with 503 is better than accepting them and timing out.

**What I'd do the same**: Heavy investment in observability. You can't optimize what you can't measure.

## Exercises

1. **Calculate Throughput**: Your batching service uses batches of 1000 with 100ms timeout. What's the maximum throughput? How does this change if you use batches of 100 with 10ms timeout?

2. **Design Rate Limiter**: Implement a "sliding window" rate limiter that's more accurate than token bucket for bursty traffic.

3. **Backpressure Strategy**: Design a backpressure mechanism that communicates current system load to clients so they can self-regulate.

4. **Priority Inversion**: What happens if low-priority notifications accumulate and block high-priority ones? Design a solution.

## Chapter Summary

- **Async ingestion**: Never block request thread on slow operations
- **Rate limiting**: Token bucket per service, distributed via Redis
- **Deduplication**: Idempotency keys with distributed locks
- **Validation**: Multi-layer (structural, semantic, business) with different SLAs
- **Batching**: Trade latency for throughput (100ms batches of 1000)
- **Backpressure**: Return 503 when overloaded, use circuit breakers
- **Load shedding**: Drop low-priority notifications during overload

## What's Next

We've safely ingested 1 million notifications per second. Now we need to decide: Where does each notification go?

In [Chapter 5: The Routing Engine](./chapter-05-routing-engine.md), we'll build the brain of the system—the component that evaluates preferences, renders templates, and routes notifications to the right channel with the right priority.

---

*"The job of the ingestion layer is to say 'no' politely and 'yes' safely."*
