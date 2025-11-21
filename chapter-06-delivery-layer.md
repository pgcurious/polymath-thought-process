# Chapter 6: The Delivery Layer: Reaching Across the Void

> "The last mile is always the hardest."

## The Dependency on Strangers

You've built a perfect system. Validated inputs, routed intelligently, rendered beautifully. Now comes the moment of truth:

**You must hand your notification to someone else's system and hope they deliver it.**

- **Apple's APNs**: You don't control it. They have rate limits. They go down. They reject invalid tokens.
- **Google's FCM**: Different API, different limits, different failure modes.
- **SMTP servers**: Blacklists, spam filters, bounce rates.
- **SMS gateways**: Expensive, slow, regulated.

**You are no longer in control.**

This chapter is about maintaining reliability when you depend on unreliable external systems.

## The External Provider Landscape

| Provider | Protocol | Rate Limit | Latency | Success Rate | Cost |
|----------|----------|------------|---------|--------------|------|
| APNs     | HTTP/2   | ~10K/sec   | 50-200ms | 95-99%      | Free |
| FCM      | HTTP/2   | ~15K/sec   | 50-300ms | 93-98%      | Free |
| SMTP     | SMTP     | Varies     | 100-5000ms | 85-95%    | $0.10/1K |
| Twilio SMS | REST   | 100/sec    | 500-3000ms | 99%       | $10/1K |

