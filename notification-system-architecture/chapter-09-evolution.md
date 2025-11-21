# Chapter 9: Evolution: Designing for the Unknown

> "The only constant is change. Design for it."

## The Inevitability of Change

Here's what will happen after you launch your notification system:

**Month 1**: "Can we support webhooks?"
**Month 3**: "We need to integrate with Slack"
**Month 6**: "New iOS features require APNs changes"
**Year 1**: "We're acquired by MegaCorp, need to integrate with their identity system"
**Year 2**: "New regulation requires notification approval workflows"
**Year 3**: "Can we support AR/VR notifications?"

**You cannot predict these changes. But you can prepare for them.**

This chapter is about building flexibility without building complexity.

## The Plugin Architecture

### The Problem

You hard-coded delivery channels:

```java
public void deliver(Notification notification) {
    if (notification.getChannel() == Channel.PUSH) {
        apnsClient.send(notification);
    } else if (notification.getChannel() == Channel.EMAIL) {
        emailClient.send(notification);
    } else if (notification.getChannel() == Channel.SMS) {
        smsClient.send(notification);
    }
    // ... 20 more else-if blocks
}
```

**New requirement**: Add Slack support.

**Current approach**: Modify this code, add new enum value, deploy everywhere.

**Problem**: Tight coupling, risky deployments, slow iteration.

### The Solution: Strategy Pattern + Registry

```java
public interface DeliveryChannel {
    String getName();
    CompletableFuture<DeliveryResult> deliver(RenderedNotification notification);
    boolean isAvailable();
    int getRateLimit();
}
```

```java
@Component
public class APNsDeliveryChannel implements DeliveryChannel {

    @Override
    public String getName() {
        return "apns";
    }

    @Override
    public CompletableFuture<DeliveryResult> deliver(RenderedNotification notification) {
        // APNs-specific delivery logic
        return apnsClient.sendAsync(notification);
    }

    @Override
    public boolean isAvailable() {
        return circuitBreaker.isClosed();
    }

    @Override
    public int getRateLimit() {
        return 10_000; // per second
    }
}
```

```java
@Component
public class SlackDeliveryChannel implements DeliveryChannel {

    @Override
    public String getName() {
        return "slack";
    }

    @Override
    public CompletableFuture<DeliveryResult> deliver(RenderedNotification notification) {
        // Slack webhook delivery
        return slackWebhookClient.postMessage(
            notification.getDestination(), // Webhook URL
            notification.getPayload()
        );
    }

    @Override
    public boolean isAvailable() {
        return true; // Slack is always available
    }

    @Override
    public int getRateLimit() {
        return 1; // Slack has strict rate limits
    }
}
```

```java
@Service
public class DeliveryChannelRegistry {
    private final Map<String, DeliveryChannel> channels = new ConcurrentHashMap<>();

    // Auto-discover channels via Spring
    @Autowired
    public DeliveryChannelRegistry(List<DeliveryChannel> channelList) {
        for (DeliveryChannel channel : channelList) {
            register(channel);
        }
    }

    public void register(DeliveryChannel channel) {
        channels.put(channel.getName(), channel);
        logger.info("Registered delivery channel: {}", channel.getName());
    }

    public DeliveryChannel get(String name) {
        return channels.get(name);
    }

    public Collection<DeliveryChannel> getAll() {
        return channels.values();
    }
}
```

```java
public class PluggableDeliveryService {

    @Autowired
    private DeliveryChannelRegistry registry;

    public CompletableFuture<DeliveryResult> deliver(RenderedNotification notification) {
        String channelName = notification.getChannel();
        DeliveryChannel channel = registry.get(channelName);

        if (channel == null) {
            return CompletableFuture.failedFuture(
                new UnsupportedChannelException(channelName)
            );
        }

        if (!channel.isAvailable()) {
            return CompletableFuture.failedFuture(
                new ChannelUnavailableException(channelName)
            );
        }

        return channel.deliver(notification);
    }
}
```

**Benefits:**
- Add new channels without modifying core code
- Each channel is isolated (test independently)
- Channels can be enabled/disabled dynamically
- Easy to add channel-specific logic

**To add Slack**: Just create `SlackDeliveryChannel`, Spring auto-wires it. Done.

## Feature Flags: Runtime Configuration

### The Problem

You want to test a new routing algorithm. But:
- Can't deploy to all users (risky)
- Can't maintain two code branches (messy)
- Need to toggle on/off quickly (if it breaks)

### The Solution

```java
@Service
public class FeatureFlagService {
    private final Map<String, FeatureFlag> flags = new ConcurrentHashMap<>();

    public boolean isEnabled(String flagName, String userId) {
        FeatureFlag flag = flags.get(flagName);

        if (flag == null) {
            return false; // Default: disabled
        }

        return flag.isEnabledFor(userId);
    }

    public void reload() {
        // Fetch flags from config service (e.g., LaunchDarkly, ConfigCat)
        List<FeatureFlag> updated = configService.getFeatureFlags();

        for (FeatureFlag flag : updated) {
            flags.put(flag.getName(), flag);
        }
    }
}
```

