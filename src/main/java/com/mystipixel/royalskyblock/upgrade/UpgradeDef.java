package com.mystipixel.royalskyblock.upgrade;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A single upgrade track (e.g. island size) — its display, effect, and ordered tiers. Loaded from
 * {@code upgrades.yml}.
 */
public final class UpgradeDef {

    private final String key;
    private final String displayName;
    private final String icon;
    private final String description;
    private final UpgradeEffect effect;
    private final List<UpgradeTier> tiers; // index 0 = tier 1

    public UpgradeDef(String key, String displayName, String icon, String description,
                      UpgradeEffect effect, List<UpgradeTier> tiers) {
        this.key = key;
        this.displayName = displayName;
        this.icon = icon;
        this.description = description;
        this.effect = effect;
        this.tiers = tiers;
    }

    public String key() {
        return key;
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

    public UpgradeEffect effect() {
        return effect;
    }

    public int maxTier() {
        return tiers.size();
    }

    /** The tier definition for a 1-based tier number, or {@code null} if out of range. */
    public @Nullable UpgradeTier tier(int tierNumber) {
        if (tierNumber < 1 || tierNumber > tiers.size()) {
            return null;
        }
        return tiers.get(tierNumber - 1);
    }

    /** The next tier after {@code currentTier} (0 = none purchased), or {@code null} if maxed. */
    public @Nullable UpgradeTier nextTier(int currentTier) {
        return tier(currentTier + 1);
    }

    /** The effect value at {@code currentTier}, or 0 if nothing purchased yet. */
    public double valueAt(int currentTier) {
        UpgradeTier t = tier(currentTier);
        return t != null ? t.value() : 0;
    }
}
