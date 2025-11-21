# Chapter 7: Observability: Seeing the Invisible

> "You can't fix what you can't see. You can't optimize what you can't measure."

## The 3 AM Page

It's 3 AM. Your phone buzzes. PagerDuty alert:

```
CRITICAL: Notification delivery rate dropped to 60%
Users affected: ~2 million
Duration: 15 minutes
```

You're half-asleep. You open your laptop. **What do you look at first?**

- Dashboard showing... something?
- Logs showing... millions of lines?
- Metrics showing... which ones?

**If you can't answer this question in 30 seconds, your observability is broken.**

This chapter is about building a system that answers questions **faster than you can ask them**.

## The Three Pillars of Observability

### 1. Metrics: "What is happening?"

**Time-series data** that answers: Is the system healthy?

Examples:
- `notifications.created.rate`: 1.2M/sec
- `notifications.delivered.rate`: 1.1M/sec
- `notifications.filtered.percentage`: 30%
- `delivery.latency.p99`: 450ms

### 2. Logs: "Why did this specific thing happen?"

**Event records** that answer: What went wrong with notification ID `xyz`?

Example:
```
2024-01-15T10:30:00.123Z [INFO] Notification created: id=abc123, user=user_456
2024-01-15T10:30:00.145Z [INFO] Preference check passed: user=user_456, event=message
2024-01-15T10:30:00.167Z [INFO] Routed to channel: push
2024-01-15T10:30:00.189Z [ERROR] APNs delivery failed: invalid_token, token=device_789
2024-01-15T10:30:00.201Z [INFO] Sent to DLQ: id=abc123
```

### 3. Traces: "How did this request flow through the system?"

**Distributed traces** that answer: Where did 450ms of latency come from?

Example:
```
Span: create_notification (total: 452ms)
├─ Span: validate_request (12ms)
├─ Span: check_preferences (45ms)
│  └─ Span: redis_get (43ms) ← SLOW!
├─ Span: render_template (23ms)
├─ Span: publish_to_kafka (8ms)
└─ Span: deliver_push (364ms)
   ├─ Span: apns_send (350ms) ← SLOW!
   └─ Span: record_delivery (14ms)
```

**The lesson**: Metrics tell you **what** to investigate. Logs tell you **why** it happened. Traces tell you **where** the problem is.

## Metrics That Matter

### The RED Method (for services)

**R**ate: How many requests per second?
**E**rrors: How many failures?
**D**uration: How long do requests take?

```java
@Component
public class NotificationMetrics {
    private final MeterRegistry registry;

    // Rate
    private final Counter createdCounter;
    private final Counter deliveredCounter;

    // Errors
    private final Counter permanentFailures;
    private final Counter transientFailures;

    // Duration
    private final Timer deliveryTimer;

    public void recordCreated(EventType eventType, Priority priority) {
        createdCounter.increment(
            Tags.of("event_type", eventType.name(),
                   "priority", priority.name())
        );
    }

    public void recordDelivered(Channel channel, long latencyMs) {
        deliveredCounter.increment(Tags.of("channel", channel.name()));
        deliveryTimer.record(latencyMs, TimeUnit.MILLISECONDS);
    }

    public void recordFailure(Channel channel, FailureType type) {
        if (type.isPermanent()) {
            permanentFailures.increment(Tags.of("channel", channel.name()));
        } else {
            transientFailures.increment(Tags.of("channel", channel.name()));
        }
    }
}
```

### The USE Method (for resources)

**U**tilization: How busy is the resource?
**S**aturation: How much queuing?
**E**rrors: How many failures?

```java
// Kafka queue metrics
gauge("kafka.queue.utilization", () -> {
    long size = queue.size();
    long capacity = queue.capacity();
    return (double) size / capacity;
});

gauge("kafka.queue.saturation", () -> {
    return queue.size(); // Items waiting
});

counter("kafka.queue.errors").increment(Tags.of("error", e.getClass().getSimpleName()));
```

### The Golden Signals

1. **Latency**: How long do operations take?
2. **Traffic**: How much demand?
3. **Errors**: How many failures?
4. **Saturation**: How full are resources?

**Dashboard example:**

```
┌─────────────────────────────────────────────────────────────┐
│ Notification System Health                                  │
├─────────────────────────────────────────────────────────────┤
│ Latency (p50/p95/p99):    45ms / 180ms / 450ms             │
│ Traffic:                   1.2M/sec                          │
│ Error rate:                0.3%                              │
│ Queue saturation:          45% (healthy)                     │
├─────────────────────────────────────────────────────────────┤
│ Per-channel breakdown:                                       │
│   Push:   800K/sec, p99=350ms, errors=0.1%                  │
│   Email:  300K/sec, p99=1200ms, errors=0.5%                 │
│   SMS:    50K/sec, p99=2800ms, errors=0.2%                  │
│   In-app: 50K/sec, p99=20ms, errors=0.0%                    │
└─────────────────────────────────────────────────────────────┘
```

