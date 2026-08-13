package com.mystipixel.royalskyblock.upgrade;

import java.util.Locale;

/**
 * What an upgrade changes. The tier {@code value} is interpreted per effect:
 * <ul>
 *   <li>{@link #RADIUS} — the island's protection radius / world-border half-size (absolute).</li>
 *   <li>{@link #GUEST_SLOTS} — extra concurrent visitors allowed (added to the base guest limit).</li>
 *   <li>{@link #COOP_SLOTS} — extra coop members allowed (added to the base member cap).</li>
 *   <li>{@link #GENERATOR} — which ore-generator tier the island produces (absolute tier number).</li>
 *   <li>{@link #MINIONS} — extra minion slots. Inert here: RoyalSkyblock only stores the tier; an
 *       addon (RoyalMinions) reads {@code island.upgradeTier("minions")} and grants the slots.</li>
 *   <li>{@link #NONE} — changes nothing by itself. For tracks whose whole purpose is the libreforge
 *       {@code effects:} chain on each tier, where {@code value} is meaningless.</li>
 * </ul>
 */
public enum UpgradeEffect {

    RADIUS,
    GUEST_SLOTS,
    COOP_SLOTS,
    GENERATOR,
    MINIONS,
    /**
     * No built-in behaviour — the tier's {@code effects:} chain is the whole upgrade.
     *
     * <p>Exists so such a track does not have to borrow an unrelated effect type. Borrowing
     * {@code minions} looked harmless (nothing reads that effect; RoyalMinions keys off the track id)
     * but the upgrades menu labels a tier by its effect, so a buff track advertised itself as
     * "+0 minion slots".
     */
    NONE;

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
            case "generator", "ore_generator", "ore-generator" -> {
                return GENERATOR;
            }
            case "minions", "minion_slots", "minion-slots" -> {
                return MINIONS;
            }
            case "none", "effects", "buff" -> {
                return NONE;
            }
            default -> {
                return fallback;
            }
        }
    }
}