**Key observations:**
- Rate limits are per account (you'll need sharding)
- Success rates are never 100%
- Latency varies wildly
- Some providers charge per message

## The Naive Delivery Service

```java
public class DeliveryService {

    public void deliver(RenderedNotification notification) {
        String deviceToken = notification.getDeviceToken();

        if (notification.getChannel() == Channel.PUSH) {
            if (notification.getPlatform() == Platform.IOS) {
                apnsClient.send(deviceToken, notification.getPayload());
            } else {
                fcmClient.send(deviceToken, notification.getPayload());
            }
        } else if (notification.getChannel() == Channel.EMAIL) {
            smtpClient.send(notification.getEmail(), notification.getPayload());
        }
    }
}
```

**What's wrong?**

1. **No error handling**: What if APNs returns 500?
2. **No retries**: Transient failures kill notifications
3. **No rate limiting**: Exceeds provider limits
4. **No timeouts**: Hangs on slow providers
5. **No circuit breaking**: Keeps hammering failing providers
6. **No observability**: Can't debug failures

## Pattern 1: The Retry Strategy

### Exponential Backoff with Jitter

```java
public class RetryStrategy {
    private static final int MAX_ATTEMPTS = 5;
    private static final long BASE_DELAY_MS = 100;
    private static final long MAX_DELAY_MS = 30_000;

    public <T> T executeWithRetry(Callable<T> operation) throws Exception {
        Exception lastException = null;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return operation.call();
            } catch (TransientException e) {
                lastException = e;

                if (attempt < MAX_ATTEMPTS - 1) {
                    long delay = calculateDelay(attempt);
                    Thread.sleep(delay);
                }
            } catch (PermanentException e) {
                // Don't retry permanent failures
                throw e;
            }
        }

        throw lastException;
    }

    private long calculateDelay(int attempt) {
        // Exponential: 100ms, 200ms, 400ms, 800ms, 1600ms
        long exponentialDelay = BASE_DELAY_MS * (1L << attempt);

        // Cap at max
        exponentialDelay = Math.min(exponentialDelay, MAX_DELAY_MS);

        // Add jitter to prevent thundering herd
        long jitter = ThreadLocalRandom.current().nextLong(0, exponentialDelay / 2);

        return exponentialDelay + jitter;
    }
}
```

### Classifying Errors

```java
public class APNsClient {

    public void send(String deviceToken, Payload payload) throws Exception {
        HttpResponse response = httpClient.post(
            "https://api.push.apple.com/3/device/" + deviceToken,
            payload
        );

        switch (response.getStatusCode()) {
            case 200 -> {
                // Success
                metrics.recordSuccess();
            }
            case 400 -> {
                // Bad request - permanent failure
                throw new PermanentException("Invalid payload: " + response.getBody());
            }
            case 403 -> {
                // Invalid certificate - permanent failure
                throw new PermanentException("Invalid auth");
            }
            case 404 -> {
                // Invalid device token - permanent failure
                throw new InvalidTokenException(deviceToken);
            }
            case 410 -> {
                // Token no longer valid - permanent failure
                throw new ExpiredTokenException(deviceToken);
            }
            case 429 -> {
                // Rate limited - transient failure
                throw new TransientException("Rate limited, retry later");
            }
            case 500, 503 -> {
                // Server error - transient failure
                throw new TransientException("APNs unavailable");
            }
            default -> {
                throw new TransientException("Unexpected status: " + response.getStatusCode());
            }
        }
    }
}
```

**Key insight**: Some errors should retry (500, 503, 429), others shouldn't (400, 403, 410).

## Pattern 2: Circuit Breaker

```java
public class CircuitBreaker {
    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0);

    private static final int FAILURE_THRESHOLD = 10;
    private static final int SUCCESS_THRESHOLD = 3;
    private static final long TIMEOUT_MS = 60_000;

    public <T> T execute(Callable<T> operation) throws Exception {
        State currentState = state.get();

        if (currentState == State.OPEN) {
            if (System.currentTimeMillis() - openedAt.get() > TIMEOUT_MS) {
                state.compareAndSet(State.OPEN, State.HALF_OPEN);
                successCount.set(0);
            } else {
                throw new CircuitBreakerOpenException("Circuit is OPEN");
            }
        }

        try {
            T result = operation.call();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            throw e;
        }
    }

    private void onSuccess() {
        failureCount.set(0);

        if (state.get() == State.HALF_OPEN) {
            if (successCount.incrementAndGet() >= SUCCESS_THRESHOLD) {
                state.set(State.CLOSED);
            }
        }
    }

    private void onFailure() {
        if (failureCount.incrementAndGet() >= FAILURE_THRESHOLD) {
            state.set(State.OPEN);
            openedAt.set(System.currentTimeMillis());
        }
    }
}
```

**Behavior:**
- **CLOSED** (normal): Requests pass through
- **OPEN** (failing): Reject all requests immediately (fail fast)
- **HALF_OPEN** (testing): Allow limited requests to test recovery

**Benefits:**
- Prevents cascading failures
- Gives failing systems time to recover
- Fails fast (better than timeout)

## Pattern 3: Provider Rate Limiting

```java
public class ProviderRateLimiter {
    // APNs limit: 10,000 req/sec per certificate
    // We have 10 certificates, so 100,000 req/sec total

    private final List<TokenBucket> buckets;

    public ProviderRateLimiter(int providerLimit, int accountCount) {
        this.buckets = new ArrayList<>();

        // Create bucket for each account
        for (int i = 0; i < accountCount; i++) {
            buckets.add(new TokenBucket(providerLimit, providerLimit));
        }
    }

    public CompletableFuture<Void> send(String deviceToken, Payload payload) {
        // Hash device token to bucket (sticky assignment)
        int bucketIndex = Math.abs(deviceToken.hashCode()) % buckets.size();
        TokenBucket bucket = buckets.get(bucketIndex);

        // Wait for token availability
        return bucket.acquire().thenCompose(token -> {
            try {
                return apnsClients.get(bucketIndex).send(deviceToken, payload);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        });
    }
}
```

**Benefits:**
- Prevents hitting provider rate limits
- Distributes load across multiple accounts
- Maintains throughput at scale

## Pattern 4: Dead Letter Queue

```java
public class DeliveryWorker {
    private final KafkaConsumer<String, Notification> consumer;
    private final KafkaProducer<String, Notification> dlqProducer;

    public void processNotifications() {
        while (true) {
            ConsumerRecords<String, Notification> records = consumer.poll(Duration.ofSeconds(1));

            for (ConsumerRecord<String, Notification> record : records) {
                Notification notification = record.value();

                try {
                    deliveryService.deliver(notification);
                    consumer.commitSync();

                } catch (PermanentException e) {
                    // Send to DLQ for manual inspection
                    dlqProducer.send(new ProducerRecord<>(
                        "notifications-dlq",
                        notification.getId(),
                        notification
                    ));

                    logger.error("Permanent failure for notification {}: {}",
                        notification.getId(), e.getMessage());

                    consumer.commitSync();

                } catch (TransientException e) {
                    // Retry logic handled by Kafka (don't commit offset)
                    logger.warn("Transient failure, will retry: {}", e.getMessage());
                }
            }
        }
    }
}
```

**DLQ strategy:**
- **Permanent failures** → DLQ (invalid token, bad payload)
- **Transient failures** → Retry (rate limit, timeout)
- **Unknown failures** → Retry with limit, then DLQ

## Pattern 5: Token Hygiene

```java
public class DeviceTokenManager {
    private final DeviceTokenRepository repository;

    @Async
    public void handleFeedback(InvalidTokenFeedback feedback) {
        // APNs/FCM provides feedback about invalid tokens

        String deviceToken = feedback.getToken();
        String reason = feedback.getReason();

        switch (reason) {
            case "Unregistered" -> {
                // User uninstalled app
                repository.markInactive(deviceToken);
            }
            case "TopicDisallowed" -> {
                // Certificate mismatch
                logger.error("Topic error for token: {}", deviceToken);
            }
            case "DeviceTokenNotForTopic" -> {
                // Wrong certificate used
                logger.error("Wrong topic for token: {}", deviceToken);
            }
            default -> {
                logger.warn("Unknown feedback reason: {}", reason);
            }
        }
    }

    public boolean isTokenValid(String deviceToken) {
        Optional<DeviceToken> token = repository.findById(deviceToken);

        return token.isPresent()
            && token.get().isActive()
            && token.get().getLastValidated().isAfter(Instant.now().minus(30, ChronoUnit.DAYS));
    }
}
```

**Why this matters:**
- Sending to invalid tokens wastes resources
- Providers may throttle or ban accounts with high invalid token rate
- Token hygiene improves delivery rates

## The Complete Delivery Service

```java
@Service
public class RobustDeliveryService {
    private final Map<Channel, ChannelDeliveryService> channelServices;
    private final CircuitBreaker circuitBreaker;
    private final RetryStrategy retryStrategy;
    private final MetricsService metrics;

    public CompletableFuture<DeliveryResult> deliver(RenderedNotification notification) {
        return CompletableFuture.supplyAsync(() -> {
            Channel channel = notification.getChannel();
            ChannelDeliveryService service = channelServices.get(channel);

            try {
                return circuitBreaker.execute(() ->
                    retryStrategy.executeWithRetry(() ->
                        service.send(notification)
                    )
                );

            } catch (CircuitBreakerOpenException e) {
                metrics.recordCircuitOpen(channel);
                return DeliveryResult.failure("Circuit open for " + channel);

            } catch (PermanentException e) {
                metrics.recordPermanentFailure(channel, e);
                return DeliveryResult.permanentFailure(e.getMessage());

            } catch (Exception e) {
                metrics.recordTransientFailure(channel, e);
                return DeliveryResult.transientFailure(e.getMessage());
            }
        });
    }
}
```

## If I Built This Again

**What I'd do differently**: Build circuit breakers from day 1. I once had a cascade failure because APNs went down and we kept hammering it, which exhausted connection pools and crashed our entire system.

**What I learned**: External dependencies are the #1 source of production incidents. Design for failure.

**What I'd do the same**: Aggressive token hygiene. Removing invalid tokens improved our delivery rate from 92% to 97%.

## Chapter Summary

- **Retry with exponential backoff**: Handle transient failures gracefully
- **Circuit breakers**: Prevent cascade failures when providers fail
- **Rate limiting**: Respect provider limits, shard across accounts
- **Dead letter queues**: Isolate permanent failures for investigation
- **Token hygiene**: Remove invalid tokens to improve delivery rates
- **Error classification**: Distinguish permanent vs transient failures

## What's Next

We can deliver notifications. But can we see what's happening? When things go wrong (and they will), can we debug them?

In [Chapter 7: Observability](./chapter-07-observability.md), we'll build the eyes and ears of the system—monitoring, tracing, and debugging at planetary scale.

---

*"Hope is not a strategy. Circuit breakers are."*
