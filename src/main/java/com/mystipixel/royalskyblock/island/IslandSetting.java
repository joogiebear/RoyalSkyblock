package com.mystipixel.royalskyblock.island;

import java.util.Locale;

/**
 * A per-island toggle; new ones appear in the settings menu automatically. Each has a stable,
 * persisted {@link #key()} and a default; its icon, name and description are in gui/island/settings.yml.
 */
public enum IslandSetting {

    /** Whether non-members may visit this island ({@code /is visit}). Off = private/invite-only. */
    VISITORS_ALLOWED("visitors-allowed", true),

    /** Whether this island appears in the public Visit browser. */
    LISTED("listed", true),

    /** Whether RoyalSkyblock spawns night mobs on this island (see island-mobs in config.yml). */
    MOB_SPAWNS("mob-spawns", true);

    private final String key;
    private final boolean defaultEnabled;

    IslandSetting(String key, boolean defaultEnabled) {
        this.key = key;
        this.defaultEnabled = defaultEnabled;
    }

    public String key() {
        return key;
    }

    public boolean defaultEnabled() {
        return defaultEnabled;
    }

    public static IslandSetting byKey(String key) {
        for (IslandSetting s : values()) {
            if (s.key.equalsIgnoreCase(key)) {
                return s;
            }
        }
        return null;
    }

    public String niceName() {
        return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
