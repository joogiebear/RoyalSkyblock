package com.mystipixel.royalskyblock.hooks;

import com.willfp.eco.core.data.PlayerProfile;
import com.willfp.eco.core.data.Profile;
import com.willfp.eco.core.data.keys.PersistentDataKey;
import org.bukkit.Bukkit;

import java.util.UUID;

/**
 * Bridges RoyalSkyblock profiles to eco's per-player data so that progression (skills, pets,
 * collections, jobs, coins — anything stored via eco) becomes per-profile.
 *
 * <p>The trick: eco can {@link PlayerProfile#load(UUID) load a data profile for any UUID}, and
 * {@link PersistentDataKey#values()} lists every key the whole eco suite has registered. So each
 * RoyalSkyblock profile is shadowed by an eco profile keyed by the profile's own UUID, and switching
 * profiles just copies every non-local key between the player's live profile and the shadow. This is
 * suite-agnostic: any eco plugin added later is scoped automatically, with no code changes here.
 *
 * <p>Guarded by eco being present; on a server without eco this is a no-op and the class's eco types
 * are never touched (construction is gated by {@link #isPresent()} at the call site).
 */
public final class EcoProfileBridge {

    private final boolean present;

    public EcoProfileBridge() {
        this.present = Bukkit.getPluginManager().isPluginEnabled("eco");
    }

    public boolean isPresent() {
        return present;
    }

    /**
     * A stable shadow-profile UUID for (player, profile-slot). Derived deterministically so the same
     * slot always maps to the same eco profile across restarts.
     */
    public static UUID shadowUuid(UUID player, UUID profileId) {
        return UUID.nameUUIDFromBytes(("rsb-profile:" + player + ":" + profileId).getBytes());
    }

    /** Copy the player's live eco data into a profile's shadow (called when leaving that profile). */
    public void save(UUID player, UUID profileId) {
        if (!present || profileId == null) {
            return;
        }
        copyAll(PlayerProfile.load(player), PlayerProfile.load(shadowUuid(player, profileId)));
    }

    /** Copy a profile's shadow eco data into the player's live data (called when entering it). */
    public void load(UUID player, UUID profileId) {
        if (!present || profileId == null) {
            return;
        }
        copyAll(PlayerProfile.load(shadowUuid(player, profileId)), PlayerProfile.load(player));
    }

    /**
     * Move the player's live eco data into the {@code from} profile's shadow, then load the {@code to}
     * profile's shadow into the player's live data. Passing {@code null} for {@code from} skips the
     * save (first load of a session); {@code null} {@code to} skips the load.
     */
    public void swap(UUID player, UUID from, UUID to) {
        if (!present) {
            return;
        }
        Profile live = PlayerProfile.load(player);
        if (from != null) {
            copyAll(live, PlayerProfile.load(shadowUuid(player, from)));
        }
        if (to != null) {
            copyAll(PlayerProfile.load(shadowUuid(player, to)), live);
        }
    }

    /** Copy every non-local persistent key's value from {@code src} to {@code dst}. */
    private static void copyAll(Profile src, Profile dst) {
        for (PersistentDataKey<?> key : PersistentDataKey.values()) {
            if (key.isLocal()) {
                continue; // session-local keys aren't part of saved progression
            }
            copyKey(src, dst, key);
        }
    }

    private static <T> void copyKey(Profile src, Profile dst, PersistentDataKey<T> key) {
        dst.write(key, src.read(key));
    }
}
