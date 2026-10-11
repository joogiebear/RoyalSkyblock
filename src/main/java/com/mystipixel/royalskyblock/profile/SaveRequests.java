package com.mystipixel.royalskyblock.profile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Rate limit for on-demand saves: at most one save per player per cooldown. Requests inside the cooldown
// fold into one trailing save at its end, so the newest state is still written. Main thread only.
final class SaveRequests {

    enum Decision { NOW, DEFERRED, COALESCED }

    private final long cooldownMillis;
    private final Map<UUID, Long> lastSave = new HashMap<>();
    private final Map<UUID, Long> pendingDueAt = new HashMap<>();

    SaveRequests(long cooldownMillis) {
        this.cooldownMillis = cooldownMillis;
    }

    Decision request(UUID player, long now) {
        Long dueAt = pendingDueAt.get(player);
        // a deferred save long overdue never ran (a reload cancels scheduled tasks): don't wait on it forever
        if (dueAt != null && now < dueAt + cooldownMillis) {
            return Decision.COALESCED;
        }
        pendingDueAt.remove(player);
        Long last = lastSave.get(player);
        if (last != null && now - last < cooldownMillis) {
            pendingDueAt.put(player, last + cooldownMillis);
            return Decision.DEFERRED;
        }
        lastSave.put(player, now);
        return Decision.NOW;
    }

    // how long a DEFERRED save waits
    long remainingMillis(UUID player, long now) {
        Long last = lastSave.get(player);
        return last == null ? 0L : Math.max(0L, last + cooldownMillis - now);
    }

    // The deferred save's turn. False if it was dropped meanwhile (the player quit).
    boolean due(UUID player, long now) {
        if (pendingDueAt.remove(player) == null) {
            return false;
        }
        lastSave.put(player, now);
        return true;
    }

    // on quit: the quit save covers anything pending
    void forget(UUID player) {
        pendingDueAt.remove(player);
        lastSave.remove(player);
    }

    int tracked() {
        return lastSave.size();
    }
}
