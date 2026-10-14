package com.mystipixel.royalskyblock.profile;

import com.mystipixel.royalskyblock.data.Storage;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every write to a player's {@code profile_data} row, so a background save can never land on top of a
 * later one. Each write takes a ticket when its data is captured; a background write runs only if no
 * later ticket was taken for the same row. Call everything except the background task from the main thread.
 */
final class ProfileRowWriter {

    private record Key(UUID profileId, UUID player) {
    }

    // latest ticket taken for one row; also the lock a row's write holds while it runs
    private static final class Slot {
        final AtomicLong latest = new AtomicLong();
        // the newest queued data not yet written, so a read before the write lands still gets it
        volatile @Nullable Pending pending;
    }

    private record Pending(long ticket, ProfileData data) {
    }

    private final Storage storage;
    private final Executor background;
    private final AtomicLong tickets = new AtomicLong();
    private final Map<Key, Slot> slots = new ConcurrentHashMap<>();

    ProfileRowWriter(Storage storage, Executor background) {
        this.storage = storage;
        this.background = background;
    }

    // Writes now. Waits only if a background write of this same row is mid-flight, so it lands after it.
    boolean saveNow(UUID profileId, UUID player, ProfileData data) {
        long ticket = tickets.incrementAndGet();
        Slot slot = take(new Key(profileId, player), ticket, null);
        synchronized (slot) {
            return storage.saveProfileData(profileId, player, data);
        }
    }

    void deleteNow(UUID profileId, UUID player) {
        long ticket = tickets.incrementAndGet();
        Slot slot = take(new Key(profileId, player), ticket, null);
        synchronized (slot) {
            storage.deleteProfileData(profileId, player);
        }
    }

    // data must already be captured (serialized) by the caller; only the storage write is deferred
    void saveLater(UUID profileId, UUID player, ProfileData data) {
        Key key = new Key(profileId, player);
        long ticket = tickets.incrementAndGet();
        take(key, ticket, data);
        background.execute(() -> writeIfCurrent(key, ticket, data));
    }

    /** The newest data queued for the row and not yet written, or null. Safe from any thread. */
    @Nullable ProfileData pending(UUID profileId, UUID player) {
        Slot slot = slots.get(new Key(profileId, player));
        Pending pending = slot != null ? slot.pending : null;
        return pending != null ? pending.data() : null;
    }

    // atomic with a queued forget, which must not drop a slot this ticket is being set on
    private Slot take(Key key, long ticket, @Nullable ProfileData queued) {
        return slots.compute(key, (k, existing) -> {
            Slot slot = existing != null ? existing : new Slot();
            slot.latest.set(ticket);
            slot.pending = queued != null ? new Pending(ticket, queued) : null;
            return slot;
        });
    }

    // a missing slot means the player quit after a newer save, which counts as superseded
    private void writeIfCurrent(Key key, long ticket, ProfileData data) {
        Slot slot = slots.get(key);
        if (slot == null) {
            return;
        }
        synchronized (slot) {
            if (slot.latest.get() == ticket && storage.saveProfileData(key.profileId(), key.player(), data)) {
                Pending pending = slot.pending;
                if (pending != null && pending.ticket() == ticket) {
                    slot.pending = null;
                }
            }
        }
    }

    // Drop a player's tickets once their final save is written (on quit). Queued older writes are then skipped.
    void forget(UUID player) {
        slots.keySet().removeIf(key -> key.player().equals(player));
    }

    // Like forget, after a final save that was queued: runs on the storage thread behind it, and keeps any row a
    // newer session (a quick rejoin) has taken a ticket on since.
    void forgetQueued(UUID player) {
        long upTo = tickets.get();
        background.execute(() -> {
            for (Key key : List.copyOf(slots.keySet())) {
                if (key.player().equals(player)) {
                    slots.computeIfPresent(key, (k, slot) -> slot.latest.get() <= upTo ? null : slot);
                }
            }
        });
    }

    int trackedRows() {
        return slots.size();
    }
}
