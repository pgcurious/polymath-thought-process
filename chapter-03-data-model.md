# Chapter 3: The Data Model: Encoding Human Attention

> "Show me your data structures, and I'll know your system's future."

## The Question That Determines Everything

You're about to make the most important design decision for your notification system. Not which database to use. Not which message queue. Not which cloud provider.

**The question is**: How do you represent a notification in bytes?

Everything else flows from this choice:
- Your query patterns
- Your scalability ceiling
- Your ability to evolve
- Your operational costs

Get it right, and you can scale to billions. Get it wrong, and you'll spend years in painful migrations.

## Thought Experiment #1: The Naive Schema

Let's start with the most obvious design. You're a smart engineer. You know SQL. You design this:

```sql
CREATE TABLE notifications (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    title TEXT,
    body TEXT,
    image_url VARCHAR(500),
    action_url VARCHAR(500),
    priority VARCHAR(20),
    created_at TIMESTAMP NOT NULL,
    sent_at TIMESTAMP,
    delivered_at TIMESTAMP,
    read_at TIMESTAMP,
    status VARCHAR(20),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_user_notifications ON notifications(user_id, created_at DESC);
CREATE INDEX idx_status ON notifications(status);
```

**Question**: What's wrong with this?

At 1,000 users: Nothing. It works great.
At 1,000,000 users: Still fine.
At 10,000,000 users: Starting to slow down.
At 1,000,000,000 users: **Catastrophic failure.**

Let's do the math.

## The Scale Calculation That Changes Everything

**Assumptions:**
- 10 billion users
- Average 10 notifications per user per day
- Retain 90 days of history

**Storage:**

```
Rows: 10B users × 10 notifs/day × 90 days = 9 trillion rows

Per row size estimation:
  id: 8 bytes
  user_id: 8 bytes
  event_type: 50 bytes (varchar)
  title: 100 bytes (average)
  body: 500 bytes (average)
  image_url: 100 bytes
  action_url: 100 bytes
  priority: 20 bytes
  timestamps: 8 × 4 = 32 bytes
  status: 20 bytes
  --------------
  Total: ~938 bytes per row

Total data: 9 trillion × 938 bytes = 8.4 petabytes
```

**Index size:**

```
idx_user_notifications: (user_id + created_at + row pointer)
  = 8 + 8 + 8 = 24 bytes per row
  = 9 trillion × 24 = 216 terabytes

idx_status: ~216 terabytes

Total indexes: ~432 TB
```

**Grand total: 8.4 PB + 432 TB = ~9 PB**

**Query performance:**

```sql
-- Fetch user's latest notifications
SELECT * FROM notifications
WHERE user_id = 123456
ORDER BY created_at DESC
LIMIT 20;
```

Even with an index, this query scans on average hundreds of thousands of rows per user. At 10 billion users, you're looking at:
- Index lookup: O(log n) where n = 9 trillion
- Index scan: O(k) where k = rows per user

**The problem isn't that this design is wrong. The problem is that it doesn't scale.**

## The Three Fundamental Tensions

Before we redesign, let's identify what we're optimizing for:

### Tension 1: Normalization vs. Denormalization

**Normalization says**: Don't repeat data. Store user info in `users` table, notification templates in `templates` table. Join when needed.

**Benefits:**
- No redundancy
- Single source of truth
- Easy updates

**Costs at scale:**
- Every query becomes a join
- Joins across sharded databases are expensive
- Cross-datacenter joins are impossible

**Denormalization says**: Embed all data needed for a query in one place.

**Benefits:**
- Single lookup per query
- No joins needed
- Can shard independently

**Costs:**
- Data duplication
- Consistency challenges
- Storage bloat

**The question**: For notifications, what data do we need in the hot path?

### Tension 2: Mutable vs. Immutable

**Mutable approach**: Notification is an entity that changes state.

```java
notification.setStatus("delivered");
notification.setDeliveredAt(now());
db.update(notification);
```