```java
public class FeatureFlag {
    private final String name;
    private final boolean globallyEnabled;
    private final double rolloutPercentage; // 0.0 to 1.0
    private final Set<String> whitelistedUsers;
    private final Set<String> blacklistedUsers;

    public boolean isEnabledFor(String userId) {
        // Blacklist always wins
        if (blacklistedUsers.contains(userId)) {
            return false;
        }

        // Whitelist bypasses percentage
        if (whitelistedUsers.contains(userId)) {
            return true;
        }

        // Check global flag
        if (!globallyEnabled) {
            return false;
        }

        // Percentage rollout (consistent hashing)
        long hash = Hashing.murmur3_128()
            .hashString(userId + ":" + name, StandardCharsets.UTF_8)
            .asLong();

        double userPercentile = Math.abs(hash % 100) / 100.0;
        return userPercentile < rolloutPercentage;
    }
}
```

**Usage:**

```java
public List<Channel> selectChannels(NotificationContext context) {
    if (featureFlags.isEnabled("multi_channel_delivery", context.getUserId())) {
        // New algorithm: Send to multiple channels
        return multiChannelStrategy.select(context);
    } else {
        // Old algorithm: Single best channel
        return List.of(bestChannelStrategy.select(context));
    }
}
```

**Deployment strategy:**

```
Day 1: Enable for 1% of users (rolloutPercentage = 0.01)
Day 2: Monitor metrics, no issues → 10%
Day 3: Still good → 50%
Day 4: All green → 100%

If issues: Set rolloutPercentage = 0.0 (instant rollback, no deployment)
```

## Versioning: Backward Compatibility

### The Problem

You change the notification payload schema:

**V1:**
```json
{
  "title": "New message",
  "body": "Alice sent you a message"
}
```

**V2** (add image support):
```json
{
  "title": "New message",
  "body": "Alice sent you a message",
  "image_url": "https://..."
}
```

**But**: Old clients don't know about `image_url`. What happens?

### The Solution: Versioned Rendering

```java
public interface NotificationRenderer {
    int getVersion();
    RenderedNotification render(NotificationContext context);
}
```

```java
@Component
public class NotificationRendererV1 implements NotificationRenderer {

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public RenderedNotification render(NotificationContext context) {
        return new RenderedNotification(
            context.getTitle(),
            context.getBody(),
            null, // No image support in V1
            null  // No actions in V1
        );
    }
}
```

```java
@Component
public class NotificationRendererV2 implements NotificationRenderer {

    @Override
    public int getVersion() {
        return 2;
    }

    @Override
    public RenderedNotification render(NotificationContext context) {
        return new RenderedNotification(
            context.getTitle(),
            context.getBody(),
            context.getPayload().get("image_url"), // Image support added
            null  // No actions yet
        );
    }
}
```

```java
@Service
public class VersionedRenderingService {

    @Autowired
    private List<NotificationRenderer> renderers;

    public RenderedNotification render(NotificationContext context, int clientVersion) {
        // Use renderer matching client version, or latest compatible
        NotificationRenderer renderer = renderers.stream()
            .filter(r -> r.getVersion() <= clientVersion)
            .max(Comparator.comparingInt(NotificationRenderer::getVersion))
            .orElse(renderers.get(0)); // Fallback to V1

        return renderer.render(context);
    }
}
```

**Client compatibility:**

```
Client v1.0 (supports payload v1) → Gets NotificationRendererV1
Client v1.5 (supports payload v2) → Gets NotificationRendererV2
Client v2.0 (supports payload v3) → Gets NotificationRendererV3

Old clients continue working, new clients get new features
```

## Database Migrations Without Downtime

### The Problem

You need to add a column to a table with 10 billion rows. In PostgreSQL, `ALTER TABLE ADD COLUMN` might lock the table for hours.

**Downtime is unacceptable.**

### The Solution: Expand-Migrate-Contract

**Phase 1: Expand** (add new column, nullable)

```sql
-- This is fast (no table rewrite if nullable)
ALTER TABLE notifications ADD COLUMN priority_v2 INTEGER DEFAULT NULL;
```

**Phase 2: Dual Write** (write to both old and new)

```java
public void create(Notification notification) {
    notification.setPriority(oldPriorityValue);      // Old column
    notification.setPriorityV2(newPriorityValue);    // New column
    repository.save(notification);
}
```

**Phase 3: Backfill** (migrate existing data, in batches)

```java
@Scheduled(fixedRate = 60_000) // Every minute
public void backfillPriority() {
    List<Notification> batch = repository.findNeedsMigration(10_000);

    for (Notification n : batch) {
        n.setPriorityV2(convertPriority(n.getPriority()));
    }

    repository.saveAll(batch);
}
```

