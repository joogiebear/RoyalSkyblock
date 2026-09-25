package com.mystipixel.royalskyblock.profile;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.bank.BankService;
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
    /** What {@link #preload} gathered off-thread, waiting to be applied when the player joins. */
    private record Preloaded(List<Profile> profiles, UUID active, @Nullable Profile coop, ProfileData data,
                             List<UUID> payouts) {
    }

    /**
     * The saved active profile, if it is a coop the player is a member of rather than one they own.
     *
     * <p>Login used to check the saved active profile against owned profiles only, so a coop never
     * counted as valid and every coop member was put back on their own profile each time they joined
     * (or handed a brand-new one, if they owned none). A member who was kicked while offline is no
     * longer a member, so they still fall through to their own profile, where their payout lands.
     */
    private static @Nullable Profile joinedCoop(UUID player, @Nullable UUID active, List<Profile> owned,
                                                java.util.function.Function<UUID, Profile> lookup) {
        if (active == null || owned.stream().anyMatch(p -> p.id().equals(active))) {
            return null;
        }
        Profile profile = lookup.apply(active);
        return profile != null && profile.isMember(player) ? profile : null;
    }

    private final Map<UUID, Preloaded> preloaded = new ConcurrentHashMap<>();

    /**
     * Read a joining player's profiles and saved state <em>before</em> they enter the world.
     *
     * <p>Called from {@code AsyncPlayerPreLoginEvent}, which already runs off the server thread and
     * fires while the player is still connecting. Doing it here instead of in the join handler keeps
     * roughly nine database round trips — profiles, members and island per profile, the active id, and
     * the inventory blob — off the main thread entirely. Because it completes before the player exists
     * in the world, there is no window where they are online holding the wrong inventory.
     *
     * <p>Best-effort: if anything fails, or the event never fires, {@link #handleJoin} silently falls
     * back to loading synchronously, exactly as before.
     */
    public void preload(UUID uuid) {
        try {
            List<Profile> profiles = storage.getProfilesByOwner(uuid);
            UUID active = storage.getActiveProfile(uuid);
            Profile coop = joinedCoop(uuid, active, profiles, storage::getProfile);
            ProfileData data = null;
            if (coop != null) {
                data = storage.getProfileData(coop.id(), uuid);
            } else if (!profiles.isEmpty()) {
                UUID resolved = active != null && profiles.stream().anyMatch(p -> p.id().equals(active))
                        ? active : profiles.get(0).id();
                data = storage.getProfileData(resolved, uuid);
            }
            preloaded.put(uuid, new Preloaded(profiles, active, coop, data, storage.getCoopPayouts(uuid)));
        } catch (Exception e) {
            preloaded.remove(uuid);
            plugin.getLogger().warning("Profile preload failed for " + uuid
                    + "; falling back to loading on join: " + e.getMessage());
        }
    }

    private boolean debug() {
        return plugin.conf().getBoolean("settings.debug", false);
    }

    /** Drop a preload for a player who never actually joined (failed login, kick at the door). */
    public void discardPreload(UUID uuid) {
        preloaded.remove(uuid);
    }

    public void handleJoin(Player player) {
        UUID uuid = player.getUniqueId();
        Preloaded ready = preloaded.remove(uuid);
        if (ready != null) {
            if (debug()) {
                plugin.getLogger().info("Profile load for " + player.getName() + " served from preload (no queries on join).");
            }
            applyPreloaded(player, ready);
            if (!ready.payouts().isEmpty()) {
                deliverCoopPayouts(player);
            }
            return;
        }
        loadOnJoin(player);
        deliverCoopPayouts(player);
    }

    /** The synchronous join load, used when no preload is waiting. */
    private void loadOnJoin(Player player) {
        UUID uuid = player.getUniqueId();
        if (debug()) {
            plugin.getLogger().info("Profile load for " + player.getName()
                    + " falling back to synchronous queries (no preload available).");
        }
        List<Profile> profiles = storage.getProfilesByOwner(uuid);
        UUID active = storage.getActiveProfile(uuid);
        Profile coop = joinedCoop(uuid, active, profiles, this::getProfile);

        if (profiles.isEmpty() && coop == null) {
            Profile created = createDefaultProfile(player);
            active = created.id();
            storage.setActiveProfile(uuid, active);
            activeProfile.put(uuid, active);
            // Seed the new profile with the player's current state rather than wiping it.
            state.save(player, active);
            return;
        }

        UUID currentActive = active;
        boolean activeValid = coop != null
                || currentActive != null && profiles.stream().anyMatch(p -> p.id().equals(currentActive));
        if (!activeValid) {
            active = profiles.get(0).id();
            storage.setActiveProfile(uuid, active);
        }
        activeProfile.put(uuid, active);
        state.load(player, active);
    }

    /**
     * Apply preloaded state on the main thread. Mirrors the synchronous path exactly, but every read
     * has already happened; the only queries left are the rare corrections (a brand-new player, or an
     * active id that no longer points at a real profile).
     */
    private void applyPreloaded(Player player, Preloaded ready) {
        UUID uuid = player.getUniqueId();
        List<Profile> profiles = ready.profiles();
        for (Profile p : profiles) {
            profileCache.put(p.id(), p);
        }
        if (ready.coop() != null) {
            profileCache.put(ready.coop().id(), ready.coop());
        }

        if (profiles.isEmpty() && ready.coop() == null) {
            Profile created = createDefaultProfile(player);
            storage.setActiveProfile(uuid, created.id());
            activeProfile.put(uuid, created.id());
            state.save(player, created.id());     // seed the new profile with what they're carrying
            return;
        }

        UUID saved = ready.active();
        UUID active = saved;
        boolean activeValid = ready.coop() != null
                || saved != null && profiles.stream().anyMatch(p -> p.id().equals(saved));
        if (!activeValid) {
            active = profiles.get(0).id();
            storage.setActiveProfile(uuid, active);
            activeProfile.put(uuid, active);
            state.load(player, active);           // preloaded blob was for a different profile
            return;
        }
        activeProfile.put(uuid, active);
        state.load(player, active, ready.data());
    }

    /** On quit: save the player's live state into their active profile. Main thread. */
    public void handleQuit(Player player) {
        UUID active = getActiveProfileId(player.getUniqueId());
        if (active != null) {
            state.save(player, active);
        }
        activeProfile.remove(player.getUniqueId());
        // A second login with this account preloaded before this session's save just above. Applying
        // that copy on join would hand back items given away since the last save. Today the old
        // connection's close also discards it (onConnectionClose), but only because Paper happens to
        // close the old connection before the new join; this makes it deliberate.
        preloaded.remove(player.getUniqueId());
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
        int max = plugin.conf().getInt("profiles.max-profiles", 3);
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
        deliverCoopPayouts(player);   // anything held back while they were on an Ironman profile

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
        // Money in any bank on the profile would become unreachable the moment it is gone, so the delete
        // waits until it has been withdrawn. Members' personal accounts count: it is their money.
        List<String> funded = new java.util.ArrayList<>();
        double coopBalance = plugin.bank().balance(BankService.coopId(targetId));
        if (coopBalance > 0) {
            funded.add("the coop bank (" + plugin.bank().money(coopBalance) + ")");
        }
        for (ProfileMember member : target.members()) {
            double personal = plugin.bank().balance(BankService.personalId(targetId, member.uuid()));
            if (personal > 0) {
                funded.add(member.name() + "'s personal bank (" + plugin.bank().money(personal) + ")");
            }
        }
        if (!funded.isEmpty()) {
            player.sendMessage(com.mystipixel.royalskyblock.util.Text.color(
                    "&cEmpty the banks on this profile before deleting it: &f" + String.join("&c, &f", funded)));
            return CompletableFuture.completedFuture(false);
        }

        // Everyone but the owner leaves first, exactly as if kicked. Deleting a coop used to leave online
        // members on a profile that no longer existed (their next save wrote into a deleted row) and
        // destroyed every member's carried items with the profile's rows. As a kick, each is moved off
        // or recorded as owed, and gets those items back on the profile they land on. Their bank
        // savings are already safe: the check above refuses the delete while any of it is left.
        List<ProfileMember> others = target.members().stream()
                .filter(member -> !member.uuid().equals(uuid))
                .toList();
        if (!others.isEmpty()) {
            others.forEach(member -> target.removeMember(member.uuid()));
            storage.saveProfile(target);
            for (ProfileMember member : others) {
                Player online = Bukkit.getPlayer(member.uuid());
                if (online != null) {
                    plugin.messages().send(online, "coop.profile-deleted", "profile", target.name());
                }
                owePayout(member.uuid(), targetId);
            }
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
                : plugin.conf().getInt("coop.max-members", 4);
        if (active.memberCount() >= max) {
            return "This profile is full (" + max + " members).";
        }
        long timeout = plugin.conf().getLong("coop.invite-timeout-seconds", 60) * 1000L;
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
                : plugin.conf().getInt("coop.max-members", 4);
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
        owePayout(target.uuid(), active.id());
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
        owePayout(player.getUniqueId(), active.id());
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
        // Any open menu was drawn for the profile they just lost; close it rather than leave its
        // buttons live. switchProfile from the GUI closes first, but a kick arrives from outside.
        online.closeInventory();
        List<Profile> owned = storage.getProfilesByOwner(player);
        // Someone with no profile of their own used to be sent to spawn while still set to the coop
        // they had just been removed from. They get a fresh profile to land on instead, which is also
        // where their coop payout is delivered.
        //
        // Not an Ironman profile if it can be helped: this is where their coop payout lands, and an
        // Ironman save is meant to have had no outside help. Only someone who owns nothing else, and
        // has no room for another profile, lands on one; their payout then waits (deliverCoopPayouts).
        UUID landing = owned.stream()
                .filter(p -> p.gamemode() != Gamemode.IRONMAN)
                .map(Profile::id)
                .findFirst()
                .orElse(null);
        if (landing == null) {
            int max = plugin.conf().getInt("profiles.max-profiles", 3);
            landing = owned.size() < max ? createDefaultProfile(online).id() : owned.get(0).id();
        }
        switchProfile(online, landing);
    }

    // ── coop payouts ──────────────────────────────────────────────────────────────

    /**
     * A member is off {@code coop}: move them off it if they are on it, then pay out what was theirs
     * there. Their row and bank account on the coop are kept, not deleted, until that payout lands:
     * deleting them is how a kick used to destroy the member's savings and the items they carried.
     */
    private void owePayout(UUID member, UUID coop) {
        storage.addCoopPayout(member, coop);
        moveOffProfileIfActive(member, coop);   // saves what they carry into the coop row first
        Player online = Bukkit.getPlayer(member);
        if (online != null) {
            deliverCoopPayouts(online);
        }
        // Offline: delivered on their next join, onto whichever profile they land on.
    }

    /**
     * Deliver every payout {@code player} is owed: personal bank savings from each coop they left into
     * their purse, and the items they carried there into their inventory, then ender chest. Anything
     * that does not fit — or that cannot be paid right now — stays owed and is retried on next join.
     * Main thread.
     */
    public void deliverCoopPayouts(Player player) {
        UUID uuid = player.getUniqueId();
        UUID current = getActiveProfileId(uuid);
        List<UUID> owed = storage.getCoopPayouts(uuid);
        if (owed.isEmpty()) {
            return;
        }
        Profile here = getProfile(current);
        if (here != null && here.gamemode() == Gamemode.IRONMAN) {
            // Coop coins and items would be outside help, which Ironman forbids. They stay owed and are
            // paid the next time the player is on any other profile (switchProfile delivers too).
            plugin.messages().send(player, "coop.payout-waiting-ironman");
            return;
        }
        for (UUID from : owed) {
            Profile coop = getProfile(from);
            if (coop != null && coop.isMember(uuid)) {
                storage.removeCoopPayout(uuid, from);   // re-invited: everything is back where it was
                continue;
            }
            if (from.equals(current)) {
                continue;                               // never pay a profile into itself
            }
            String name = coop != null ? coop.name() : "a former coop";
            boolean settled = true;

            double paid = plugin.bank().payOutAll(player, BankService.personalId(from, uuid),
                    player.getName() + " left " + name);
            if (paid < 0) {
                settled = false;
            } else if (paid > 0) {
                plugin.messages().send(player, "coop.payout-coins", "amount", plugin.bank().money(paid), "profile", name);
            }

            ProfileData row = storage.getProfileData(from, uuid);
            if (row != null) {
                List<org.bukkit.inventory.ItemStack> items = state.itemsOf(row);
                if (items == null) {
                    settled = false;                    // undecodable: keep the row, never guess
                    plugin.getLogger().severe("Could not read " + player.getName() + "'s items on " + from
                            + "; their coop payout is kept and will be retried.");
                } else if (!items.isEmpty()) {
                    int given = items.size();
                    List<org.bukkit.inventory.ItemStack> left = new java.util.ArrayList<>(
                            player.getInventory().addItem(items.toArray(new org.bukkit.inventory.ItemStack[0])).values());
                    if (!left.isEmpty()) {
                        left = new java.util.ArrayList<>(
                                player.getEnderChest().addItem(left.toArray(new org.bukkit.inventory.ItemStack[0])).values());
                    }
                    // Save the profile they are on BEFORE touching the coop row, so a crash between the
                    // two can at worst hand the items over twice, never lose them.
                    if (current != null) {
                        state.save(player, current);
                    }
                    if (left.isEmpty()) {
                        storage.deleteProfileData(from, uuid);
                        plugin.messages().send(player, "coop.payout-items", "count", String.valueOf(given), "profile", name);
                    } else {
                        ProfileData rest = state.leftoverRow(left);
                        if (rest != null && storage.saveProfileData(from, uuid, rest)) {
                            plugin.messages().send(player, "coop.payout-partial", "count", String.valueOf(left.size()),
                                    "profile", name);
                        } else {
                            plugin.getLogger().severe("Could not store the rest of " + player.getName()
                                    + "'s coop payout from " + from + "; the original row is kept.");
                        }
                        settled = false;
                    }
                } else {
                    storage.deleteProfileData(from, uuid);
                }
            }
            if (settled) {
                storage.removeCoopPayout(uuid, from);
            }
        }
    }

    public String describe(Profile profile) {
        return profile.name() + " &7(" + profile.gamemode().name().toLowerCase(Locale.ROOT) + ")";
    }
}
