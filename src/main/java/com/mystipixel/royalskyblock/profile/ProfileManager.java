package com.mystipixel.royalskyblock.profile;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.data.Storage;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.island.IslandRole;
import org.bukkit.Bukkit;
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
    private final Map<UUID, Invite> pendingInvites = new ConcurrentHashMap<>(); // invited player -> invite

    /** A pending coop invite: which profile, who sent it, and when it expires. */
    private record Invite(UUID profileId, String inviterName, long expiresAt) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

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

    /** All profiles a player can access: the ones they own, plus coop profiles they're a member of. */
    public List<Profile> getProfiles(UUID player) {
        java.util.LinkedHashMap<UUID, Profile> byId = new java.util.LinkedHashMap<>();
        for (Profile p : storage.getProfilesByOwner(player)) {
            byId.put(p.id(), p);
            profileCache.put(p.id(), p);
        }
        for (UUID pid : storage.getProfileIdsByMember(player)) {
            if (!byId.containsKey(pid)) {
                Profile p = getProfile(pid);
                if (p != null) {
                    byId.put(pid, p);
                }
            }
        }
        return new java.util.ArrayList<>(byId.values());
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

    // ── coop invites ──────────────────────────────────────────────────────────────

    /** Invite a player to the inviter's active Coop profile. Returns an error message, or null on success. */
    public String invite(Player inviter, Player target) {
        Profile active = getActiveProfile(inviter);
        if (active == null) {
            return "You have no active profile.";
        }
        if (active.gamemode() != Gamemode.COOP) {
            return "You're on '" + active.name() + "' (" + active.gamemode().name().toLowerCase(Locale.ROOT)
                    + "). Switch to a Coop profile to invite members.";
        }
        IslandRole role = active.roleOf(inviter.getUniqueId());
        if (role != IslandRole.OWNER && role != IslandRole.CO_OWNER) {
            return "Only the profile owner can invite members.";
        }
        if (target.getUniqueId().equals(inviter.getUniqueId())) {
            return "You can't invite yourself.";
        }
        if (active.isMember(target.getUniqueId())) {
            return target.getName() + " is already on this profile.";
        }
        Island island = plugin.islands().getIslandByProfile(active.id());
        int max = island != null ? plugin.upgrades().coopMemberCap(island)
                : plugin.getConfig().getInt("coop.max-members", 4);
        if (active.memberCount() >= max) {
            return "This profile is full (" + max + " members).";
        }
        long timeout = plugin.getConfig().getLong("coop.invite-timeout-seconds", 60) * 1000L;
        pendingInvites.put(target.getUniqueId(), new Invite(active.id(), inviter.getName(), System.currentTimeMillis() + timeout));
        return null;
    }

    /** Outcome of an accept attempt: the joined profile on success, else a reason code. */
    public record AcceptResult(@Nullable Profile profile, @Nullable String error) {
    }

    /** Accept a pending coop invite. Re-checks the member cap at accept time (it may have filled since). */
    public AcceptResult acceptInvite(Player target) {
        Invite invite = pendingInvites.remove(target.getUniqueId());
        if (invite == null) {
            return new AcceptResult(null, "none");
        }
        if (invite.expired()) {
            return new AcceptResult(null, "expired");
        }
        Profile profile = getProfile(invite.profileId());
        if (profile == null) {
            return new AcceptResult(null, "none");
        }
        if (profile.isMember(target.getUniqueId())) {
            return new AcceptResult(profile, null); // already joined
        }
        Island island = plugin.islands().getIslandByProfile(profile.id());
        int max = island != null ? plugin.upgrades().coopMemberCap(island)
                : plugin.getConfig().getInt("coop.max-members", 4);
        if (profile.memberCount() >= max) {
            return new AcceptResult(null, "full");
        }
        profile.putMember(new ProfileMember(target.getUniqueId(), target.getName(),
                IslandRole.MEMBER, Instant.now().toEpochMilli()));
        storage.saveProfile(profile);
        profileCache.put(profile.id(), profile);
        notifyJoin(profile, target);
        return new AcceptResult(profile, null);
    }

    /** Tell the online members of a profile that someone just joined. */
    private void notifyJoin(Profile profile, Player joiner) {
        for (ProfileMember m : profile.members()) {
            if (m.uuid().equals(joiner.getUniqueId())) {
                continue;
            }
            Player online = Bukkit.getPlayer(m.uuid());
            if (online != null) {
                plugin.messages().send(online, "coop.member-joined", "player", joiner.getName());
            }
        }
    }

    public boolean denyInvite(Player target) {
        return pendingInvites.remove(target.getUniqueId()) != null;
    }

    public boolean hasInvite(Player target) {
        Invite invite = pendingInvites.get(target.getUniqueId());
        return invite != null && !invite.expired();
    }

    /** Kick a member from the actor's active Coop profile. Returns an error message, or null on success. */
    public String kick(Player actor, String targetName) {
        Profile active = getActiveProfile(actor);
        if (active == null || active.gamemode() != Gamemode.COOP) {
            return "You're not on a Coop profile.";
        }
        IslandRole role = active.roleOf(actor.getUniqueId());
        if (role != IslandRole.OWNER && role != IslandRole.CO_OWNER) {
            return "Only the profile owner can kick members.";
        }
        ProfileMember target = active.members().stream()
                .filter(m -> m.name().equalsIgnoreCase(targetName)).findFirst().orElse(null);
        if (target == null) {
            return "No member named " + targetName + ".";
        }
        if (target.role() == IslandRole.OWNER) {
            return "You can't kick the owner.";
        }
        if (role == IslandRole.CO_OWNER && target.role() == IslandRole.CO_OWNER) {
            return "Only the owner can remove a co-owner.";
        }
        if (target.uuid().equals(actor.getUniqueId())) {
            return "You can't kick yourself — use /is leave.";
        }
        active.removeMember(target.uuid());
        storage.saveProfile(active);
        profileCache.put(active.id(), active);
        moveOffProfileIfActive(target.uuid(), active.id());
        storage.deleteProfileData(active.id(), target.uuid()); // don't leave their coop state orphaned
        return null;
    }

    /** Leave the player's active Coop profile (non-owner only). Returns an error message, or null on success. */
    public String leave(Player player) {
        Profile active = getActiveProfile(player);
        if (active == null || active.gamemode() != Gamemode.COOP) {
            return "You're not on a Coop profile.";
        }
        if (active.roleOf(player.getUniqueId()) == IslandRole.OWNER) {
            return "The owner can't leave — transfer ownership first (/is transfer) or delete the profile.";
        }
        if (!active.isMember(player.getUniqueId())) {
            return "You're not a member of this profile.";
        }
        active.removeMember(player.getUniqueId());
        storage.saveProfile(active);
        profileCache.put(active.id(), active);
        moveOffProfileIfActive(player.getUniqueId(), active.id());
        storage.deleteProfileData(active.id(), player.getUniqueId()); // clear their state on this coop
        return null;
    }

    // ── coop roles / ownership ─────────────────────────────────────────────────────

    /** Promote a member to co-owner. Owner only. Returns an error message, or null on success. */
    public String promote(Player actor, String targetName) {
        return changeRole(actor, targetName, IslandRole.MEMBER, IslandRole.CO_OWNER,
                targetName + " is already a co-owner.");
    }

    /** Demote a co-owner back to member. Owner only. Returns an error message, or null on success. */
    public String demote(Player actor, String targetName) {
        return changeRole(actor, targetName, IslandRole.CO_OWNER, IslandRole.MEMBER,
                targetName + " isn't a co-owner.");
    }

    private String changeRole(Player actor, String targetName, IslandRole from, IslandRole to, String wrongState) {
        Profile active = getActiveProfile(actor);
        if (active == null || active.gamemode() != Gamemode.COOP) {
            return "You're not on a Coop profile.";
        }
        if (active.roleOf(actor.getUniqueId()) != IslandRole.OWNER) {
            return "Only the owner can change member ranks.";
        }
        ProfileMember target = memberByName(active, targetName);
        if (target == null) {
            return "No member named " + targetName + ".";
        }
        if (target.uuid().equals(actor.getUniqueId())) {
            return "You can't change your own rank.";
        }
        if (target.role() != from) {
            return wrongState;
        }
        active.putMember(new ProfileMember(target.uuid(), target.name(), to, target.joinedAt()));
        storage.saveProfile(active);
        profileCache.put(active.id(), active);
        return null;
    }

    /**
     * Transfer ownership of the actor's active Coop profile to another member: the target becomes OWNER
     * and the current owner steps down to co-owner. Current owner only. Returns an error, or null on ok.
     */
    public String transferOwnership(Player actor, String targetName) {
        Profile active = getActiveProfile(actor);
        if (active == null || active.gamemode() != Gamemode.COOP) {
            return "You're not on a Coop profile.";
        }
        if (active.roleOf(actor.getUniqueId()) != IslandRole.OWNER || !active.owner().equals(actor.getUniqueId())) {
            return "Only the owner can transfer ownership.";
        }
        ProfileMember target = memberByName(active, targetName);
        if (target == null) {
            return "No member named " + targetName + ".";
        }
        if (target.uuid().equals(actor.getUniqueId())) {
            return "You already own this profile.";
        }
        ProfileMember self = active.member(actor.getUniqueId());
        long selfJoined = self != null ? self.joinedAt() : Instant.now().toEpochMilli();
        active.putMember(new ProfileMember(target.uuid(), target.name(), IslandRole.OWNER, target.joinedAt()));
        active.putMember(new ProfileMember(actor.getUniqueId(), actor.getName(), IslandRole.CO_OWNER, selfJoined));
        active.setOwner(target.uuid());
        storage.saveProfile(active);
        profileCache.put(active.id(), active);
        return null;
    }

    private ProfileMember memberByName(Profile profile, String name) {
        return profile.members().stream()
                .filter(m -> m.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    /** If the player is online and currently on {@code profileId}, move them to one of their own profiles. */
    private void moveOffProfileIfActive(UUID player, UUID profileId) {
        Player online = Bukkit.getPlayer(player);
        if (online == null || !profileId.equals(getActiveProfileId(player))) {
            return;
        }
        List<Profile> owned = storage.getProfilesByOwner(player);
        if (!owned.isEmpty()) {
            switchProfile(online, owned.get(0).id());
        } else {
            plugin.islands().sendToSpawn(online);
        }
    }

    public String describe(Profile profile) {
        return profile.name() + " &7(" + profile.gamemode().name().toLowerCase(Locale.ROOT) + ")";
    }
}
