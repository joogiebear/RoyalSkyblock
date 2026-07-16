package com.mystipixel.royalskyblock.upgrade;

import java.util.Locale;

/**
 * What an upgrade changes. The tier {@code value} is interpreted per effect:
 * <ul>
 *   <li>{@link #RADIUS} — the island's protection radius / world-border half-size (absolute).</li>
 *   <li>{@link #GUEST_SLOTS} — extra concurrent visitors allowed (added to the base guest limit).</li>
 *   <li>{@link #COOP_SLOTS} — extra coop members allowed (added to the base member cap).</li>
 * </ul>
 */
public enum UpgradeEffect {

    RADIUS,
    GUEST_SLOTS,
    COOP_SLOTS;

    public static UpgradeEffect fromString(String raw, UpgradeEffect fallback) {
        if (raw == null) {
            return fallback;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "radius", "size" -> {
                return RADIUS;
            }
            case "guest_slots", "guest-slots", "guest_limit", "guest-limit" -> {
                return GUEST_SLOTS;
            }
            case "coop_slots", "coop-slots", "coop_members", "coop-members" -> {
                return COOP_SLOTS;
            }
            default -> {
                return fallback;
            }
        }
    }
}
