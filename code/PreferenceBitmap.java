package com.notificationengine.preferences;

import java.util.Arrays;

/**
 * Compact Preference Storage using Bitmaps
 *
 * Stores user notification preferences as a compact bitmap.
 * 50 event types × 4 channels = 200 bits = 25 bytes
 *
 * Used in Chapter 3: The Data Model and Chapter 5: The Routing Engine
 *
 * Benefits:
 * - Extremely compact (25 bytes vs. hundreds of bytes for JSON)
 * - Fast lookups (O(1) bit check)
 * - Cache-friendly (small memory footprint)
 * - Can cache millions of users in-memory
 *
 * Memory usage: 10 million users × 25 bytes = 250 MB
 */
public class PreferenceBitmap {

    // 50 event types × 4 channels = 200 bits = 25 bytes
    private static final int BIT_COUNT = 200;
    private static final int BYTE_COUNT = (BIT_COUNT + 7) / 8; // Ceiling division

    private final byte[] bits;

    /**
     * Event types (examples - in production, use an enum)
     */
    public enum EventType {
        MESSAGE(0),
        COMMENT(1),
        LIKE(2),
        TAG(3),
        FOLLOW(4),
        MENTION(5),
        // ... 44 more event types
        MARKETING(49);

        private final int ordinal;

        EventType(int ordinal) {
            this.ordinal = ordinal;
        }

        public int getOrdinal() {
            return ordinal;
        }
    }

    /**
     * Delivery channels
     */
    public enum Channel {
        PUSH(0),
        EMAIL(1),
        SMS(2),
        IN_APP(3);

        private final int ordinal;

        Channel(int ordinal) {
            this.ordinal = ordinal;
        }

        public int getOrdinal() {
            return ordinal;
        }
    }

    /**
     * Creates a new preference bitmap with all preferences disabled
     */
    public PreferenceBitmap() {
        this.bits = new byte[BYTE_COUNT];
    }

    /**
     * Creates a preference bitmap from existing byte array
     */
    public PreferenceBitmap(byte[] bits) {
        if (bits.length != BYTE_COUNT) {
            throw new IllegalArgumentException(
                "Invalid bitmap size: expected " + BYTE_COUNT + ", got " + bits.length
            );
        }
        this.bits = Arrays.copyOf(bits, BYTE_COUNT);
    }

    /**
     * Checks if a specific event type + channel is enabled
     *
     * @param eventType The event type
     * @param channel The delivery channel
     * @return true if enabled, false otherwise
     */
    public boolean isEnabled(EventType eventType, Channel channel) {
        int bitIndex = calculateBitIndex(eventType, channel);
        return getBit(bitIndex);
    }

    /**
     * Sets the preference for a specific event type + channel
     *
     * @param eventType The event type
     * @param channel The delivery channel
     * @param enabled true to enable, false to disable
     */
    public void setEnabled(EventType eventType, Channel channel, boolean enabled) {
        int bitIndex = calculateBitIndex(eventType, channel);
        setBit(bitIndex, enabled);
    }

    /**
     * Calculates the bit index for an event type + channel combination
     */
    private int calculateBitIndex(EventType eventType, Channel channel) {
        // Layout: event_type * 4 + channel
        // Example: MESSAGE (0) + EMAIL (1) = bit 1
        //          COMMENT (1) + PUSH (0) = bit 4
        return eventType.getOrdinal() * 4 + channel.getOrdinal();
    }

    /**
     * Gets the value of a specific bit
     */
    private boolean getBit(int bitIndex) {
        int byteIndex = bitIndex / 8;
        int bitOffset = bitIndex % 8;

        return (bits[byteIndex] & (1 << bitOffset)) != 0;
    }

    /**
     * Sets the value of a specific bit
     */
    private void setBit(int bitIndex, boolean value) {
        int byteIndex = bitIndex / 8;
        int bitOffset = bitIndex % 8;

        if (value) {
            // Set bit to 1
            bits[byteIndex] |= (1 << bitOffset);
        } else {
            // Set bit to 0
            bits[byteIndex] &= ~(1 << bitOffset);
        }
    }

