package com.mystipixel.royalskyblock.profile;

import com.mystipixel.royalskyblock.data.Storage;

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
        Slot slot = slot(profileId, player);
        synchronized (slot) {
            slot.latest.set(tickets.incrementAndGet());
            return storage.saveProfileData(profileId, player, data);
        }
    }

    void deleteNow(UUID profileId, UUID player) {
        Slot slot = slot(profileId, player);
        synchronized (slot) {
            slot.latest.set(tickets.incrementAndGet());
            storage.deleteProfileData(profileId, player);
        }
    }

    // data must already be captured (serialized) by the caller; only the storage write is deferred
    void saveLater(UUID profileId, UUID player, ProfileData data) {
        Key key = new Key(profileId, player);
        long ticket = tickets.incrementAndGet();
        slot(profileId, player).latest.set(ticket);
        background.execute(() -> writeIfCurrent(key, ticket, data));
    }

    // a missing slot means the player quit after a newer save, which counts as superseded
    private void writeIfCurrent(Key key, long ticket, ProfileData data) {
        Slot slot = slots.get(key);
        if (slot == null) {
            return;
        }
        synchronized (slot) {
            if (slot.latest.get() == ticket) {
                storage.saveProfileData(key.profileId(), key.player(), data);
            }
        }
    }

    private Slot slot(UUID profileId, UUID player) {
        return slots.computeIfAbsent(new Key(profileId, player), k -> new Slot());
    }

    // Drop a player's tickets once their final save is written (on quit). Queued older writes are then skipped.
    void forget(UUID player) {
        slots.keySet().removeIf(key -> key.player().equals(player));
    }

    int trackedRows() {
        return slots.size();
    }
}
