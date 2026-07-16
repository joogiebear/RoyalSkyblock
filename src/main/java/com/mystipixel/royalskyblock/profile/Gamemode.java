package com.mystipixel.royalskyblock.profile;

import java.util.Locale;

/**
 * A profile's game mode. Each maps to a {@code gamemodes/<key>.yml} ruleset that decides which
 * features (auction house, bazaar, trading, ...) are allowed. The set is fixed in code, but their
 * rules are fully config-driven.
 */
public enum Gamemode {

    /** Standard Skyblock — everything enabled. */
    SOLO,

    /** Shared island + economy with invited members. */
    COOP,

    /** Self-sufficient — no trading with other players (AH, bazaar, etc. blocked). */
    IRONMAN;

    /** The {@code gamemodes/<key>.yml} file name / config key for this mode. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Gamemode fromString(String raw, Gamemode fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
