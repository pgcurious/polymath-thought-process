# Chapter 8: The Edge Cases That Became the System

> "The difference between a prototype and a production system is handling the cases you didn't think of."

## The Law of Edge Cases

**Law**: Every edge case you ignore will eventually become your most common production issue.

**Corollary**: What seems like an "edge case" at 1,000 users becomes mainstream at 1 billion users.

This chapter is about the real-world complexity that doesn't appear in system design diagrams.

## Edge Case 1: Time Zones and Scheduling

### The Problem

"Send me notifications between 9 AM - 9 PM in my local timezone."

Sounds simple. Now consider:

**User in New York (EST)**:
- 9 AM EST = 2 PM UTC
- "Send at 9 AM" means different UTC times depending on DST

**User traveling from New York to Tokyo**:
- Phone reports timezone: Asia/Tokyo
- User preference stored as: America/New_York
- Which one is correct?

**Scheduled notification**:
- Created at 10 AM UTC to be sent at 9 AM user-local-time
- User changes timezone before 9 AM
- When do you send?

### The Solution

```java
public class TimezoneAwareScheduler {

    public Instant calculateDeliveryTime(
            NotificationRequest request,
            UserPreferences preferences) {

        // Get user's current timezone (from device or profile)
        ZoneId userZone = getUserTimezone(request.getUserId());

        // Get user's quiet hours (in their local time)
        LocalTime quietStart = preferences.getQuietHoursStart(); // e.g., 22:00
        LocalTime quietEnd = preferences.getQuietHoursEnd();     // e.g., 08:00

        // Current time in user's timezone
        ZonedDateTime userNow = Instant.now().atZone(userZone);
        LocalTime userLocalTime = userNow.toLocalTime();

        // Check if we're in quiet hours
        if (isInQuietHours(userLocalTime, quietStart, quietEnd)) {
            // Schedule for when quiet hours end
            ZonedDateTime deliveryTime = userNow.with(quietEnd);

            // If quiet end is before now (e.g., it's 11 PM and quiet ends at 8 AM),
            // schedule for tomorrow
            if (deliveryTime.isBefore(userNow)) {
                deliveryTime = deliveryTime.plusDays(1);
            }

            return deliveryTime.toInstant();
        }

        // Send immediately
        return Instant.now();
    }

    private boolean isInQuietHours(LocalTime current, LocalTime start, LocalTime end) {
        if (start.isBefore(end)) {
            // Normal case: quiet hours within same day (e.g., 2 PM - 6 PM)
            return current.isAfter(start) && current.isBefore(end);
        } else {
            // Wrap-around case: quiet hours cross midnight (e.g., 10 PM - 8 AM)
            return current.isAfter(start) || current.isBefore(end);
        }
    }

    private ZoneId getUserTimezone(String userId) {
        // Priority:
        // 1. Recent device-reported timezone (most accurate)
        // 2. User profile timezone setting
        // 3. Inferred from recent activity patterns
        // 4. Fallback: UTC

        return deviceService.getLastReportedTimezone(userId)
            .or(() -> userService.getProfileTimezone(userId))
            .or(() -> inferTimezoneFromActivity(userId))
            .orElse(ZoneId.of("UTC"));
    }
}
```

### DST Handling

```java
public class DSTAwareScheduler {

    public Instant scheduleDaily(LocalTime targetTime, ZoneId timezone) {
        ZonedDateTime now = Instant.now().atZone(timezone);
        ZonedDateTime scheduled = now.with(targetTime);

        // If target time has passed today, schedule for tomorrow
        if (scheduled.isBefore(now)) {
            scheduled = scheduled.plusDays(1);
        }

        // Check for DST transitions
        // When DST ends, 2 AM happens twice
        // When DST starts, 2 AM doesn't exist

        if (timezone.getRules().isDaylightSavings(scheduled.toInstant())) {
            // Handle ambiguous or non-existent times
            // Use ZonedDateTime to let Java handle the complexity
        }

        return scheduled.toInstant();
    }
}
```

**Key insights:**
- Always store absolute time (Instant/UTC) in the database
- Convert to local time only for display or scheduling decisions
- Use ZoneId, not fixed offsets (handles DST automatically)
- Consider device timezone vs. profile timezone

## Edge Case 2: Internationalization (i18n)

### The Problem

English: "Alice and 3 others liked your photo"

**Challenge 1: Pluralization**

- English: "1 person" vs. "2 people"
- Polish: Different forms for 1, 2-4, 5+ items
- Arabic: Different forms for 0, 1, 2, 3-10, 11-99, 100+

**Challenge 2: Word Order**

- English: "Alice liked your photo"
- Japanese: "Aliceさんがあなたの写真にいいねしました" (subject-object-verb)
- Arabic: Right-to-left rendering

**Challenge 3: Text Expansion**

- English: "Message" (7 chars)
- German: "Nachricht" (9 chars)
- Finnish: "Viesti" (6 chars)