    /**
     * Enables all preferences for an event type across all channels
     */
    public void enableAllChannels(EventType eventType) {
        for (Channel channel : Channel.values()) {
            setEnabled(eventType, channel, true);
        }
    }

    /**
     * Disables all preferences for an event type across all channels
     */
    public void disableAllChannels(EventType eventType) {
        for (Channel channel : Channel.values()) {
            setEnabled(eventType, channel, false);
        }
    }

    /**
     * Gets the raw byte array (for serialization/storage)
     */
    public byte[] toBytes() {
        return Arrays.copyOf(bits, bits.length);
    }

    /**
     * Gets the size in bytes
     */
    public int getSizeInBytes() {
        return bits.length;
    }

    /**
     * Creates a default preference bitmap (reasonable defaults)
     */
    public static PreferenceBitmap createDefault() {
        PreferenceBitmap bitmap = new PreferenceBitmap();

        // Enable high-value notifications by default
        bitmap.enableAllChannels(EventType.MESSAGE);
        bitmap.enableAllChannels(EventType.COMMENT);
        bitmap.enableAllChannels(EventType.MENTION);

        // Enable push/in-app for social notifications
        bitmap.setEnabled(EventType.TAG, Channel.PUSH, true);
        bitmap.setEnabled(EventType.TAG, Channel.IN_APP, true);
        bitmap.setEnabled(EventType.FOLLOW, Channel.PUSH, true);
        bitmap.setEnabled(EventType.FOLLOW, Channel.IN_APP, true);

        // Disable marketing by default
        bitmap.disableAllChannels(EventType.MARKETING);

        return bitmap;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("PreferenceBitmap {\n");

        for (EventType eventType : new EventType[]{EventType.MESSAGE, EventType.COMMENT,
                EventType.LIKE, EventType.TAG, EventType.FOLLOW, EventType.MENTION, EventType.MARKETING}) {
            sb.append("  ").append(eventType.name()).append(": ");

            for (Channel channel : Channel.values()) {
                if (isEnabled(eventType, channel)) {
                    sb.append(channel.name()).append(" ");
                }
            }
            sb.append("\n");
        }

        sb.append("  Size: ").append(getSizeInBytes()).append(" bytes\n");
        sb.append("}");

        return sb.toString();
    }

    /**
     * Example usage
     */
    public static void main(String[] args) {
        // Create default preferences
        PreferenceBitmap prefs = PreferenceBitmap.createDefault();

        System.out.println("Default Preferences:");
        System.out.println(prefs);
        System.out.println();

        // Check specific preferences
        System.out.println("User wants MESSAGE via PUSH? " +
            prefs.isEnabled(EventType.MESSAGE, Channel.PUSH));

        System.out.println("User wants MARKETING via EMAIL? " +
            prefs.isEnabled(EventType.MARKETING, Channel.EMAIL));

        // Modify preferences
        System.out.println("\nEnabling LIKE notifications for PUSH...");
        prefs.setEnabled(EventType.LIKE, Channel.PUSH, true);

        System.out.println("User wants LIKE via PUSH? " +
            prefs.isEnabled(EventType.LIKE, Channel.PUSH));

        // Serialize to bytes
        byte[] serialized = prefs.toBytes();
        System.out.println("\nSerialized size: " + serialized.length + " bytes");

        // Deserialize from bytes
        PreferenceBitmap restored = new PreferenceBitmap(serialized);
        System.out.println("\nRestored preferences match original? " +
            prefs.toString().equals(restored.toString()));

        // Memory efficiency calculation
        System.out.println("\n--- Memory Efficiency ---");
        int usersToCache = 10_000_000;
        long totalMemoryBytes = (long) usersToCache * prefs.getSizeInBytes();
        long totalMemoryMB = totalMemoryBytes / (1024 * 1024);

        System.out.printf("Caching %,d users: %,d MB%n", usersToCache, totalMemoryMB);
        System.out.println("(vs. ~5 GB for JSON-based storage)");
    }
}