## Distributed Tracing

### The Trace Context

```java
public class TraceContext {
    private final String traceId;        // Same across all spans
    private final String spanId;         // Unique per span
    private final String parentSpanId;   // Parent span ID

    public static TraceContext create() {
        return new TraceContext(
            generateTraceId(),
            generateSpanId(),
            null  // Root span has no parent
        );
    }

    public TraceContext createChild() {
        return new TraceContext(
            this.traceId,
            generateSpanId(),
            this.spanId  // This span becomes parent
        );
    }

    private static String generateTraceId() {
        return UUID.randomUUID().toString();
    }

    private static String generateSpanId() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong());
    }
}
```

### Propagating Context

```java
@RestController
public class NotificationController {

    @PostMapping("/notifications")
    public ResponseEntity<?> create(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestBody NotificationRequest request) {

        // Create or continue trace
        TraceContext trace = traceId != null
            ? TraceContext.fromHeader(traceId)
            : TraceContext.create();

        try (Span span = tracer.startSpan("create_notification", trace)) {
            span.tag("user_id", request.getUserId());
            span.tag("event_type", request.getEventType());

            // Process notification
            NotificationResponse response = notificationService.create(request, trace);

            span.tag("notification_id", response.getId());
            return ResponseEntity.ok(response);
        }
    }
}
```

```java
public class NotificationService {

    public NotificationResponse create(NotificationRequest request, TraceContext trace) {
        try (Span span = tracer.startSpan("validate_request", trace.createChild())) {
            validator.validate(request);
        }

        try (Span span = tracer.startSpan("check_preferences", trace.createChild())) {
            if (!preferenceService.allows(request.getUserId(), request.getEventType())) {
                span.tag("filtered", "true");
                throw new FilteredException("User preferences");
            }
        }

        try (Span span = tracer.startSpan("publish_to_queue", trace.createChild())) {
            publisher.publish(request, trace);
        }

        return new NotificationResponse(request.getId());
    }
}
```

### Trace Visualization

```
Trace ID: 7f3a8c9d-2e1b-4a5c-9d3e-6f8a2b1c4d5e
Total Duration: 452ms

 0ms                  200ms                 400ms
 |---------------------|---------------------|
 [====== create_notification (452ms) =======]
   [validate (12ms)]
                [=check_prefs (45ms)=]
                 [redis_get (43ms)]
                               [render (23ms)]
                                       [kafka (8ms)]
                                         [===== deliver (364ms) =====]
                                          [==== apns_send (350ms) ====]
                                                              [record (14ms)]
```

**Insight**: APNs is slow (350ms). Redis is also slow (43ms). These are your optimization targets.

## Structured Logging

### The Anti-Pattern

```java
logger.info("Notification created for user " + userId + " with event type " + eventType);
```

**Problems:**
- Hard to parse
- Can't query efficiently
- No consistent format

### The Better Way

```java
logger.info("Notification created",
    kv("notification_id", notificationId),
    kv("user_id", userId),
    kv("event_type", eventType),
    kv("priority", priority),
    kv("trace_id", trace.getTraceId())
);
```

**Output (JSON):**

```json
{
  "timestamp": "2024-01-15T10:30:00.123Z",
  "level": "INFO",
  "message": "Notification created",
  "notification_id": "abc123",
  "user_id": "user_456",
  "event_type": "message",
  "priority": "HIGH",
  "trace_id": "7f3a8c9d-2e1b-4a5c-9d3e-6f8a2b1c4d5e",
  "service": "notification-service",
  "host": "prod-worker-42"
}
```

**Benefits:**
- Easy to parse and query
- Consistent structure
- Rich context
- Traceable (trace_id links to distributed trace)

## The Notification Autopsy System

When a user reports "I didn't get my notification," you need to trace its lifecycle.