Push notification limits (iOS/Android):
- Title: ~65 chars
- Body: ~240 chars

**A German translation might not fit!**

### The Solution

**Use ICU MessageFormat:**

```java
public class I18nNotificationRenderer {
    private final MessageFormat formatter;

    public String render(String template, Map<String, Object> params, Locale locale) {
        MessageFormat fmt = new MessageFormat(template, locale);
        return fmt.format(params);
    }
}
```

**Template (English):**

```
{count, plural,
  =0 {No one liked your photo}
  =1 {{name} liked your photo}
  other {{name} and {count} others liked your photo}
}
```

**Template (Polish):**

```
{count, plural,
  =0 {Nikt nie polubił Twojego zdjęcia}
  =1 {{name} polubił Twoje zdjęcie}
  few {{name} i {count} inne osoby polubiły Twoje zdjęcie}
  many {{name} i {count} innych osób polubiło Twoje zdjęcie}
  other {{name} i {count} osoby polubiły Twoje zdjęcie}
}
```

**Template (Arabic with RTL):**

```
{count, plural,
  =0 {لا أحد أعجب بصورتك}
  =1 {{name} أعجب بصورتك}
  other {{name} و {count} آخرون أعجبوا بصورتك}
}
```

**Handling overflow:**

```java
public String truncateForPlatform(String text, Platform platform) {
    int maxLength = switch (platform) {
        case IOS -> 178;      // APNs recommended limit
        case ANDROID -> 240;   // FCM limit
        case WEB -> 160;       // Chrome notification limit
    };

    if (text.length() <= maxLength) {
        return text;
    }

    // Truncate at word boundary
    int lastSpace = text.lastIndexOf(' ', maxLength - 3);
    return text.substring(0, lastSpace) + "...";
}
```

## Edge Case 3: Notification Fatigue

### The Problem

User receives 100 notifications per day. After a week, they:
- Disable all notifications
- Uninstall the app
- Never engage

**You've lost the user by spamming them.**

### The Solution: Adaptive Rate Limiting

```java
public class FatigueManager {

    public boolean shouldSend(String userId, NotificationRequest notification) {
        UserEngagement engagement = engagementService.get(userId);

        // Calculate user's notification tolerance
        int dailyBudget = calculateDailyBudget(engagement);

        // Count notifications sent today
        int sentToday = notificationRepository.countSentToday(userId);

        if (sentToday >= dailyBudget) {
            // User has hit their limit
            if (notification.getPriority() == Priority.CRITICAL) {
                // Critical notifications bypass limit
                return true;
            }

            // Defer low-priority notifications
            return false;
        }

        return true;
    }

    private int calculateDailyBudget(UserEngagement engagement) {
        // High engagement = higher tolerance
        double clickRate = engagement.getClickRate();
        double dismissRate = engagement.getDismissRate();

        if (clickRate > 0.5) {
            return 50;  // User loves notifications
        } else if (clickRate > 0.2) {
            return 20;  // Normal user
        } else if (clickRate > 0.05) {
            return 10;  // Low engagement
        } else {
            return 5;   // Very low engagement, risk of disabling
        }
    }
}
```

**Progressive reduction:**

```java
public class ProgressiveFatigueManager {

    public boolean shouldSend(String userId, NotificationRequest notification) {
        UserStats stats = statsService.get(userId);

        // If user hasn't engaged in 7 days, reduce frequency
        if (stats.getDaysSinceLastClick() > 7) {
            // Only send high-priority
            return notification.getPriority().getImpactScore() >= 70;
        }

        // If user hasn't engaged in 30 days, only send critical
        if (stats.getDaysSinceLastClick() > 30) {
            return notification.getPriority() == Priority.CRITICAL;
        }

        return true;
    }
}
```

## Edge Case 4: The Notification Storm

### The Problem

A bug in your service causes it to send the same notification 1 million times to the same user.

**Or**: A celebrity with 10 million followers posts, generating 10 million "X posted" notifications simultaneously.

### The Solution: Deduplication and Windowing

```java
public class StormPrevention {

    // Detect duplicate notifications within a time window
    public boolean isDuplicate(NotificationRequest request) {
        String dedupeKey = generateDedupeKey(request);

        // Check if we've seen this exact notification in the last 5 minutes
        boolean exists = dedupeCache.containsKey(dedupeKey);

        if (!exists) {
            // Store with 5-minute TTL
            dedupeCache.put(dedupeKey, true, Duration.ofMinutes(5));
        }

        return exists;
    }

    private String generateDedupeKey(NotificationRequest request) {
        // Key includes: user + event type + content hash
        return String.format("%s:%s:%s",
            request.getUserId(),
            request.getEventType(),
            hashPayload(request.getPayload())
        );
    }

    // Detect notification storms (same event type, many recipients)
    public boolean isStorm(EventType eventType) {
        // Count notifications of this type in last minute
        long recentCount = metrics.getCount(
            "notifications.created",
            Duration.ofMinutes(1),
            Tags.of("event_type", eventType.name())
        );

        // If more than 100K of same type in 1 minute, likely a storm
        if (recentCount > 100_000) {
            logger.warn("Potential storm detected for event type: {}", eventType);
            // Alert operations team
            alertService.sendAlert("Notification storm: " + eventType);
            return true;
        }

        return false;
    }
}
```

