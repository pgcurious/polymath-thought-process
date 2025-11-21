# Chapter 5: The Brain: The Routing Engine

> "Intelligence is knowing what to do when you don't know what to do."

## The Three-Second Decision

A notification enters the routing engine. You have 3 milliseconds to answer:

1. **Should** this user receive this notification? (Preferences)
2. **How** should it be formatted? (Template rendering)
3. **Where** should it go? (Channel selection)
4. **When** should it be sent? (Timing optimization)
5. **Which** variant should they see? (A/B testing)

At 1 million notifications per second, you make **333,000 routing decisions per millisecond**.

Each decision requires:
- Database lookups (user preferences, device tokens)
- Template rendering (localization, personalization)
- Business logic evaluation (priority scoring, channel selection)
- External service calls (user segmentation, feature flags)

**How do you make 333,000 intelligent decisions per millisecond?**

This is the hardest part of the system.

## Thought Experiment #1: The Three Data Points

You can only keep **3 pieces of user data** in memory for routing decisions.

The rest must be fetched (with latency penalty) or inferred.

**Which 3 do you choose?**

Options:
- User ID
- Preferred language
- Timezone
- Notification preferences (all event types)
- Device tokens (all devices)
- Online status
- Last seen timestamp
- Subscription tier (free/premium)
- Location (country/city)
- User engagement score
- Notification fatigue score

**Take a moment. What are your 3?**

<details>
<summary>My answer</summary>

**1. User ID** (obviously—need to know who)

**2. Notification preference bitmap** (200 bits = 25 bytes encoding all event types × channels)
   - Answers "should" question instantly
   - Compact enough to cache for all users
   - Rarely changes (can cache for hours)

**3. Last activity timestamp**
   - Determines online status (send now vs. batch)
   - Indicates timezone (infer from activity pattern)
   - Measures engagement (recent activity = higher engagement)

**What I'd infer/fetch:**
- Language: Infer from historical notifications, fallback to account setting (cached)
- Device tokens: Fetch on-demand (only for users who pass preference check)
- Location: Not needed for most routing decisions
- Subscription tier: Embed in user_id (e.g., ID ranges) or fetch

**The lesson**: Design for the data you have, not the data you want.
</details>

## The Routing Pipeline: Five Stages

```
┌──────────────────────────────────────────────────────────────┐
│  Stage 1: Preference Evaluation                              │
│  "Should this user get this notification?"                   │
└───────────────────────┬──────────────────────────────────────┘
                        │
                 [FILTER] 70% drop here
                        │
                        ↓
┌──────────────────────────────────────────────────────────────┐
│  Stage 2: Channel Selection                                  │
│  "Push, email, SMS, or in-app?"                              │
└───────────────────────┬──────────────────────────────────────┘
                        │
                 [ROUTE] Split into channels
                        │
                        ↓
┌──────────────────────────────────────────────────────────────┐
│  Stage 3: Template Rendering                                 │
│  "What does it look like?"                                   │
└───────────────────────┬──────────────────────────────────────┘
                        │
                   [TRANSFORM]
                        │
                        ↓
┌──────────────────────────────────────────────────────────────┐
│  Stage 4: Delivery Optimization                              │
│  "When and how to send?"                                     │
└───────────────────────┬──────────────────────────────────────┘
                        │
                  [OPTIMIZE]
                        │
                        ↓
┌──────────────────────────────────────────────────────────────┐
│  Stage 5: Dispatch                                           │
│  "Hand off to delivery layer"                                │
└──────────────────────────────────────────────────────────────┘
```

Let's build each stage.

## Stage 1: Preference Evaluation

### The Naive Approach (Don't Do This)

```java
public boolean shouldSend(String userId, EventType eventType, Channel channel) {
    // Database query for each routing decision
    UserPreferences prefs = db.query(
        "SELECT * FROM user_preferences WHERE user_id = ?", userId
    );

    return prefs.isEnabled(eventType, channel);
}
```

**At 1M notifications/sec:**
- 1 million database queries/second
- Even with perfect caching, CPU to deserialize results is prohibitive

### The Evolved Approach: Preference Cache