**Benefits:**
- Smaller storage (one row per notification)
- Familiar mental model
- Easy to query "current state"

**Costs:**
- Lost history
- Race conditions (concurrent updates)
- Difficult to debug

**Immutable approach**: Notification is a sequence of events.

```java
events.append(new NotificationDelivered(notificationId, now()));
```

**Benefits:**
- Complete audit trail
- No race conditions
- Can derive new metrics later

**Costs:**
- More storage
- Complex queries for "current state"
- Need event compaction

### Tension 3: Hot vs. Cold Storage

Not all notifications are equal:

**Hot data**: Recent notifications (last 7 days)
- Need: Fast reads, frequent access
- Volume: ~700 billion notifications (10B users × 10/day × 7 days)
- Storage tier: SSD, in-memory cache

**Warm data**: Recent history (7-90 days)
- Need: Occasional access, analytics
- Volume: ~8.3 trillion notifications
- Storage tier: SSD

**Cold data**: Archive (>90 days)
- Need: Compliance, rare access
- Volume: Unbounded (grows forever)
- Storage tier: Object storage (S3, GCS)

**The question**: How do you design a schema that smoothly transitions data between tiers?

## The Evolved Schema: Partition by Time and User

Here's the key insight: **Most notification queries are scoped by user and time.**

```sql
-- This is the dominant query pattern:
SELECT * FROM notifications
WHERE user_id = ?
  AND created_at > ?
ORDER BY created_at DESC
LIMIT ?;
```

So let's design for this pattern.

### Design 1: Partition by User

```sql
-- Instead of one table, use a sharding key
CREATE TABLE notifications_shard_{user_id % 1000} (
    id BIGINT,
    user_id BIGINT,
    -- ... rest of fields
    PRIMARY KEY (user_id, id)
) PARTITION BY RANGE (created_at) (
    PARTITION p_2024_01 VALUES LESS THAN ('2024-02-01'),
    PARTITION p_2024_02 VALUES LESS THAN ('2024-03-01'),
    -- ...
);
```

**Benefits:**
- User queries hit one shard
- Can partition historical data by month
- Can drop old partitions easily

**Challenges:**
- Need to route queries to correct shard
- Resharding (changing shard count) is painful
- Global queries (admin, analytics) are expensive

### Design 2: Separate Hot and Cold

```sql
-- Hot storage: Recent notifications
CREATE TABLE notifications_hot (
    id BIGINT PRIMARY KEY,
    user_id BIGINT,
    payload JSONB,  -- Denormalized, everything needed for display
    created_at TIMESTAMP,
    INDEX idx_user_time (user_id, created_at DESC)
) WITH (
    ttl = 7 days  -- Automatically archive after 7 days
);

-- Cold storage: Archive
CREATE TABLE notifications_archive (
    id BIGINT,
    user_id BIGINT,
    payload JSONB,
    created_at TIMESTAMP,
    archived_at TIMESTAMP,
    PRIMARY KEY (user_id, created_at, id)
) PARTITION BY RANGE (created_at);
```

**Benefits:**
- Hot queries are blazing fast (smaller index)
- Can use different storage engines
- Automated archival

**Challenges:**
- Need to query both tables for "last 14 days"
- Archival process adds complexity

### Design 3: The NoSQL Approach

```javascript
// Document in Cassandra/DynamoDB
{
    partition_key: "user_123",        // Partition by user
    sort_key: "2024-01-15T10:30:00Z", // Sort by time
    notification_id: "notif_abc",
    event_type: "message",
    payload: {
        from_user: "Alice",
        message: "Hi there!",
        image_url: "https://..."
    },
    status: "delivered",
    delivered_at: "2024-01-15T10:30:05Z",
    ttl: 1707987000  // Auto-delete after 90 days
}
```