**Phase 4: Read from new** (switch reads)

```java
public Notification find(String id) {
    Notification n = repository.findById(id);

    // Prefer new column, fall back to old
    if (n.getPriorityV2() != null) {
        n.setPriority(n.getPriorityV2());
    }

    return n;
}
```

**Phase 5: Stop writing to old** (once backfill complete)

```java
public void create(Notification notification) {
    notification.setPriorityV2(newPriorityValue);  // Only new column
    repository.save(notification);
}
```

**Phase 6: Contract** (drop old column)

```sql
ALTER TABLE notifications DROP COLUMN priority;
ALTER TABLE notifications RENAME COLUMN priority_v2 TO priority;
```

**Total downtime: 0 seconds.**

## Event-Driven Architecture: Decoupling

### The Problem

Your notification service is tightly coupled to other services:

```java
public void onUserSignup(User user) {
    // Notification service does too much
    notificationService.send(user, "welcome_email");
    analyticsService.track("user_signed_up", user);
    marketingService.addToCampaign(user);
    crmService.createLead(user);
}
```

**Problem**: Every new feature requires modifying notification service.

### The Solution: Event Bus

```java
// Producer: User service
public void createUser(UserRequest request) {
    User user = userRepository.save(request.toUser());

    // Publish event, don't call services directly
    eventBus.publish(new UserCreatedEvent(
        user.getId(),
        user.getEmail(),
        user.getCreatedAt()
    ));
}
```

```java
// Consumer: Notification service
@EventListener
public void onUserCreated(UserCreatedEvent event) {
    notificationService.send(
        event.getUserId(),
        NotificationType.WELCOME_EMAIL
    );
}
```

```java
// Consumer: Analytics service
@EventListener
public void onUserCreated(UserCreatedEvent event) {
    analyticsService.track("user_signup", event);
}
```

**Benefits:**
- Producers don't know about consumers
- Add new consumers without changing producers
- Easy to add/remove features
- Natural async processing

## Graceful Degradation

### The Problem

Your dependency (user service) is down. Do you:
- Reject all notifications? (availability suffers)
- Send all notifications? (might send to invalid users)

### The Solution: Fallback Strategies

```java
public boolean shouldSend(NotificationRequest request) {
    try {
        // Try to check user existence
        boolean userExists = userService.exists(
            request.getUserId(),
            Duration.ofMillis(100) // Fast timeout
        );

        return userExists;

    } catch (TimeoutException e) {
        // User service slow/down, use fallback
        logger.warn("User service timeout, using optimistic fallback");

        // Optimistic: Assume user exists
        metrics.recordFallback("user_check");
        return true;

    } catch (ServiceUnavailableException e) {
        // User service completely down
        logger.error("User service unavailable");

        // Check cache (stale data is better than no data)
        Optional<Boolean> cached = userCache.get(request.getUserId());

        return cached.orElse(true); // Optimistic fallback
    }
}
```

**Degradation levels:**

```java
public enum SystemHealth {
    HEALTHY,        // All systems operational
    DEGRADED,       // Some systems slow/unavailable, use fallbacks
    CRITICAL,       // Core systems failing, minimal functionality
    EMERGENCY       // Survival mode, only critical operations
}

public void processNotification(Notification notification) {
    SystemHealth health = healthService.getCurrentHealth();

    switch (health) {
        case HEALTHY -> processWithAllFeatures(notification);
        case DEGRADED -> {
            // Skip expensive validations
            processWithReducedFeatures(notification);
        }
        case CRITICAL -> {
            // Only high-priority notifications
            if (notification.getPriority().isHigh()) {
                processMinimal(notification);
            }
        }
        case EMERGENCY -> {
            // Only critical, no external dependencies
            if (notification.getPriority() == Priority.CRITICAL) {
                processOffline(notification);
            }
        }
    }
}
```

## If I Built This Again

**What I'd do differently**: Implement feature flags from day 1. We once had to emergency-rollback a deployment that took 6 hours. With feature flags, it would have been 30 seconds.

**What I learned**: Backward compatibility isn't optional at scale. Breaking changes require months of migration planning.

**What I'd do the same**: Event-driven architecture. It's more complex initially, but enables rapid iteration later.

## Chapter Summary

- **Plugin architecture**: Strategy pattern + registry for extensibility
- **Feature flags**: Runtime configuration, gradual rollouts, instant rollback
- **Versioning**: Multiple renderers for backward compatibility
- **Database migrations**: Expand-migrate-contract for zero-downtime changes
- **Event-driven**: Decouple producers and consumers via event bus
- **Graceful degradation**: Fallback strategies when dependencies fail

## What's Next

We've built a technically sophisticated system. But there's a question we haven't asked: **Should we?**

In [Chapter 10: The Human Element](./chapter-10-human-element.md), we'll step back and reflect on the impact of what we've built.

---

*"The best code is code that adapts to change without changing."*