```java
@Service
public class NotificationAutopsyService {

    public NotificationLifecycle trace(String notificationId) {
        // Reconstruct full lifecycle from logs, metrics, and events

        List<LifecycleEvent> events = new ArrayList<>();

        // 1. Check creation event
        Optional<CreationEvent> creation = creationEventStore.find(notificationId);
        if (creation.isEmpty()) {
            return NotificationLifecycle.notFound(notificationId);
        }
        events.add(creation.get());

        // 2. Check if filtered by preferences
        Optional<FilterEvent> filter = filterEventStore.find(notificationId);
        if (filter.isPresent()) {
            events.add(filter.get());
            return NotificationLifecycle.filtered(notificationId, events);
        }

        // 3. Check routing
        Optional<RoutingEvent> routing = routingEventStore.find(notificationId);
        routing.ifPresent(events::add);

        // 4. Check delivery attempts
        List<DeliveryAttempt> attempts = deliveryEventStore.findAttempts(notificationId);
        events.addAll(attempts);

        // 5. Check final status
        Optional<DeliveryConfirmation> confirmation =
            deliveryEventStore.findConfirmation(notificationId);

        if (confirmation.isPresent()) {
            events.add(confirmation.get());
            return NotificationLifecycle.delivered(notificationId, events);
        } else {
            return NotificationLifecycle.failed(notificationId, events);
        }
    }
}
```

**Example output:**

```
Notification: abc123
User: user_456
Status: DELIVERED

Timeline:
10:30:00.123 - CREATED (event: message, priority: HIGH)
10:30:00.145 - PREFERENCE_CHECK (result: allowed)
10:30:00.167 - ROUTED (channel: push, platform: iOS)
10:30:00.189 - RENDER (language: en, template: message_v2)
10:30:00.210 - DELIVERY_ATTEMPT_1 (provider: APNs, result: rate_limited)
10:30:01.450 - DELIVERY_ATTEMPT_2 (provider: APNs, result: success)
10:30:01.465 - DELIVERED (latency: 1342ms)
10:30:15.200 - USER_VIEWED (time_to_view: 13.7s)
```

## Alerting Philosophy

### Bad Alert

```
Alert: CPU usage above 80%
```

**Problems:**
- What should I do about it?
- Is this actually a problem?
- Might just be normal load spike

### Good Alert

```
Alert: Notification delivery latency p99 > 1000ms for 5 minutes

Impact: ~500K notifications delayed
Likely cause: APNs slow (apns_send span at 800ms p99)
Runbook: https://wiki.company.com/notification-latency-runbook

Suggested actions:
1. Check APNs status page
2. Check circuit breaker state
3. Consider enabling fallback to email
```

**Characteristics of good alerts:**
- **Actionable**: Clear next steps
- **Specific**: Precise problem definition
- **Impact-focused**: User impact, not just technical metrics
- **Contextual**: Related information and runbooks

## The Debugging Workflow

**User reports**: "I didn't get my notification"

**Step 1: Find the notification**

```
GET /api/admin/notifications/search?user_id=user_456&event_type=message&timestamp=2024-01-15T10:30:00Z

→ notification_id: abc123
```

**Step 2: Get autopsy**

```
GET /api/admin/notifications/abc123/autopsy

→ Status: DELIVERED at 10:30:01.465Z
→ User viewed at 10:30:15.200Z
```

**Conclusion**: Notification was delivered. User may have dismissed it or not noticed.

---

**Alternative: User reports "I got the same notification 5 times"**

**Step 1: Search duplicates**

```
GET /api/admin/notifications/search?user_id=user_456&event_type=message&timestamp=2024-01-15T10:30:00Z

→ Found 5 notifications with same payload
```

**Step 2: Check request IDs**

```
GET /api/admin/notifications/abc123/metadata
→ request_id: req_xyz (seen 1 time)

GET /api/admin/notifications/abc124/metadata
→ request_id: null  ← BUG! Missing idempotency key
```

**Conclusion**: Client didn't send request_id. Retries created duplicates. Fix: Require request_id.

## If I Built This Again

**What I'd do differently**: Invest in observability from day 1. I once spent 3 days debugging an issue that would have taken 10 minutes with proper tracing.

**What I learned**: The autopsy system is invaluable. Being able to trace a single notification end-to-end saved us countless hours.

**What I'd do the same**: Structured logging. Grep-ing plain text logs at scale is impossible.

## Chapter Summary

- **Three pillars**: Metrics (what), Logs (why), Traces (where)
- **Key metrics**: RED (Rate, Errors, Duration) for services; USE (Utilization, Saturation, Errors) for resources
- **Distributed tracing**: Propagate trace context, visualize span dependencies
- **Structured logging**: JSON format, consistent fields, include trace_id
- **Autopsy system**: Reconstruct notification lifecycle for debugging
- **Alert philosophy**: Actionable, specific, impact-focused

## What's Next

We've built a robust, observable system. But the real world is messy. Time zones. Internationalization. GDPR. Notification fatigue. Edge cases become the system.

In [Chapter 8: The Edge Cases](./chapter-08-edge-cases.md), we'll handle the complexity that doesn't fit in the happy path.

---

*"If you can't measure it, you can't improve it. If you can't debug it, you can't fix it."*
