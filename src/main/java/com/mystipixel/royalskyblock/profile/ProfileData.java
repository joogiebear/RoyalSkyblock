package com.mystipixel.royalskyblock.profile;

import org.jetbrains.annotations.Nullable;

/**
 * The per-profile player state swapped on profile switch: serialized inventory and ender chest (Paper's
 * item byte format) plus vanilla stats. Eco progression is handled by
 * {@link com.mystipixel.royalskyblock.hooks.EcoProfileBridge}. Byte fields are {@code null} for a new
 * profile, so the player starts fresh.
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