**Query pattern:**
```sql
SELECT * FROM notifications
WHERE partition_key = 'user_123'
  AND sort_key > '2024-01-01'
ORDER BY sort_key DESC
LIMIT 20;
```

**Benefits:**
- Scales horizontally automatically
- Built-in TTL (auto-deletion)
- Fast range queries on sort key
- No need to manage partitions manually

**Challenges:**
- Can't query by notification_id efficiently (need secondary index)
- Can't query by status globally (need GSI with careful design)
- Cost scales with throughput

**This is the design most large-scale systems use.**

## The Philosophy of Priority: Creating a Universal Ranking System

Here's a deceptively hard problem: **How do you design a priority system that works for every type of notification?**

### The Naive Approach

```java
enum Priority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
```

**Seems simple. But ask these questions:**

- Is a "high priority" social notification more important than a "medium priority" security alert?
- Should "low priority" marketing emails ever supersede "high priority" comment notifications?
- How do you add a new priority level without breaking existing code?

**The problem**: Priority is context-dependent, but we need a global ordering.

### Thought Experiment #2: The Priority Dilemma

You have 5 pending notifications for a user:

1. Security alert: "Suspicious login from new device" (HIGH)
2. Payment: "Your subscription will renew tomorrow" (MEDIUM)
3. Social: "Friend tagged you in a photo" (HIGH)
4. Breaking news: "Earthquake detected in your area" (CRITICAL)
5. App update: "New feature available" (LOW)

**Question 1**: What order do you deliver them?

Obvious answer: CRITICAL → HIGH → HIGH → MEDIUM → LOW

**Question 2**: Now the user's notification quota is 2 per hour (to prevent fatigue). Which 2 do you send?

Obvious answer: #4 and #1 (earthquake and security)

**Question 3**: But what if the user has disabled "News" notifications in their preferences?

Now you can't send #4. Do you send #1 and #2? Or #1 and #3?

**Question 4**: What if the earthquake happened 2 hours ago, but the security alert just happened?

Should recency override priority?

**This is why priority is hard.**

### The Multi-Dimensional Priority System

Instead of a single number, use a vector:

```java
class NotificationPriority {
    // Category importance (0-100)
    int categoryScore;

    // Time sensitivity (0-100)
    // 100 = must deliver within seconds
    // 0 = can wait days
    int urgencyScore;

    // User impact (0-100)
    // 100 = affects user's safety/money
    // 0 = purely informational
    int impactScore;

    // Derived composite score
    double getCompositeScore(Instant now, Instant eventTime) {
        double recencyFactor = calculateDecay(now, eventTime);

        return (categoryScore * 0.3
              + urgencyScore * 0.4
              + impactScore * 0.3)
              * recencyFactor;
    }

    private double calculateDecay(Instant now, Instant eventTime) {
        Duration age = Duration.between(eventTime, now);

        // Exponential decay: value halves every hour
        double hours = age.toMinutes() / 60.0;
        return Math.pow(0.5, hours);
    }
}
```

**Examples:**

```java
// Security alert
new NotificationPriority(
    categoryScore: 90,  // Security is high priority
    urgencyScore: 95,   // Must act fast
    impactScore: 100    // Affects account safety
)
// Initial score: ~95
// After 1 hour: ~47.5 (still higher than most)

// Social notification
new NotificationPriority(
    categoryScore: 50,
    urgencyScore: 30,   // Not time-sensitive
    impactScore: 10     // Low impact
)
// Initial score: ~30
// After 1 hour: ~15

// Breaking news
new NotificationPriority(
    categoryScore: 70,
    urgencyScore: 100,  // Extremely time-sensitive
    impactScore: 80     // Could affect safety
)
// Initial score: ~85
// After 1 hour: ~42.5 (decays faster due to high urgency)
```

**This model captures nuance**: A security alert from 10 minutes ago is more important than a social notification from 1 second ago.

## Polymath's Corner: Priority Systems in Nature

Your body has a multi-dimensional priority system for processing sensory input:

**Pain**: High impact, high urgency, moderate category
- Composite score: ~90
- Decay: Slow (pain matters even if not fresh)

**Hunger**: High impact, low urgency, low category
- Composite score: ~40
- Decay: Slow (accumulates over time)

**Social notification (someone says your name)**: Low impact, high urgency, high category
- Composite score: ~60
- Decay: Fast (irrelevant after a few seconds)

**Background noise**: Low everything
- Composite score: ~5
- Gets filtered out

Your notification system needs similar sophistication.

## Schema Evolution: Designing for the Unknown

Here's what will happen after you launch:

**Week 1**: "Can we add video thumbnails?"
**Month 2**: "We need to support interactive buttons."
**Month 6**: "Can we show notifications in AR glasses?"
**Year 2**: "New regulation: Must store delivery proof for 7 years."

**Your schema must evolve without downtime.**

### The Versioned Payload Approach

```java
// Don't do this:
class Notification {
    String title;
    String body;
    String imageUrl;
    String videoUrl; // Added in v2
    List<Button> buttons; // Added in v3
    ARContent arContent; // Added in v4
}

// Do this:
class Notification {
    String id;
    int schemaVersion;
    JsonNode payload; // Schemaless JSON
}
```

**Reading with schema versioning:**

```java
public NotificationView render(Notification notification) {
    return switch (notification.getSchemaVersion()) {
        case 1 -> renderV1(notification.getPayload());
        case 2 -> renderV2(notification.getPayload());
        case 3 -> renderV3(notification.getPayload());
        default -> renderLatest(notification.getPayload());
    };
}

private NotificationView renderV1(JsonNode payload) {
    return new NotificationView(
        payload.get("title").asText(),
        payload.get("body").asText(),
        payload.get("imageUrl").asText()
    );
}

private NotificationView renderV2(JsonNode payload) {
    // V2 added video support
    return new NotificationView(
        payload.get("title").asText(),
        payload.get("body").asText(),
        payload.get("imageUrl").asText(),
        payload.get("videoUrl").asText() // New field
    );
}
```

**Benefits:**
- Can read old data with old code
- Can add new fields without migration
- Can run multiple versions during deployment

**Challenges:**
- Need to maintain rendering logic for all versions
- Need strategy for "upgrading" old notifications on read

### The Metadata Table Pattern

Separate frequently-changing metadata from stable core data:

```sql
-- Core table (stable schema)
CREATE TABLE notifications (
    id BIGINT PRIMARY KEY,
    user_id BIGINT,
    event_type VARCHAR(50),
    payload JSONB,
    created_at TIMESTAMP
);

-- Metadata table (evolving schema)
CREATE TABLE notification_metadata (
    notification_id BIGINT,
    key VARCHAR(100),
    value JSONB,
    PRIMARY KEY (notification_id, key)
);
```

**Usage:**

```sql
-- Store rich attributes without schema changes
INSERT INTO notification_metadata VALUES
    (123, 'priority', '{"category": 90, "urgency": 95}'),
    (123, 'ab_test', '{"variant": "A", "campaign": "2024_Q1"}'),
    (123, 'delivery_context', '{"timezone": "PST", "device": "iPhone"}');
```

**Benefits:**
- Add new attributes without ALTER TABLE
- Query specific attributes efficiently
- Can index individual metadata keys

**Challenges:**
- More complex queries (need joins)
- Harder to enforce schema validation

## Interview Question: The User Preferences Problem

**The scenario**: 10 billion users. Each user has preferences for:
- 50 different event types (message, comment, like, tag, etc.)
- 4 delivery channels (push, email, sms, in-app)
- Time windows (e.g., "no notifications 10 PM - 8 AM")
- Language preference
- Custom quiet days

**The requirement**: When routing a notification, check user preferences in <1ms.

**Question 1**: How do you model this data?

**Question 2**: Where do you store it?

