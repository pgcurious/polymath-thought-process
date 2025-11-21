# Chapter 2: First Principles: What Is a Notification?

> "If you can't define it precisely, you can't build it correctly."

## The Deceptively Simple Question

Ask someone "What is a notification?" and you'll get answers like:
- "A message that pops up on your phone"
- "A way to tell users about events"
- "An alert system"

These aren't wrong, but they're incomplete. They describe what notifications *do*, not what they *are*.

When you're building a system to handle 1 million notifications per second, vague definitions become fatal. Every ambiguity becomes a bug, a performance bottleneck, or a data inconsistency.

So let's start with the hardest question: **What is the minimal, precise definition of a notification?**

## Thought Experiment #1: The Notification Reduction

Let's strip away everything non-essential.

**Start with a real-world notification:**
```
"John Smith tagged you in a photo at Sunset Beach with 3 others.
View photo: [image thumbnail]
Tap to see who else was tagged."
```

What can we remove while still calling it a "notification"?

- Remove the thumbnail → Still a notification
- Remove the names of others → Still a notification
- Remove the location → Still a notification
- Remove the sender name → Getting questionable
- Remove the action → Debatable
- Remove the recipient → **Not a notification**

**Try removing from the other direction:**

- Remove the delivery mechanism (push/email/SMS) → Still a notification
- Remove the timestamp → Still a notification (but we've lost context)
- Remove the identifier → Can't track it, but conceptually still a notification

**What remains when we've stripped everything?**

```
A notification is:
  - Information
  - Directed at a specific recipient
  - About an event
  - Intended to trigger awareness or action
```

But this is still too abstract for system design. Let's get more precise.

## The Atomic Structure of a Notification

In chemistry, understanding atomic structure lets you predict how elements behave. Same principle applies here.

**A notification has three essential components:**

### 1. Identity (What)
```
notification_id: "unique identifier"
event_type: "photo_tag", "comment", "message", etc.
payload: { event-specific data }
```

### 2. Routing (Who/Where)
```
recipient_id: "who should receive this"
channel: "push", "email", "sms", "in_app"
destination: "device token", "email address", "phone number"
```

### 3. Context (When/Why)
```
priority: "critical", "high", "normal", "low"
created_at: timestamp
expires_at: timestamp (optional)
metadata: { tracking, A/B test info, etc. }
```

**Everything else is a derivation** of these three components.

The beautiful message with image and custom sound? That's rendering the payload based on event_type.

The user preference check? That's routing logic.

The retry logic? That's context-driven behavior based on priority and timestamps.

## Polymath's Corner: Notifications as Packets

This three-part structure mirrors network packet design (OSI model):

- **Identity** = Payload (Layer 7: Application)
- **Routing** = Headers (Layers 3-4: Network/Transport)
- **Context** = Metadata (Layer 2: Data Link)

Network engineers solved notification-like problems decades ago:
- How do you route billions of packets per second?
- How do you handle delivery failures?
- How do you prioritize traffic?

**What can we learn from TCP/IP?**
- Layered architecture (separation of concerns)
- Best-effort vs. guaranteed delivery
- Congestion control (backpressure)
- Checksums and acknowledgments

The Internet is essentially a notification system at cosmic scale.

## The Notification Lifecycle: A State Machine

Now that we know *what* a notification is, let's trace *what happens* to it.

Most engineers think of notifications as a simple pipeline:
```
Create → Send → Deliver → Done
```

But that's the happy path. Real systems need to handle every possible state transition.

**Here's the complete state machine:**

```
┌─────────┐
│ CREATED │ (Initial state)
└────┬────┘
     │
     ├─→ VALIDATED ──→ INVALID ──→ REJECTED
     │                              (Terminal state)
     ↓
┌──────────┐
│ QUEUED   │ (Waiting for processing)
└────┬─────┘
     │
     ├─→ EXPIRED ──→ DISCARDED
     │               (Terminal state)
     ↓
┌──────────────┐
│ ROUTED       │ (Destination determined)
└────┬─────────┘
     │
     ├─→ FILTERED ──→ SUPPRESSED
     │                (Terminal state: user preferences)
     ↓
┌──────────────┐
│ DISPATCHED   │ (Sent to delivery channel)
└────┬─────────┘
     │
     ├─→ DELIVERY_FAILED ──→ RETRY ──→ DISPATCHED (loop)
     │                    ↓
     │                    └─→ ABANDONED (Terminal: max retries)
     ↓
┌──────────────┐
│ DELIVERED    │ (Confirmed by provider)
└────┬─────────┘
     │
     ├─→ DISPLAYED ──→ DISMISSED
     │                 (User saw it)
     ├─→ CLICKED ──────→ CONVERTED
     │                   (User acted)
     ├─→ IGNORED
     │   (Delivered but never opened)
     └─→ READ
         (Terminal states)
```

**Count the states**: 17 distinct states.

**Count the transitions**: 23 possible state changes.

**The Curiosity Prompt**: Why does this matter? Why not just track "sent" vs. "not sent"?

<details>
<summary>Answer</summary>

Each state transition reveals critical information:

- **Business metrics**: What's our click-through rate? (DELIVERED → CLICKED)
- **Operational health**: Why are notifications failing? (DISPATCHED → DELIVERY_FAILED)
- **User experience**: Are we sending notifications users ignore? (DELIVERED → IGNORED)
- **Cost optimization**: Can we stop retrying? (RETRY → ABANDONED)
- **Debugging**: Where did this notification get stuck? (Check current state)

Without precise states, you're flying blind.
</details>

## Thought Experiment #2: The CAP Theorem Lens

The CAP theorem states: In a distributed system, you can have at most 2 of 3:
- **Consistency**: All nodes see the same data
- **Availability**: Every request gets a response
- **Partition tolerance**: System works despite network failures

**Let's apply CAP to our notification lifecycle:**

### Scenario 1: User changes notification preferences

You're browsing Instagram. You navigate to Settings → Notifications → disable "Photo Tag" notifications.

At the exact same moment, your friend tags you in a photo.

**What happens?**

The notification creation service (in AWS us-east-1) creates the notification.
The preference service (in AWS eu-west-1) updates your preferences.

Due to network latency, these events are concurrent.

**If we choose Consistency + Partition Tolerance (CP):**
- Block until preferences are synchronized globally
- Latency: Could be 100-500ms
- Risk: If network is partitioned, requests fail
- Guarantee: Preferences are always correct

**If we choose Availability + Partition Tolerance (AP):**
- Allow both operations to succeed immediately
- Latency: <10ms
- Risk: You might get the notification despite disabling it
- Guarantee: System always responds

**Which do you choose?**

This isn't academic. This is a design decision you must make consciously.

### The Real-World Answer

Most notification systems choose **AP** (Availability + Partition tolerance) with *eventual consistency*.

Why?
1. **User expectation**: No one expects instant preference propagation
2. **Forgiveness**: Getting one unwanted notification is annoying but not catastrophic
3. **Scale**: Consistency at 10B users is prohibitively expensive

But this choice has consequences:

```java
// Naive approach (wrong):
Notification n = createNotification(userId, eventType, payload);
UserPreferences prefs = getUserPreferences(userId); // May be stale!
if (prefs.allows(eventType)) {
    send(n);
}

// Better approach:
Notification n = createNotification(userId, eventType, payload);
n.setPreferenceCheckTimestamp(now());
n.setPreferenceVersion(prefs.getVersion()); // Track which version we checked
// Later: if user complains, we can audit using preference version
```

**The lesson**: Choose your CAP trade-off explicitly, then design your system to mitigate the consequences.

## The Two-Phase Nature of Notifications

Here's a subtle but critical insight: **Notifications have two distinct phases with different requirements.**

### Phase 1: Creation → Routing (The Write Path)
**Characteristics:**
- High throughput (1M/sec)
- Need durability (can't lose notifications)
- Can tolerate latency (100ms is fine)
- Benefits from batching

**Requirements:**
- Strong persistence
- Idempotency
- Audit trail

**Think of it like**: Mail sorting at a post office. Volume matters, accuracy matters, but seconds don't.

### Phase 2: Routing → Delivery (The Read Path)
**Characteristics:**
- Variable throughput (depends on user online status)
- Need low latency (<100ms)
- Cannot tolerate staleness
- Difficult to batch (personalized)

**Requirements:**
- Fast lookups (user preferences, device tokens)
- Real-time status (is user online?)
- Graceful degradation

**Think of it like**: Package delivery. Timing matters, personalization matters, batching is hard.

**This dichotomy shapes our architecture.** Different phases need different databases, different scaling strategies, different monitoring.

## The Event Sourcing Mental Model

Traditional approach: Model notification as a mutable entity.

```java
class Notification {
    String id;
    String status; // "created", "sent", "delivered"
    Timestamp updatedAt;
}

// Update status:
notification.setStatus("delivered");
notification.setUpdatedAt(now());
db.update(notification);
```

**Problem**: You've lost history. You can't answer:
- When was it sent?
- How many retries occurred?
- What was the error message from the first attempt?

**Event sourcing approach**: Model notification as a sequence of immutable events.

```java
sealed interface NotificationEvent {
    String notificationId();
    Timestamp occurredAt();
}

record NotificationCreated(String notificationId, Timestamp occurredAt,
                          String userId, EventType type, JsonNode payload)
    implements NotificationEvent {}

record NotificationValidated(String notificationId, Timestamp occurredAt)
    implements NotificationEvent {}

record NotificationDispatched(String notificationId, Timestamp occurredAt,
                             String channel, String destination)
    implements NotificationEvent {}

record NotificationDelivered(String notificationId, Timestamp occurredAt,
                            String providerId)
    implements NotificationEvent {}

// Current state is derived from event history:
List<NotificationEvent> events = eventStore.getEvents(notificationId);
NotificationState state = NotificationState.fromEvents(events);
```

**Benefits:**
- Complete audit trail
- Can replay history
- Can derive new metrics retroactively
- Time-travel debugging ("What was state at 3:00 PM?")

**Costs:**
- More storage
- More complex queries
- Need to handle event compaction

**When to use event sourcing?**
- When audit trail is critical (financial, security, compliance)
- When you need to analyze historical behavior
- When debugging complex distributed flows

**When to skip it?**
- When storage is constrained
- When only current state matters
- When scale makes history prohibitive

## Interview Question: The Idempotency Challenge

Here's a real scenario:

Your notification creation API receives:
```json
{
  "user_id": "user_123",
  "event_type": "message",
  "payload": { "from": "Alice", "text": "Hi!" },
  "request_id": "req_abc789"
}
```

Due to a network hiccup, the client retries. You receive the **identical request** 3 times within 100ms.

**Question 1**: How do you ensure the user gets only ONE notification?

**Question 2**: How do you ensure the client gets a successful response for all 3 requests (even though you only process it once)?

**Question 3**: What happens if the 3rd request arrives 1 hour later?

Take a moment to think through the solution before reading on.

<details>
<summary>Solution Approach</summary>

```java
public class NotificationService {
    private final IdempotencyStore idempotencyStore;
    private final NotificationStore notificationStore;

    public NotificationResult create(NotificationRequest req) {
        // Check if we've seen this request_id before
        Optional<NotificationResult> cached =
            idempotencyStore.get(req.getRequestId());

        if (cached.isPresent()) {
            // Return cached result (idempotent response)
            return cached.get();
        }

        // First time seeing this request
        Notification notification = processNotification(req);
        NotificationResult result = new NotificationResult(
            notification.getId(), "CREATED"
        );

        // Cache the result with TTL (e.g., 24 hours)
        idempotencyStore.put(req.getRequestId(), result, Duration.ofHours(24));

        return result;
    }
}
```

**Answer 1**: Use `request_id` as an idempotency key.

**Answer 2**: Cache the response for each `request_id`. Return cached response for duplicates.

**Answer 3**: TTL on idempotency cache. After 1 hour, treat as new request. This is a trade-off:
- Short TTL: Risk duplicate notifications
- Long TTL: Higher storage cost
- Typical: 24-72 hours based on expected retry windows
</details>

## The Dependency Graph

Notifications don't exist in isolation. They have dependencies:

```
Notification depends on:
  ├─→ User (who gets it?)
  ├─→ Preferences (should they get it?)
  ├─→ Devices (where to send it?)
  ├─→ Template (how to format it?)
  └─→ External Event (what happened?)

User depends on:
  ├─→ Account status (active? suspended?)
  └─→ Subscription tier (free? premium?)

Preferences depend on:
  ├─→ Global defaults
  ├─→ User overrides
  └─→ Compliance rules (GDPR quiet hours)

Devices depend on:
  ├─→ Device registration
  └─→ Token validity
```

**Each dependency is a potential point of failure.**

What happens when:
- User is deleted mid-processing?
- Preference service is down?
- Device token is invalid?
- Template rendering fails?

**The First Principle of Fault Tolerance**: Design for the dependency graph, not the happy path.

## The Philosophical Question: Is a Notification Delivered if No One Sees It?

This sounds like a Zen koan, but it's a real design question.

**Scenario**: You send a push notification. Apple's APNs returns "200 OK - Delivered to device."

But:
- The user's phone is in airplane mode
- The user has notifications muted
- The user dismissed it without reading
- The user uninstalled the app

**Was the notification "delivered"?**

**From APNs perspective**: Yes (reached the device)
**From user perspective**: No (didn't see it)
**From business perspective**: No (didn't achieve intent)

**This ambiguity has real consequences:**

```java
// What does this metric actually measure?
metrics.increment("notifications.delivered");

// Is it:
// A) Successfully sent to APNs?
// B) Confirmed received by device?
// C) Displayed to user?
// D) Acknowledged by user?
```

**The lesson**: Define your terms precisely. Different stakeholders need different definitions.

```java
enum DeliveryStatus {
    SENT_TO_PROVIDER,      // We gave it to APNs/FCM
    CONFIRMED_BY_PROVIDER, // Provider acknowledged
    DEVICE_RECEIVED,       // Device reported receipt
    USER_DISPLAYED,        // Shown on screen
    USER_ENGAGED           // User clicked/dismissed
}
```

Now you can measure what matters to each stakeholder.

## If I Built This Again

**What I'd do differently**: I'd spend more time modeling states. My first system had 3 states (sent, delivered, failed). We couldn't answer "Why did this notification arrive 5 hours late?" because we didn't track queuing, routing, retries.

**What I learned**: The state machine IS your design. Get it right early, because changing it later means data migration at scale.

**What I'd do the same**: Event sourcing for critical notifications (security, financial). The extra storage cost is trivial compared to the debugging value.

## Exercises

1. **State Machine Expansion**: Add "SCHEDULED" state for future-delivery notifications. What transitions change? What new edge cases appear?

2. **CAP Trade-offs**: Design a notification system that chooses CP (Consistency + Partition tolerance) instead of AP. What changes? What's the latency impact?

3. **Dependency Analysis**: Draw the dependency graph for a "payment successful" notification. What external services must be reachable? What happens if each fails?

4. **Semantic Precision**: Define "delivery latency" for your system. When does the clock start? When does it stop? How do you measure it?

## Chapter Summary

- **Atomic structure**: Notifications are Identity + Routing + Context
- **State machines**: 17 states, 23 transitions—model them explicitly
- **CAP theorem**: Choose AP (availability + partition tolerance) with eventual consistency
- **Two phases**: Write path (high throughput, durable) vs. Read path (low latency, fresh)
- **Event sourcing**: Immutable events give you time-travel debugging
- **Idempotency**: Cache results by request_id with TTL
- **Dependencies**: Map the graph, design for failure
- **Precise definitions**: "Delivered" means different things to different stakeholders

## What's Next

We've defined what a notification is and traced its lifecycle. But we still have a critical question: **How do we store billions of these efficiently?**

Data model design determines everything: performance, scalability, evolvability.

In [Chapter 3: The Data Model](./chapter-03-data-model.md), we'll answer:
- How do you model user preferences for 10 billion users?
- How do you handle schema evolution without downtime?
- What's the right balance between normalization and denormalization?
- How do you design for queries you don't know yet?

---

*"A precisely defined problem is halfway solved. An ambiguously defined problem is impossible to solve."*