```java
public class PreferenceEvaluator {
    private final LoadingCache<String, PreferenceBitmap> cache;

    public PreferenceEvaluator() {
        this.cache = Caffeine.newBuilder()
            .maximumSize(10_000_000)  // 10M users = ~250 MB
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build(this::loadPreferences);
    }

    private PreferenceBitmap loadPreferences(String userId) {
        // Fetch from database (happens once per hour per user)
        UserPreferences prefs = preferenceService.get(userId);
        return PreferenceBitmap.from(prefs);
    }

    public boolean shouldSend(String userId, EventType eventType, Channel channel) {
        PreferenceBitmap bitmap = cache.get(userId);

        // Bitmap check: O(1), no deserialization
        int bitIndex = eventType.ordinal() * 4 + channel.ordinal();
        return bitmap.isSet(bitIndex);
    }
}
```

### The Preference Bitmap

```java
public class PreferenceBitmap {
    // 50 event types × 4 channels = 200 bits = 25 bytes
    private final byte[] bits = new byte[25];

    public boolean isSet(int index) {
        int byteIndex = index / 8;
        int bitIndex = index % 8;
        return (bits[byteIndex] & (1 << bitIndex)) != 0;
    }

    public void set(int index, boolean value) {
        int byteIndex = index / 8;
        int bitIndex = index % 8;

        if (value) {
            bits[byteIndex] |= (1 << bitIndex);
        } else {
            bits[byteIndex] &= ~(1 << bitIndex);
        }
    }

    public static PreferenceBitmap from(UserPreferences prefs) {
        PreferenceBitmap bitmap = new PreferenceBitmap();

        for (EventType event : EventType.values()) {
            for (Channel channel : Channel.values()) {
                int index = event.ordinal() * 4 + channel.ordinal();
                bitmap.set(index, prefs.isEnabled(event, channel));
            }
        }

        return bitmap;
    }
}
```

**Performance:**
- Cache hit: <1μs (bitmap check)
- Cache miss: ~10ms (database fetch + bitmap construction)
- Memory: 25 bytes/user × 10M cached users = 250 MB

**At 70% filter rate**: 1M notifications → 300K pass preferences → saves 700K downstream processing steps.

## Stage 2: Channel Selection

Not all channels are equal. If a user has push enabled, should you also send email?

### The Multi-Channel Strategy

```java
public class ChannelSelector {

    public Set<Channel> selectChannels(
            NotificationContext context,
            PreferenceBitmap preferences) {

        Set<Channel> selected = new HashSet<>();

        // Priority 1: Real-time channels (if user is online)
        if (context.isUserOnline()) {
            if (preferences.allows(context.eventType, Channel.PUSH)) {
                selected.add(Channel.PUSH);
            }
            if (preferences.allows(context.eventType, Channel.IN_APP)) {
                selected.add(Channel.IN_APP);
            }
        }

        // Priority 2: Durable channels (if high importance)
        if (context.priority.getImpactScore() >= 70) {
            if (preferences.allows(context.eventType, Channel.EMAIL)) {
                selected.add(Channel.EMAIL);
            }
        }

        // Priority 3: Expensive channels (if critical)
        if (context.priority == Priority.CRITICAL) {
            if (preferences.allows(context.eventType, Channel.SMS)) {
                selected.add(Channel.SMS);
            }
        }

        // Fallback: If user is offline, use email
        if (selected.isEmpty() && !context.isUserOnline()) {
            if (preferences.allows(context.eventType, Channel.EMAIL)) {
                selected.add(Channel.EMAIL);
            }
        }

        return selected;
    }
}
```

**Channel characteristics:**

| Channel | Latency | Cost/1M | Delivery Rate | User Engagement |
|---------|---------|---------|---------------|-----------------|
| Push    | <1s     | $10     | 95%           | 15% click rate  |
| In-app  | <100ms  | $1      | 100% (if open)| 40% click rate  |
| Email   | 1-60s   | $100    | 98%           | 3% click rate   |
| SMS     | 1-5s    | $10,000 | 99%           | 20% click rate  |