**Question 3**: How do you handle preference updates (which happen frequently)?

**Question 4**: What if checking preferences becomes the bottleneck?

<details>
<summary>Solution Approach</summary>

**Model Option 1: Normalized (DON'T DO THIS)**

```sql
CREATE TABLE user_event_preferences (
    user_id BIGINT,
    event_type VARCHAR(50),
    channel VARCHAR(20),
    enabled BOOLEAN,
    PRIMARY KEY (user_id, event_type, channel)
);
-- This creates 10B users × 50 events × 4 channels = 2 trillion rows
-- Storage: ~80 PB (with indexes)
-- Query time: Requires 50-200 lookups per notification
```

**Model Option 2: Denormalized Bitmap**

```java
class UserPreferences {
    long userId;

    // Bitmap: each bit represents event_type + channel
    // 50 events × 4 channels = 200 bits = 25 bytes
    byte[] enabledNotifications;

    // Time windows stored as ranges
    List<TimeWindow> quietHours;

    String languageCode;
}
```

Storage: 10B users × ~100 bytes = 1 TB (manageable!)

Query time: Single lookup, bitmap check is O(1)

**Model Option 3: Hybrid (BEST)**

```java
class UserPreferences {
    // Store defaults in global config
    static final Set<EventType> DEFAULT_ENABLED = Set.of(...);

    // Store only overrides per user
    Map<EventType, ChannelPreferences> overrides;
}
```

Storage: Only users who customize (maybe 10%) = 100 GB

**Where to store:**
- Cache: Redis/Memcached (hot users)
- Persistent: DynamoDB/Cassandra (partition by user_id)
- Fallback: Default preferences if lookup fails

**Handling updates:**
- Write-through: Update cache and DB together
- Invalidate: Delete from cache on update, repopulate on next read
- Eventual consistency: OK if user gets one notification during propagation

**If preferences become bottleneck:**
- Embed preferences in notification payload (denormalize)
- Pre-compute "allowed" notifications during write phase
- Cache preferences for active users only
</details>

## If I Built This Again

**What I'd do differently**: Start with NoSQL (Cassandra/DynamoDB) instead of trying to scale PostgreSQL. Relational databases are amazing, but they're not built for 9 trillion rows.

**What I learned**: Design for the query patterns you have, not the ones you might want. 99% of queries are "get user's recent notifications." Optimize for that.

**What I'd do the same**: Use JSONB/document model for payload. Schema flexibility is worth more than the storage cost.

## Exercises

1. **Calculate Storage**: Design a schema for notification analytics (click rates, conversion rates per event type). How much data per day at 1M notifications/sec?

2. **Design Preferences**: Model preferences for a feature where users can set "only notify me if more than 10 people like my post." How does this change the data model?

3. **Time-Series Optimization**: Redesign the schema optimized for the query "show me all notifications sent between 2-3 PM yesterday across all users" (admin query for debugging).

4. **Migration Strategy**: You have 5 trillion notifications in the old schema. How do you migrate to the new schema without downtime?

## Chapter Summary

- **Scale forces denormalization**: 9 PB of normalized data is unmanageable
- **Partition by access pattern**: User + time is the dominant query
- **Hot vs. cold storage**: Different tiers for different data ages
- **Priority is multi-dimensional**: Category + urgency + impact + recency
- **Schema must evolve**: Use versioning and JSONB for flexibility
- **Preferences must be fast**: Bitmap encoding or caching required
- **NoSQL for notification storage**: Cassandra/DynamoDB handle scale better than RDBMS

## What's Next

We have a data model. Now we need to fill it with data.

How do you accept 1 million notifications per second without drowning? How do you validate, deduplicate, and route them efficiently?

In [Chapter 4: The Write Path](./chapter-04-write-path.md), we'll design the ingestion layer—the front door to our system where chaos meets order.

---

*"Data models are beliefs about the structure of reality, encoded in bytes."*
