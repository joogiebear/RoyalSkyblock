package com.mystipixel.royalskyblock.profile;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.data.Storage;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.island.IslandRole;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The profile lifecycle: create, list, switch, delete — plus loading/saving a player's per-profile
 * state on join/quit. A profile is a self-contained save (island + inventory + progression); switching
 * swaps the player's whole state and moves them to that profile's island.
 */
public final class ProfileManager {

    private static final String[] FRUIT_NAMES = {
            "Apple", "Banana", "Cherry", "Mango", "Lemon", "Melon", "Papaya", "Peach",
            "Pear", "Kiwi", "Lime", "Coconut", "Guava", "Plum", "Fig", "Orange"};

    private final RoyalSkyblockPlugin plugin;
    private final Storage storage;
    private final PlayerStateService state;

    private final Map<UUID, UUID> activeProfile = new ConcurrentHashMap<>();  // player -> active profile
    private final Map<UUID, Profile> profileCache = new ConcurrentHashMap<>();

    public ProfileManager(RoyalSkyblockPlugin plugin, Storage storage, PlayerStateService state) {
        this.plugin = plugin;
        this.storage = storage;
        this.state = state;
    }

    // ── lookups ─────────────────────────────────────────────────────────────────

    public @Nullable Profile getProfile(UUID id) {
        if (id == null) {
            return null;
        }
        Profile cached = profileCache.get(id);
        if (cached != null) {
            return cached;
        }
        Profile loaded = storage.getProfile(id);
        if (loaded != null) {
            profileCache.put(id, loaded);
        }
        return loaded;
    }

    public List<Profile> getProfiles(UUID owner) {
        List<Profile> profiles = storage.getProfilesByOwner(owner);
        for (Profile p : profiles) {
            profileCache.put(p.id(), p);
        }
        return profiles;
    }

    public @Nullable UUID getActiveProfileId(UUID player) {
        UUID cached = activeProfile.get(player);
        if (cached != null) {
            return cached;
        }
        UUID stored = storage.getActiveProfile(player);
        if (stored != null) {
            activeProfile.put(player, stored);
        }
        return stored;
    }

    public @Nullable Profile getActiveProfile(Player player) {
        return getProfile(getActiveProfileId(player.getUniqueId()));
    }

    // ── join / quit state handling ────────────────────────────────────────────────

    /** On join: ensure the player has a profile, then load its state onto them. Main thread. */
    public void handleJoin(Player player) {
        UUID uuid = player.getUniqueId();
        List<Profile> profiles = storage.getProfilesByOwner(uuid);
        UUID active = storage.getActiveProfile(uuid);

        if (profiles.isEmpty()) {
            Profile created = createDefaultProfile(player);
            active = created.id();
            storage.setActiveProfile(uuid, active);
            activeProfile.put(uuid, active);
            // Seed the new profile with the player's current state rather than wiping it.
            state.save(player, active);
            return;
        }

        UUID currentActive = active;
        boolean activeValid = currentActive != null && profiles.stream().anyMatch(p -> p.id().equals(currentActive));
        if (!activeValid) {
            active = profiles.get(0).id();
            storage.setActiveProfile(uuid, active);
        }
        activeProfile.put(uuid, active);
        state.load(player, active);
    }

    /** On quit: save the player's live state into their active profile. Main thread. */
    public void handleQuit(Player player) {
        UUID active = getActiveProfileId(player.getUniqueId());
        if (active != null) {
            state.save(player, active);
        }
        activeProfile.remove(player.getUniqueId());
    }

    // ── create ──────────────────────────────────────────────────────────────────

    private Profile createDefaultProfile(Player player) {
        Profile profile = buildProfile(player, Gamemode.SOLO, null, List.of());
        storage.saveProfile(profile);
        profileCache.put(profile.id(), profile);
        return profile;
    }

    private Profile buildProfile(Player player, Gamemode gamemode, @Nullable String name, List<Profile> existing) {
        UUID id = UUID.randomUUID();
        long now = Instant.now().toEpochMilli();
        String finalName = name != null && !name.isBlank() ? name.trim() : nextFruitName(existing);
        Profile profile = new Profile(id, player.getUniqueId(), finalName, gamemode, now);
        profile.putMember(new ProfileMember(player.getUniqueId(), player.getName(), IslandRole.OWNER, now));
        return profile;
    }