**Strategy:**
- Cheap + effective: Push + In-app (always use if user online)
- Expensive + effective: SMS (only for critical)
- Cheap + persistent: Email (for high-importance, offline users)

## Stage 3: Template Rendering

### The Template System

```java
public class TemplateRenderer {
    private final Map<EventType, Template> templates;
    private final LocalizationService i18n;

    public RenderedNotification render(
            NotificationContext context,
            Channel channel) {

        Template template = templates.get(context.eventType);

        // Get user's language
        String language = context.userLanguage;

        // Render title
        String title = i18n.translate(
            template.getTitleKey(),
            language,
            context.payload
        );

        // Render body
        String body = i18n.translate(
            template.getBodyKey(),
            language,
            context.payload
        );

        // Channel-specific rendering
        return switch (channel) {
            case PUSH -> renderPush(title, body, context);
            case EMAIL -> renderEmail(title, body, context);
            case SMS -> renderSMS(title, body, context);
            case IN_APP -> renderInApp(title, body, context);
        };
    }

    private RenderedNotification renderPush(
            String title, String body, NotificationContext ctx) {

        return new PushNotification(
            title,
            body,
            ctx.payload.get("image_url"),
            ctx.payload.get("action_url"),
            // iOS-specific
            ctx.priority.getImpactScore() > 50 ? "default" : "passive",
            // Badge count
            ctx.userState.getUnreadCount()
        );
    }
}
```

### Template Example

```json
{
  "event_type": "photo_tag",
  "templates": {
    "title": {
      "en": "{{tagger_name}} tagged you in a photo",
      "es": "{{tagger_name}} te etiquetó en una foto",
      "fr": "{{tagger_name}} vous a identifié dans une photo"
    },
    "body": {
      "en": "{{tagger_name}} tagged you with {{other_count}} others at {{location}}",
      "es": "{{tagger_name}} te etiquetó con {{other_count}} otras personas en {{location}}",
      "fr": "{{tagger_name}} vous a identifié avec {{other_count}} autres personnes à {{location}}"
    }
  }
}
```

### The Performance Challenge

**Naive rendering:**

```java
// Parse template on every render
String template = "{{name}} sent you a message";
String rendered = template
    .replace("{{name}}", payload.get("name"))
    .replace("{{message}}", payload.get("message"));
```

**At 1M/sec**: Millions of string allocations, GC pressure, CPU waste.

**Optimized rendering:**

```java
public class CompiledTemplate {
    // Pre-parsed template components
    private final List<TemplatePart> parts;

    static abstract class TemplatePart {
        abstract String render(Map<String, String> payload);
    }

    static class LiteralPart extends TemplatePart {
        private final String value;

        String render(Map<String, String> payload) {
            return value;  // No allocation
        }
    }

    static class VariablePart extends TemplatePart {
        private final String key;

        String render(Map<String, String> payload) {
            return payload.getOrDefault(key, "");
        }
    }

    public String render(Map<String, String> payload) {
        StringBuilder sb = new StringBuilder(128);  // Pre-sized
        for (TemplatePart part : parts) {
            sb.append(part.render(payload));
        }
        return sb.toString();
    }

    // Compile once, render millions of times
    public static CompiledTemplate compile(String template) {
        List<TemplatePart> parts = new ArrayList<>();
        // Parse template into parts (done once at startup)
        // ...
        return new CompiledTemplate(parts);
    }
}
```

**Performance improvement**: 10-100× faster, zero GC overhead.

## Stage 4: Delivery Optimization

### The Batching Decision

**Question**: You have 10 notifications for the same user within 1 minute. Do you send 10 separate notifications or 1 combined notification?

**It depends:**

