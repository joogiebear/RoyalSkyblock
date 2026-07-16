package com.mystipixel.royalskyblock.profile;

import org.jetbrains.annotations.Nullable;

/**
 * The per-profile player state that swaps in and out on profile switch: serialized inventory and
 * ender chest (Paper's item byte format), plus vanilla stats. Eco progression is handled separately
 * by {@link com.mystipixel.royalskyblock.hooks.EcoProfileBridge} (stored in eco's own data by profile
 * UUID), so it isn't duplicated here.
 *
 * <p>Byte fields are {@code null} for a brand-new profile (nothing saved yet) → the player starts
 * fresh.
 */
public record ProfileData(@Nullable byte[] inventory,
                          @Nullable byte[] enderChest,
                          int expLevel,
                          float expProgress,
                          double health,
                          int food,
                          float saturation) {

    /** A fresh, empty state for a new profile. */
    public static ProfileData fresh() {
        return new ProfileData(null, null, 0, 0f, 20.0, 20, 5f);
    }
}
