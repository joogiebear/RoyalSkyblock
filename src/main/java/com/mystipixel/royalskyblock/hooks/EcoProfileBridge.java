package com.mystipixel.royalskyblock.hooks;

import com.willfp.eco.core.data.PlayerProfile;
import com.willfp.eco.core.data.Profile;
import com.willfp.eco.core.data.keys.PersistentDataKey;
import org.bukkit.Bukkit;

import java.util.UUID;

/**
 * Makes eco-stored progression (skills, pets, collections, coins, anything stored via eco)
 * per-profile. Each RoyalSkyblock profile is shadowed by an eco profile on its own UUID, and switching
 * profiles copies every non-local key in {@link PersistentDataKey#values()} between the player's live
 * eco profile and the shadow, so eco plugins added later are covered automatically.
 *
 * <p>Only constructed when eco is present ({@link #isPresent()} at the call site).
 */
public final class EcoProfileBridge {

    private final boolean present;

    // set when eco resolves a player's data to their profile itself (see EcoProfileResolver), which makes
    // copying pointless
    private boolean resolverActive;

    public EcoProfileBridge() {
        this.present = Bukkit.getPluginManager().isPluginEnabled("eco");
    }

    /** Whether eco is resolving profiles itself, so nothing here needs to copy anything. */
    public boolean isResolverActive() {
        return resolverActive;
    }

    public void setResolverActive(final boolean resolverActive) {
        this.resolverActive = resolverActive;
    }

    public boolean isPresent() {
        return present;
    }

    /** A stable shadow-profile UUID for (player, profile slot), the same across restarts. */
    public static UUID shadowUuid(UUID player, UUID profileId) {
        return UUID.nameUUIDFromBytes(("rsb-profile:" + player + ":" + profileId).getBytes());
    }

    /** Copy the player's live eco data into a profile's shadow (called when leaving that profile). */
    public void save(UUID player, UUID profileId) {
        if (resolverActive) {
            return;
        }
        if (!present || profileId == null) {
            return;
        }
        copyAll(PlayerProfile.load(player), PlayerProfile.load(shadowUuid(player, profileId)));
    }

    /** Copy a profile's shadow eco data into the player's live data (called when entering it). */
    public void load(UUID player, UUID profileId) {
        if (resolverActive) {
            return;
        }
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
        if (resolverActive) {
            return;
        }
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

    private static void copyAll(Profile src, Profile dst) {
        for (PersistentDataKey<?> key : PersistentDataKey.values()) {
            if (key.isLocal()) {
                continue; // session-local keys aren't part of saved progression
            }
            if (OWN_NAMESPACE.equals(key.getKey().getNamespace())) {
                // our own keys are storage, not progression: on the eco backend they include which profile the
                // player is on
                continue;
            }
            copyKey(src, dst, key);
        }
    }

    // the namespace of every key this plugin registers
    private static final String OWN_NAMESPACE = "royalskyblock";

    private static <T> void copyKey(Profile src, Profile dst, PersistentDataKey<T> key) {
        dst.write(key, src.read(key));
    }
}