```java
public class BatchingOptimizer {

    public DeliveryStrategy optimize(List<Notification> notifications) {
        // Group by user
        Map<String, List<Notification>> byUser = notifications.stream()
            .collect(Collectors.groupingBy(Notification::getUserId));

        List<DeliveryPlan> plans = new ArrayList<>();

        for (var entry : byUser.entrySet()) {
            String userId = entry.getKey();
            List<Notification> userNotifs = entry.getValue();

            if (shouldCoalesce(userNotifs)) {
                // Combine into single notification
                plans.add(createCoalescedPlan(userId, userNotifs));
            } else {
                // Send individually
                for (Notification n : userNotifs) {
                    plans.add(createIndividualPlan(n));
                }
            }
        }

        return new DeliveryStrategy(plans);
    }

    private boolean shouldCoalesce(List<Notification> notifications) {
        // Don't coalesce critical notifications
        if (notifications.stream().anyMatch(n -> n.isCritical())) {
            return false;
        }

        // Don't coalesce different event types
        long distinctTypes = notifications.stream()
            .map(Notification::getEventType)
            .distinct()
            .count();

        if (distinctTypes > 1) {
            return false;
        }

        // Coalesce if many notifications of same type
        return notifications.size() >= 5;
    }

    private DeliveryPlan createCoalescedPlan(
            String userId,
            List<Notification> notifications) {

        // "5 people liked your post" instead of 5 individual notifications
        return new DeliveryPlan(
            userId,
            notifications.get(0).getEventType(),
            String.format("%d %s",
                notifications.size(),
                notifications.get(0).getEventType().getPluralName())
        );
    }
}
```

### The Timing Optimizer

```java
public class TimingOptimizer {

    public Instant determineDeliveryTime(
            NotificationContext context,
            UserState userState) {

        // Critical: Send immediately
        if (context.priority == Priority.CRITICAL) {
            return Instant.now();
        }

        // User is online: Send immediately
        if (userState.isOnline()) {
            return Instant.now();
        }

        // User has quiet hours: Delay until quiet hours end
        if (userState.isInQuietHours()) {
            return userState.getQuietHoursEnd();
        }

        // Optimize for user's active hours
        if (context.priority == Priority.LOW) {
            return predictNextActiveTime(userState);
        }

        return Instant.now();
    }

    private Instant predictNextActiveTime(UserState userState) {
        // ML model: When is this user likely to be active next?
        // Fallback: Historical pattern (e.g., usually active 7-9 AM)

        LocalTime typicalActiveTime = userState.getTypicalMorningActiveTime();
        ZonedDateTime userTime = Instant.now().atZone(userState.getTimezone());

        ZonedDateTime nextActive = userTime.with(typicalActiveTime);

        // If we've passed today's active time, schedule for tomorrow
        if (nextActive.isBefore(userTime)) {
            nextActive = nextActive.plusDays(1);
        }

        return nextActive.toInstant();
    }
}
```

**Benefits:**
- Reduces notification fatigue (batching)
- Increases engagement (timing optimization)
- Respects user preferences (quiet hours)

**Costs:**
- Adds latency (delayed delivery)
- Increases complexity (scheduling system)

## Stage 5: A/B Testing

### The Experimentation Framework

```java
public class ABTestingService {
    private final ExperimentConfigService configService;

    public Variant getVariant(String userId, String experimentId) {
        ExperimentConfig config = configService.get(experimentId);

        if (config == null || !config.isActive()) {
            return Variant.CONTROL;
        }

        // Consistent hashing: same user always gets same variant
        long hash = hashUserId(userId, experimentId);
        long bucket = hash % 100;  // 0-99

        // Experiment: 50% control, 30% variant A, 20% variant B
        if (bucket < 50) {
            return Variant.CONTROL;
        } else if (bucket < 80) {
            return Variant.A;
        } else {
            return Variant.B;
        }
    }

    private long hashUserId(String userId, String experimentId) {
        // Combine userId + experimentId for consistent hashing
        String combined = userId + ":" + experimentId;
        return Hashing.murmur3_128().hashString(combined, StandardCharsets.UTF_8)
            .asLong();
    }
}
```

### Template Variants

```java
public RenderedNotification render(NotificationContext context) {
    // Check for active experiments
    Variant variant = abTestingService.getVariant(
        context.userId,
        "notification_copy_test_2024_q1"
    );

    Template template = switch (variant) {
        case CONTROL -> templates.get(context.eventType);
        case A -> templates.get(context.eventType + "_variant_a");
        case B -> templates.get(context.eventType + "_variant_b");
    };

    // Track which variant was shown
    context.setExperimentVariant(variant);

    return template.render(context);
}
```

