package com.mystipixel.royalskyblock.island;

import java.util.Locale;

/**
 * A per-island toggle. New privacy/behaviour flags are added here and they automatically appear in
 * the settings menu. Each has a stable {@link #key()} (persisted), a default, and display metadata.
 */
public enum IslandSetting {

    /** Whether non-members may visit this island ({@code /is visit}). Off = private/invite-only. */
    VISITORS_ALLOWED("visitors-allowed", true, "&bVisitors", "oak_door",
            "Allow players who aren't members to visit your island."),

    /** Whether this island appears in the public Visit browser. */
    LISTED("listed", true, "&aVisit Listing", "book",
            "Show this island in the public /visit browser.");

    private final String key;
    private final boolean defaultEnabled;
    private final String displayName;
    private final String icon;
    private final String description;

    IslandSetting(String key, boolean defaultEnabled, String displayName, String icon, String description) {
        this.key = key;
        this.defaultEnabled = defaultEnabled;
        this.displayName = displayName;
        this.icon = icon;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public boolean defaultEnabled() {
        return defaultEnabled;
    }

    public String displayName() {
        return displayName;
    }

    public String icon() {
        return icon;
    }

    public String description() {
        return description;
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