**Rate limiting per user:**

```java
public class UserRateLimiter {

    public boolean allowNotification(String userId) {
        String key = "notif_rate:" + userId;

        // Allow max 100 notifications per user per hour
        Long count = redis.execute(
            "INCR", key
        );

        if (count == 1) {
            redis.execute("EXPIRE", key, "3600");
        }

        return count <= 100;
    }
}
```

## Edge Case 5: Regulatory Compliance

### GDPR Quiet Hours

**EU regulation**: Cannot send marketing notifications between 8 PM and 8 AM.

```java
public class GDPRComplianceService {

    public boolean canSendMarketing(NotificationRequest request, UserLocation location) {
        // Only applies to marketing, not transactional
        if (!request.isMarketing()) {
            return true;
        }

        // Only applies to EU users
        if (!location.isInEU()) {
            return true;
        }

        // Check local time
        ZonedDateTime localTime = Instant.now().atZone(location.getTimezone());
        int hour = localTime.getHour();

        // Quiet hours: 8 PM (20:00) to 8 AM (08:00)
        if (hour >= 20 || hour < 8) {
            logger.info("Deferring marketing notification due to GDPR quiet hours");
            return false;
        }

        return true;
    }
}
```

### Right to Erasure

**User requests deletion. What happens to their notifications?**

```java
@Service
public class DataDeletionService {

    @Transactional
    public void deleteUserData(String userId) {
        // 1. Stop processing pending notifications
        notificationQueue.cancelAllForUser(userId);

        // 2. Delete user preferences
        preferenceRepository.deleteByUserId(userId);

        // 3. Delete device tokens
        deviceRepository.deleteByUserId(userId);

        // 4. Anonymize notification history (for analytics)
        notificationRepository.anonymizeUser(userId);

        // 5. Remove from caches
        preferenceCache.invalidate(userId);
        engagementCache.invalidate(userId);

        logger.info("User data deleted: {}", userId);
    }
}
```

## Edge Case 6: The Million Unread Notifications

### The Problem

User hasn't opened the app in 6 months. They have 1 million unread notifications.

**What happens when they open the app?**

Option 1: Load all 1M → App crashes
Option 2: Load recent 100 → User misses critical notifications
Option 3: ???

### The Solution

```java
public class UnreadNotificationStrategy {

    public List<Notification> getUnreadNotifications(String userId) {
        long unreadCount = notificationRepository.countUnread(userId);

        if (unreadCount < 100) {
            // Normal case: Return all
            return notificationRepository.findUnread(userId);
        }

        // High unread count: Aggressive filtering
        List<Notification> result = new ArrayList<>();

        // 1. Always include critical notifications (last 30 days)
        result.addAll(
            notificationRepository.findUnread(
                userId,
                Priority.CRITICAL,
                Instant.now().minus(30, ChronoUnit.DAYS)
            )
        );

        // 2. Include high-priority (last 7 days)
        result.addAll(
            notificationRepository.findUnread(
                userId,
                Priority.HIGH,
                Instant.now().minus(7, ChronoUnit.DAYS)
            )
        );

        // 3. Sample recent medium/low priority (last 24 hours)
        result.addAll(
            notificationRepository.findUnread(
                userId,
                Set.of(Priority.MEDIUM, Priority.LOW),
                Instant.now().minus(1, ChronoUnit.DAYS)
            ).stream().limit(20).toList()
        );

        // 4. Mark old notifications as "archived" (not deleted)
        notificationRepository.archiveOldUnread(
            userId,
            Instant.now().minus(30, ChronoUnit.DAYS)
        );

        return result;
    }
}
```

## If I Built This Again

**What I'd do differently**: Plan for i18n from day 1. Retrofitting internationalization is painful.

**What I learned**: Edge cases aren't edge cases at scale. The 0.1% case happens 1 million times per day at 1B users.

**What I'd do the same**: Aggressive storm prevention. Better to miss 1 legitimate notification than spam users with 1000 duplicates.

## Chapter Summary

- **Time zones**: Store UTC, convert for scheduling, handle DST
- **i18n**: Use ICU MessageFormat, handle pluralization, truncate for platforms
- **Fatigue**: Adaptive rate limiting based on engagement
- **Storms**: Deduplication, per-user rate limits, storm detection
- **Compliance**: GDPR quiet hours, right to erasure
- **Million unread**: Priority-based filtering, archival strategy

## What's Next

We've handled the edge cases. But requirements change. New channels emerge. Regulations evolve. How do you build a system that adapts?

In [Chapter 9: Evolution](./chapter-09-evolution.md), we'll design for the unknown future.

---

*"Edge cases are just use cases you haven't scaled to yet."*
