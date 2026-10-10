package com.mystipixel.royalskyblock.upgrade;

import java.util.UUID;

/**
 * An upgrade in progress: the base cost was paid and it completes at {@link #completeAt} (epoch millis)
 * unless the owner pays the skip cost. Persisted so timers survive restarts.
 */
public record PendingUpgrade(UUID islandId, String upgradeKey, int targetTier, long completeAt) {

    public boolean isDone(long now) {
        return now >= completeAt;
    }

    public long secondsLeft(long now) {
        return Math.max(0, (completeAt - now) / 1000L);
    }
}