    private String nextFruitName(List<Profile> existing) {
        for (String fruit : FRUIT_NAMES) {
            boolean taken = existing.stream().anyMatch(p -> p.name().equalsIgnoreCase(fruit));
            if (!taken) {
                return fruit;
            }
        }
        return "Profile-" + (existing.size() + 1);
    }

    /**
     * Create a new profile for the player and switch to it (fresh island + empty state). Fails if the
     * player is at the profile cap.
     */
    public CompletableFuture<Profile> createProfile(Player player, Gamemode gamemode, @Nullable String name) {
        List<Profile> existing = storage.getProfilesByOwner(player.getUniqueId());
        int max = plugin.getConfig().getInt("profiles.max-profiles", 3);
        if (existing.size() >= max) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("You've reached the profile limit (" + max + ")."));
        }
        Profile profile = buildProfile(player, gamemode, name, existing);
        storage.saveProfile(profile);
        profileCache.put(profile.id(), profile);
        return switchProfile(player, profile.id()).thenApply(ok -> profile);
    }

    // ── switch ──────────────────────────────────────────────────────────────────

    /**
     * Switch the player to another of their profiles: save current state, load the target's, and take
     * them to its island (creating one if the profile has none yet). Completes {@code false} if the
     * target isn't theirs.
     */
    public CompletableFuture<Boolean> switchProfile(Player player, UUID targetId) {
        UUID uuid = player.getUniqueId();
        Profile target = getProfile(targetId);
        if (target == null || !canUse(uuid, target)) {
            return CompletableFuture.completedFuture(false);
        }
        UUID current = getActiveProfileId(uuid);
        if (targetId.equals(current)) {
            return CompletableFuture.completedFuture(true);
        }

        // Swap state on the main thread.
        if (current != null) {
            state.save(player, current);
        }
        storage.setActiveProfile(uuid, targetId);
        activeProfile.put(uuid, targetId);
        state.load(player, targetId);

        // Take them to the target island (creating it if this profile has never had one).
        return plugin.islands().ensureIsland(targetId)
                .thenCompose(island -> plugin.islands().teleportToIsland(player, island))
                .thenApply(ok -> true);
    }

    private boolean canUse(UUID player, Profile profile) {
        return profile.owner().equals(player) || profile.isMember(player);
    }

    // ── island convenience (active profile) ───────────────────────────────────────

    /** Take the player to their active profile's island, creating it if this profile has none yet. */
    public CompletableFuture<Boolean> goToActiveIsland(Player player) {
        UUID active = getActiveProfileId(player.getUniqueId());
        if (active == null) {
            return CompletableFuture.completedFuture(false);
        }
        return plugin.islands().ensureIsland(active)
                .thenCompose(island -> plugin.islands().teleportToIsland(player, island));
    }

    /** Whether the player's active profile already has an island. */
    public boolean activeHasIsland(Player player) {
        UUID active = getActiveProfileId(player.getUniqueId());
        return active != null && plugin.islands().getIslandByProfile(active) != null;
    }

    // ── delete ────────────────────────────────────────────────────────────────────

    /**
     * Delete one of the player's profiles (and its island). Refuses to delete the active profile or the
     * player's only remaining profile.
     */
    public CompletableFuture<Boolean> deleteProfile(Player player, UUID targetId) {
        UUID uuid = player.getUniqueId();
        Profile target = getProfile(targetId);
        if (target == null || !target.owner().equals(uuid)) {
            return CompletableFuture.completedFuture(false);
        }
        if (targetId.equals(getActiveProfileId(uuid))) {
            player.sendMessage(com.mystipixel.royalskyblock.util.Text.color(
                    "&cYou can't delete the profile you're on — switch to another first."));
            return CompletableFuture.completedFuture(false);
        }
        if (storage.getProfilesByOwner(uuid).size() <= 1) {
            player.sendMessage(com.mystipixel.royalskyblock.util.Text.color(
                    "&cYou can't delete your only profile."));
            return CompletableFuture.completedFuture(false);
        }

        profileCache.remove(targetId);
        Island island = plugin.islands().getIslandByProfile(targetId);
        CompletableFuture<Void> worldDelete = island != null
                ? plugin.islands().deleteIsland(island.id())
                : CompletableFuture.completedFuture(null);
        return worldDelete
                .thenRun(() -> storage.deleteProfile(targetId))
                .thenApply(ignored -> true);
    }

    public String describe(Profile profile) {
        return profile.name() + " &7(" + profile.gamemode().name().toLowerCase(Locale.ROOT) + ")";
    }
}