**Experiment examples:**
- **Copy testing**: "New message from Alice" vs. "Alice sent you a message"
- **Timing testing**: Immediate vs. batched
- **Channel testing**: Push-only vs. Push+Email
- **Priority testing**: Different priority scoring formulas

## Polymath's Corner: The Router as a Neuron

A routing decision is like a neuron firing:

**Inputs** (dendrites):
- User preferences
- Notification priority
- User state (online/offline)
- Historical engagement
- Time of day

**Activation function** (soma):
```
fire = (preference_score * 0.3
      + priority_score * 0.4
      + engagement_score * 0.2
      + timing_score * 0.1) > threshold
```

**Output** (axon):
- Send to channel(s)
- Or suppress

**The brain parallelizes billions of neurons. We parallelize millions of routing decisions.**

## Interview Question: The Routing Optimization Challenge

**Scenario**: Your routing engine processes 300K notifications/sec. You notice:
- 40% of notifications are filtered by preferences
- 30% are delayed due to quiet hours
- Only 30% are sent immediately

**Question 1**: Where should you optimize first?

**Question 2**: Preference lookups take 50μs each (cache hit). That's 15 seconds of CPU time per second. How do you reduce this?

**Question 3**: A new requirement: Support per-contact notification preferences (e.g., "Mute Alice"). How does this change your preference model?

<details>
<summary>Solution Approach</summary>

**Answer 1**: Optimize the 40% filter case.
- Move preference check earlier (before template rendering)
- Use coarser-grained first-pass filter (cheaper check)
- Example: Check if user has *any* notifications enabled before checking specific event type

**Answer 2**: Reduce preference lookup cost:
- Batch lookups (fetch 100 users' preferences in one query)
- Denormalize preferences into notification payload (add 25 bytes, save 50μs)
- Use probabilistic filters (Bloom filter: 99% accurate, 1μs lookup)

**Answer 3**: Per-contact preferences:
- Add "muted_contacts" bitmap to user preferences
- Hash contact_id to bit index
- Check: `preferences.isContactMuted(contactId)`
- Storage: 10,000 contacts per user × 1 bit = 1.25 KB per user (manageable)
</details>

## If I Built This Again

**What I'd do differently**: Invest in a proper rule engine earlier. I initially hard-coded routing logic. As requirements grew, the code became unmaintainable.

**What I learned**: The routing engine is the most complex component. It touches every notification, has the most dependencies, and changes most frequently. Design for evolution from day 1.

**What I'd do the same**: Heavy caching of preferences. This single optimization gave us 100× performance improvement.

## Exercises

1. **Design Rule Engine**: Create a DSL for routing rules (e.g., "IF user.timezone = PST AND hour < 8 THEN delay UNTIL 8 AM").

2. **Calculate Throughput**: If preference evaluation takes 50μs, template rendering takes 100μs, and channel selection takes 20μs, what's the maximum throughput per CPU core?

3. **Preference Compression**: Design a more compact encoding than bitmaps for preferences. Can you use fewer than 25 bytes?

4. **A/B Test Analysis**: You ran an experiment with 3 variants. Control: 10% click rate. Variant A: 12% click rate. Variant B: 11% click rate. With 1M samples each, is Variant A statistically significant?

## Chapter Summary

- **Preference evaluation**: Bitmap encoding, in-memory cache, <1μs per check
- **Channel selection**: Multi-channel strategy based on cost, engagement, user state
- **Template rendering**: Compiled templates, pre-parsed, zero-allocation rendering
- **Delivery optimization**: Batching, timing, quiet hours, active hours prediction
- **A/B testing**: Consistent hashing, variant assignment, experiment tracking
- **Performance**: Design for 300K routing decisions/second per instance

## What's Next

The routing engine decided: Send this notification via push to this device at this time with this content.

Now we need to actually deliver it. How do we integrate with APNs, FCM, SMTP, and SMS gateways? How do we handle failures, retries, and rate limits?

In [Chapter 6: The Delivery Layer](./chapter-06-delivery-layer.md), we'll build the final mile—reaching across the void to devices scattered across the planet.

---

*"The routing engine is where data becomes intelligence."*
