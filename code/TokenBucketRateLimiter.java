package com.notificationengine.ratelimit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Token Bucket Rate Limiter
 *
 * Implements the token bucket algorithm for rate limiting.
 * Tokens are refilled at a constant rate, allowing bursts up to capacity.
 *
 * Used in Chapter 4: The Write Path
 *
 * Characteristics:
 * - Thread-safe using atomic operations
 * - Allows bursts (up to capacity)
 * - Smooth long-term rate
 * - Lock-free implementation
 */
public class TokenBucketRateLimiter {

    private final long capacity;           // Maximum tokens
    private final long refillRate;         // Tokens added per second
    private final AtomicLong tokens;       // Current available tokens
    private final AtomicLong lastRefill;   // Last refill timestamp (nanoseconds)

    /**
     * Creates a new Token Bucket Rate Limiter
     *
     * @param capacity Maximum number of tokens (burst size)
     * @param refillRate Number of tokens added per second
     */
    public TokenBucketRateLimiter(long capacity, long refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = new AtomicLong(capacity);
        this.lastRefill = new AtomicLong(System.nanoTime());
    }

    /**
     * Attempts to consume the specified number of tokens
     *
     * @param tokensNeeded Number of tokens to consume
     * @return true if tokens were consumed, false if rate limited
     */
    public boolean tryConsume(long tokensNeeded) {
        refill();

        long current = tokens.get();
        while (current >= tokensNeeded) {
            if (tokens.compareAndSet(current, current - tokensNeeded)) {
                return true; // Success
            }
            current = tokens.get(); // Retry due to concurrent modification
        }

        return false; // Rate limited
    }

    /**
     * Refills tokens based on elapsed time since last refill
     */
    private void refill() {
        long now = System.nanoTime();
        long last = lastRefill.get();
        long elapsed = now - last;

        // Calculate tokens to add based on elapsed time
        long tokensToAdd = (elapsed * refillRate) / 1_000_000_000L;

        if (tokensToAdd > 0) {
            long current = tokens.get();
            long newTokens = Math.min(capacity, current + tokensToAdd);

            // Update tokens and timestamp atomically
            if (tokens.compareAndSet(current, newTokens)) {
                lastRefill.set(now);
            }
        }
    }

    /**
     * Gets the current number of available tokens
     *
     * @return Current token count
     */
    public long getAvailableTokens() {
        refill();
        return tokens.get();
    }

    /**
     * Example usage
     */
    public static void main(String[] args) throws InterruptedException {
        // Create limiter: 100 tokens max, refill 10 per second
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 10);

        // Simulate burst traffic
        System.out.println("Simulating burst traffic:");
        for (int i = 0; i < 150; i++) {
            boolean allowed = limiter.tryConsume(1);
            System.out.printf("Request %d: %s (tokens: %d)%n",
                i + 1,
                allowed ? "ALLOWED" : "RATE_LIMITED",
                limiter.getAvailableTokens());
        }

        // Wait for refill
        System.out.println("\nWaiting 5 seconds for refill...");
        Thread.sleep(5000);

        // Try again
        System.out.printf("After refill - tokens: %d%n", limiter.getAvailableTokens());
        System.out.println("Next request: " +
            (limiter.tryConsume(1) ? "ALLOWED" : "RATE_LIMITED"));
    }
}
