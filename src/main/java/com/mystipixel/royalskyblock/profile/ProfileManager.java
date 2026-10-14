package com.mystipixel.royalskyblock.profile;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.bank.BankService;
import com.mystipixel.royalcommon.storage.DbExecutor;
import com.mystipixel.royalcommon.storage.PlayerHandoff;
import com.mystipixel.royalcommon.storage.VersionPoller;
import com.mystipixel.royalskyblock.data.SqlStorage;
import com.mystipixel.royalskyblock.data.Storage;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.island.IslandRole;
import com.mystipixel.royalskyblock.message.Reason;
import com.mystipixel.royalskyblock.message.ReasonException;
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
 * The profile lifecycle: create, list, switch, delete, plus loading/saving a player's per-profile state
 * on join/quit. Switching swaps the player's whole state and moves them to that profile's island.
 */
public final class ProfileManager {

    private static final String[] FRUIT_NAMES = {
            "Apple", "Banana", "Cherry", "Mango", "Lemon", "Melon", "Papaya", "Peach",
            "Pear", "Kiwi", "Lime", "Coconut", "Guava", "Plum", "Fig", "Orange"};

    // on-demand saves (RoyalSkyblockApi.savePlayerNow) run at most once a second per player
    private static final long SAVE_REQUEST_COOLDOWN_MILLIS = 1_000L;

    private final RoyalSkyblockPlugin plugin;
    private final Storage storage;
    private final PlayerStateService state;

    private final Map<UUID, UUID> activeProfile = new ConcurrentHashMap<>();  // player to active profile
    // players not online here to their stored active profile (null: none), until when that answer is trusted
    private final Map<UUID, OfflineActive> offlineActive = new ConcurrentHashMap<>();
    private static final long OFFLINE_ACTIVE_TTL_MILLIS = 10_000L;
    private static final int OFFLINE_ACTIVE_MAX = 5_000;
    // online players' profile ids in list order, until when that list is trusted; menus and tab-complete
    private final Map<UUID, ProfileList> profileLists = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> listing = ConcurrentHashMap.newKeySet();
    // raised whenever a list is dropped, so a read that started before the change is not kept
    private final java.util.concurrent.atomic.AtomicLong listChanges = new java.util.concurrent.atomic.AtomicLong();
    private static final long PROFILE_LIST_TTL_MILLIS = 30_000L;
    private final SaveRequests saveRequests = new SaveRequests(SAVE_REQUEST_COOLDOWN_MILLIS);
    private final Map<UUID, Profile> profileCache = new ConcurrentHashMap<>();
    // null on stores nothing else writes to (eco storage)
    private volatile @Nullable VersionPoller<UUID> poller;
    private final Map<UUID, Invite> pendingInvites = new ConcurrentHashMap<>(); // invited player to invite

    // Profiles part-way through deletion. Invite accepts are refused for them, or a reload of the
    // still-existing row would be saved straight back.
    private final java.util.Set<UUID> deleting = ConcurrentHashMap.newKeySet();

    private record OfflineActive(@Nullable UUID profileId, long until) {
    }

    private record ProfileList(List<UUID> ids, long until) {
    }

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
            cacheProfile(loaded);
        }
        return loaded;
    }

    private void cacheProfile(Profile profile) {
        profileCache.put(profile.id(), profile);
        VersionPoller<UUID> current = poller;
        if (current != null) {
            current.seen(profile.id(), profile.version());
        }
    }

    /**
     * Runs a targeted update of the profile on the database thread. Main thread. When nobody else wrote the
     * row in between, the version it returns is marked as seen, so the poll does not reload (and briefly undo)
     * this server's own change.
     */
    public void writeProfile(Profile profile, java.util.function.LongSupplier update) {
        DbExecutor db = plugin.db();
        if (db == null) {
            update.getAsLong();
            return;
        }
        db.submit("write profile", update::getAsLong).whenCompleteAsync((version, error) -> {
            if (error != null) {
                plugin.getLogger().warning("Could not save profile " + profile.id() + ": " + error.getMessage());
                return;
            }
            if (version <= 0 || version != profile.version() + 1) {
                return;                          // failed, or another server wrote too: let the poll reload it
            }
            profile.setVersion(version);
            VersionPoller<UUID> current = poller;
            if (current != null) {
                current.seen(profile.id(), version);
            }
        }, db.mainThread());
    }

    /**
     * Polls the cached profiles' versions, so a roster or owner change another server made shows up here within
     * a few seconds. Called from createTasks; a no-op unless the store is a shared (MySQL) one.
     */
    public void startVersionPoll(long periodTicks) {
        DbExecutor db = plugin.db();
        // a single SQLite server writes everything itself, so there is nothing to poll for
        if (!(storage instanceof SqlStorage sql) || !sql.shared() || db == null) {
            return;
        }
        VersionPoller<UUID> current = poller;
        if (current == null) {
            current = new VersionPoller<>(sql.dataSource(), db,
                    VersionPoller.byColumn("profiles", "id", "version", UUID::fromString), this::reload);
            poller = current;
        }
        current.start(plugin, periodTicks, () -> List.copyOf(profileCache.keySet()));
    }

    // main thread: reread the changed profiles on the database thread, then update the cached copies in place
    private void reload(java.util.Set<UUID> changed) {
        DbExecutor db = plugin.db();
        if (db == null) {
            return;
        }
        db.submit("reload profiles", () -> {
            Map<UUID, Profile> fresh = new java.util.HashMap<>();
            for (UUID id : changed) {
                Profile profile = storage.getProfile(id);
                if (profile != null) {
                    fresh.put(id, profile);
                }
            }
            return fresh;
        }).thenAcceptAsync(fresh -> fresh.forEach((id, profile) -> {
            Profile cached = profileCache.get(id);
            if (cached != null) {
                cached.copyFrom(profile);
            }
        }), db.mainThread());
    }

    /**
     * All profiles a player can access: the ones they own, plus coop profiles they're a member of. Served from
     * a list kept for a short while, which create, delete and coop changes made here drop at once.
     */
    public List<Profile> getProfiles(UUID player) {
        List<Profile> cached = cachedProfiles(player);
        if (cached != null) {
            return cached;
        }
        List<Profile> profiles = readProfiles(player);
        rememberProfiles(player, profiles);
        return profiles;
    }

    /**
     * As {@link #getProfiles}, but never touches the store: null when no recent list is cached, and a read
     * starts on the database thread so a later call has one. For tab-complete. Main thread.
     */
    public @Nullable List<Profile> peekProfiles(UUID player) {
        List<Profile> cached = cachedProfiles(player);
        DbExecutor db = plugin.db();
        if (cached != null || db == null || !listing.add(player)) {
            return cached;
        }
        long changesBefore = listChanges.get();
        db.submit("list profiles of " + player, () -> {
            List<Profile> found = new java.util.ArrayList<>(storage.getProfilesByOwner(player));
            java.util.Set<UUID> owned = new java.util.HashSet<>();
            found.forEach(p -> owned.add(p.id()));
            for (UUID pid : storage.getProfileIdsByMember(player)) {
                if (owned.contains(pid)) {
                    continue;
                }
                Profile p = profileCache.get(pid);
                if (p == null) {
                    p = storage.getProfile(pid);
                }
                if (p != null) {
                    found.add(p);
                }
            }
            return found;
        }).whenCompleteAsync((found, error) -> {
            listing.remove(player);
            if (error != null || listChanges.get() != changesBefore) {
                return;
            }
            List<Profile> profiles = new java.util.ArrayList<>(found.size());
            for (Profile read : found) {
                Profile here = profileCache.get(read.id());
                if (here == null) {
                    cacheProfile(read);
                    here = read;
                }
                profiles.add(here);
            }
            rememberProfiles(player, profiles);
        }, db.mainThread());
        return null;
    }

    private @Nullable List<Profile> cachedProfiles(UUID player) {
        ProfileList list = profileLists.get(player);
        if (list == null || list.until() <= System.currentTimeMillis()) {
            return null;
        }
        List<Profile> profiles = new java.util.ArrayList<>(list.ids().size());
        for (UUID id : list.ids()) {
            Profile profile = profileCache.get(id);
            if (profile == null) {
                return null;
            }
            // the version poll may have taken them off a coop since
            if (canUse(player, profile)) {
                profiles.add(profile);
            }
        }
        return profiles;
    }

    private void forgetProfiles(UUID player) {
        profileLists.remove(player);
        listChanges.incrementAndGet();
    }

    private void rememberProfiles(UUID player, List<Profile> profiles) {
        if (Bukkit.getPlayer(player) != null) {
            profileLists.put(player, new ProfileList(profiles.stream().map(Profile::id).toList(),
                    System.currentTimeMillis() + PROFILE_LIST_TTL_MILLIS));
        }
    }

    private List<Profile> readProfiles(UUID player) {
        java.util.LinkedHashMap<UUID, Profile> byId = new java.util.LinkedHashMap<>();
        for (Profile p : storage.getProfilesByOwner(player)) {
            byId.put(p.id(), p);
            cacheProfile(p);
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

    /**
     * The active profile id, for players who may not be online. An offline player's answer is kept only for a
     * few seconds: placeholders and eco data reads ask about the same players over and over, but the player
     * may switch profile on another server.
     */
    public @Nullable UUID peekActiveProfileId(UUID player) {
        UUID cached = activeProfile.get(player);
        if (cached != null) {
            return cached;
        }
        long now = System.currentTimeMillis();
        OfflineActive known = offlineActive.get(player);
        if (known != null && known.until() > now) {
            return known.profileId();
        }
        UUID stored = storage.getActiveProfile(player);
        if (offlineActive.size() >= OFFLINE_ACTIVE_MAX) {
            offlineActive.values().removeIf(entry -> entry.until() <= now);
            if (offlineActive.size() >= OFFLINE_ACTIVE_MAX) {
                offlineActive.clear();
            }
        }
        offlineActive.put(player, new OfflineActive(stored, now + OFFLINE_ACTIVE_TTL_MILLIS));
        return stored;
    }

    public @Nullable Profile getActiveProfile(Player player) {
        return getProfile(getActiveProfileId(player.getUniqueId()));
    }

    // what preload gathered off-thread, waiting to be applied on join
    private record Preloaded(List<Profile> profiles, UUID active, @Nullable Profile coop, ProfileData data,
                             List<UUID> payouts, @Nullable Island island) {
    }

    // The saved active profile, if it is a coop the player is a member of. A member kicked while offline
    // isn't one any more and falls through to their own profile, where their payout lands.
    private static @Nullable Profile joinedCoop(UUID player, @Nullable UUID active, List<Profile> owned,
                                                java.util.function.Function<UUID, Profile> lookup) {
        if (active == null || owned.stream().anyMatch(p -> p.id().equals(active))) {
            return null;
        }
        Profile profile = lookup.apply(active);
        return profile != null && profile.isMember(player) ? profile : null;
    }

    private final Map<UUID, Preloaded> preloaded = new ConcurrentHashMap<>();

    // How long a joining player waits at the login screen for the server they left to finish saving them.
    private static final java.time.Duration HANDOFF_WAIT = java.time.Duration.ofSeconds(5);

    // players this server has (or is about to have) marked ACTIVE in the hand-off table, each with the
    // generation of that session; a release checks it after its SQL, so no lock is held across database calls
    private final Map<UUID, Long> claimed = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong sessions = new java.util.concurrent.atomic.AtomicLong();
    // players whose release (markSaved) failed; the heartbeat retries them
    private final Map<UUID, Long> releasing = new ConcurrentHashMap<>();

    private @Nullable PlayerHandoff handoff() {
        return storage instanceof SqlStorage sql ? sql.handoff() : null;
    }

    /**
     * Before a login loads anything: waits for the server the player came from to finish saving them, then
     * records that this server has them. Returns the server still holding them when the wait timed out (the
     * login should be refused), else null. Off the main thread ({@code AsyncPlayerPreLoginEvent}).
     */
    public @Nullable String claimForLogin(UUID uuid) {
        PlayerHandoff handoff = handoff();
        if (handoff == null) {
            return null;
        }
        try {
            PlayerHandoff.Result wait = handoff.awaitSaved(uuid, HANDOFF_WAIT);
            switch (wait.status()) {
                case TIMED_OUT -> {
                    plugin.getLogger().info("Refused " + uuid + ": server " + wait.holder()
                            + " has not finished saving them yet.");
                    return wait.holder() != null ? wait.holder() : "?";
                }
                case STALE -> plugin.getLogger().warning("Previous server " + wait.holder() + " of " + uuid
                        + " stopped heartbeating; loading what it last saved.");
                default -> {
                }
            }
            markActive(handoff, uuid);
        } catch (java.sql.SQLException e) {
            plugin.getLogger().warning("Could not check where " + uuid + " was last saved; loading anyway: "
                    + e.getMessage());
        }
        return null;
    }

    private void markActive(PlayerHandoff handoff, UUID uuid) throws java.sql.SQLException {
        // recorded before the SQL: a release of an earlier session that lands meanwhile sees it and re-marks
        claimed.put(uuid, sessions.incrementAndGet());
        releasing.remove(uuid);
        handoff.markActive(uuid);
    }

    // After the player's last write is queued: the database thread runs this after it, so the next server
    // only loads once everything has landed. Skipped if they logged back in here meanwhile.
    private void releaseHandoff(UUID uuid) {
        PlayerHandoff handoff = handoff();
        if (handoff == null) {
            return;
        }
        Long released = claimed.remove(uuid);
        if (released == null) {
            return;
        }
        plugin.writeAsync(() -> markSaved(handoff, uuid, released));
    }

    // Database thread. released is the generation of the session being ended: a newer one means the player
    // logged in here again, and its ACTIVE may have landed before this SAVED, so it is said again after.
    private void markSaved(PlayerHandoff handoff, UUID uuid, long released) {
        if (claimed.containsKey(uuid)) {
            releasing.remove(uuid);
            return;                             // back on this server already
        }
        try {
            handoff.markSaved(uuid);
            releasing.remove(uuid);
            Long current = claimed.get(uuid);
            if (current != null && current > released) {
                handoff.markActive(uuid);
            }
        } catch (java.sql.SQLException e) {
            releasing.put(uuid, released);
            plugin.getLogger().warning("Could not record that " + uuid + " was saved; retrying: " + e.getMessage());
        }
    }

    /** Retries releases that failed. On the database thread, from the heartbeat. */
    public void retryReleases() {
        PlayerHandoff handoff = handoff();
        if (handoff != null) {
            for (Map.Entry<UUID, Long> release : List.copyOf(releasing.entrySet())) {
                markSaved(handoff, release.getKey(), release.getValue());
            }
        }
    }

    /**
     * Read a joining player's profiles and saved state before they enter the world. Called from
     * {@code AsyncPlayerPreLoginEvent}, off the main thread. Best-effort: if it fails or never runs,
     * {@link #handleJoin} loads synchronously.
     */
    public void preload(UUID uuid) {
        try {
            List<Profile> profiles = storage.getProfilesByOwner(uuid);
            UUID active = storage.getActiveProfile(uuid);
            Profile coop = joinedCoop(uuid, active, profiles, storage::getProfile);
            ProfileData data = null;
            Island island = null;
            if (coop != null) {
                data = state.savedData(coop.id(), uuid);
                island = storage.getIslandByProfile(coop.id());
            } else if (!profiles.isEmpty()) {
                UUID resolved = active != null && profiles.stream().anyMatch(p -> p.id().equals(active))
                        ? active : profiles.get(0).id();
                data = state.savedData(resolved, uuid);
                island = storage.getIslandByProfile(resolved);
            }
            preloaded.put(uuid, new Preloaded(profiles, active, coop, data, storage.getCoopPayouts(uuid), island));
        } catch (Exception e) {
            preloaded.remove(uuid);
            plugin.getLogger().warning("Profile preload failed for " + uuid
                    + "; falling back to loading on join: " + e.getMessage());
        }
    }

    private boolean debug() {
        return plugin.conf().getBoolean("settings.debug", false);
    }

    /** Drop a preload for a player who never joined (failed login, kick at the door), and let them go. */
    public void discardPreload(UUID uuid) {
        preloaded.remove(uuid);
        if (Bukkit.getPlayer(uuid) == null) {
            releaseHandoff(uuid);               // never joined; after a normal quit this is already done
        }
    }

    /** On join: ensure the player has a profile, then load its state onto them. Main thread. */
    public void handleJoin(Player player) {
        UUID uuid = player.getUniqueId();
        Preloaded ready = preloaded.remove(uuid);
        if (ready != null) {
            if (debug()) {
                plugin.getLogger().info("Profile load for " + player.getName() + " served from preload (no queries on join).");
            }
            applyPreloaded(player, ready);
            if (ready.island() != null) {
                plugin.islands().preloaded(ready.island());
            }
            forgetMissingIsland(uuid);
            if (!ready.payouts().isEmpty()) {
                deliverCoopPayouts(player);
            }
            return;
        }
        PlayerHandoff handoff = handoff();
        if (handoff != null && !claimed.containsKey(uuid)) {
            try {
                markActive(handoff, uuid);
            } catch (java.sql.SQLException e) {
                plugin.getLogger().warning("Could not record that " + player.getName() + " is on this server: "
                        + e.getMessage());
            }
        }
        loadOnJoin(player);
        forgetMissingIsland(uuid);
        deliverCoopPayouts(player);
    }

    // the player may have made their island on another server since this one last looked
    private void forgetMissingIsland(UUID uuid) {
        UUID active = activeProfile.get(uuid);
        if (active != null) {
            plugin.islands().forgetMissing(active);
            plugin.islands().peekIslandByProfile(active);   // warm the cache so placeholders don't read "no island"
        }
    }

    // the synchronous join load, used when no preload is waiting
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
            // seed the new profile with the player's current state rather than wiping it
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

    // Apply preloaded state on the main thread, mirroring the synchronous path; only rare corrections
    // (a new player, a stale active id) still query.
    private void applyPreloaded(Player player, Preloaded ready) {
        UUID uuid = player.getUniqueId();
        List<Profile> profiles = ready.profiles();
        for (Profile p : profiles) {
            cacheProfile(p);
        }
        if (ready.coop() != null) {
            cacheProfile(ready.coop());
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

    /**
     * On quit: save the player's live state into their active profile. The row is written on the database thread,
     * ahead of the handoff release queued after it. Main thread.
     */
    public void handleQuit(Player player) {
        quit(player, false);
    }

    /** As {@link #handleQuit}, but the row is written before this returns. For plugin disable. */
    public void handleShutdown(Player player) {
        quit(player, true);
    }

    private void quit(Player player, boolean shuttingDown) {
        UUID active = getActiveProfileId(player.getUniqueId());
        if (shuttingDown) {
            if (active != null) {
                state.save(player, active);
            }
            state.rows().forget(player.getUniqueId());
        } else {
            if (active != null) {
                state.saveQueued(player, active);
            }
            state.rows().forgetQueued(player.getUniqueId());
        }
        saveRequests.forget(player.getUniqueId());
        activeProfile.remove(player.getUniqueId());
        offlineActive.remove(player.getUniqueId());
        forgetProfiles(player.getUniqueId());
        // A second login may have preloaded before this session's save above; applying it on join would hand
        // back items given away since.
        preloaded.remove(player.getUniqueId());
        releaseHandoff(player.getUniqueId());
    }

    /**
     * Periodic save of an online player's active profile: captured now, written on the storage thread.
     * Skips a player whose profile isn't loaded yet rather than querying for it. Main thread.
     */
    public void autosave(Player player) {
        UUID active = activeProfile.get(player.getUniqueId());
        if (active != null) {
            state.saveInBackground(player, active);
        }
    }

    /**
     * On-demand save of an online player's active profile, through the same path as {@link #autosave}.
     * Rate limited by {@link SaveRequests}: a request inside the cooldown becomes one save at its end.
     * False if the player has no loaded profile. Main thread.
     */
    public boolean requestSave(Player player) {
        UUID uuid = player.getUniqueId();
        if (!activeProfile.containsKey(uuid)) {
            return false;
        }
        long now = System.currentTimeMillis();
        switch (saveRequests.request(uuid, now)) {
            case NOW -> autosave(player);
            case DEFERRED -> {
                long ticks = Math.max(1L, (saveRequests.remainingMillis(uuid, now) + 49L) / 50L);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (saveRequests.due(uuid, System.currentTimeMillis())) {
                        Player online = Bukkit.getPlayer(uuid);
                        if (online != null) {
                            autosave(online);
                        }
                    }
                }, ticks);
            }
            case COALESCED -> {
            }
        }
        return true;
    }

    private Profile createDefaultProfile(Player player) {
        Profile profile = buildProfile(player, Gamemode.SOLO, null, List.of());
        storage.insertProfile(profile);
        cacheProfile(profile);
        forgetProfiles(player.getUniqueId());
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

    // Why a chosen profile name can't be used, or null if it can: colour codes, length, and duplicate
    // names (which make one unreachable by name).
    private static @Nullable Reason nameProblem(String raw, List<Profile> existing) {
        String name = cleanName(raw);
        if (name.isEmpty() || name.length() > 16) {
            return new Reason("profile.errors.name-length");
        }
        if (!name.matches("[A-Za-z0-9 _-]+")) {
            return new Reason("profile.errors.name-chars");
        }
        if (existing.stream().anyMatch(p -> p.name().equalsIgnoreCase(name))) {
            return new Reason("profile.errors.name-taken", "name", name);
        }
        return null;
    }

    // the name with colour codes removed and surrounding space trimmed
    private static String cleanName(String raw) {
        return raw.replaceAll("(?i)[&\u00a7][0-9a-fk-orx]", "").trim();
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
                    new ReasonException(new Reason("profile.errors.limit", "max", String.valueOf(max))));
        }
        if (name != null && !name.isBlank()) {
            Reason problem = nameProblem(name, existing);
            if (problem != null) {
                return CompletableFuture.failedFuture(new ReasonException(problem));
            }
            name = cleanName(name);
        }
        Profile profile = buildProfile(player, gamemode, name, existing);
        storage.insertProfile(profile);
        cacheProfile(profile);
        forgetProfiles(player.getUniqueId());
        return switchProfile(player, profile.id()).thenApply(ok -> profile);
    }

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

        if (current != null) {
            state.saveQueued(player, current);
        }
        storage.setActiveProfile(uuid, targetId);
        activeProfile.put(uuid, targetId);
        state.load(player, targetId);
        deliverCoopPayouts(player);   // anything held back while they were on an Ironman profile

        // take them to the target island (creating it if this profile has never had one)
        return plugin.islands().ensureIsland(targetId)
                .thenCompose(island -> plugin.islands().teleportToIsland(player, island))
                .thenApply(ok -> true);
    }

    private boolean canUse(UUID player, Profile profile) {
        return profile.owner().equals(player) || profile.isMember(player);
    }

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

    /**
     * Delete one of the player's profiles (and its island). Refuses to delete the active profile or the
     * player's only remaining profile.
     */
    public CompletableFuture<Boolean> deleteProfile(Player player, UUID targetId) {
        UUID uuid = player.getUniqueId();
        Profile target = getProfile(targetId);
        if (target == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (!target.owner().equals(uuid)) {
            plugin.messages().sendPlain(player, "profile.delete-not-owner");
            return CompletableFuture.completedFuture(false);
        }
        if (targetId.equals(getActiveProfileId(uuid))) {
            plugin.messages().sendPlain(player, "profile.delete-active");
            return CompletableFuture.completedFuture(false);
        }
        if (storage.getProfilesByOwner(uuid).size() <= 1) {
            plugin.messages().sendPlain(player, "profile.delete-only");
            return CompletableFuture.completedFuture(false);
        }
        // Money in any bank on the profile (members' personal accounts included) would become unreachable,
        // so the delete waits until it is withdrawn.
        List<String> funded = new java.util.ArrayList<>();
        double coopBalance = plugin.bank().balance(BankService.coopId(targetId));
        if (coopBalance > 0) {
            funded.add(plugin.messages().raw("profile.delete-bank-coop", "amount", plugin.bank().money(coopBalance)));
        }
        for (ProfileMember member : target.members()) {
            double personal = plugin.bank().balance(BankService.personalId(targetId, member.uuid()));
            if (personal > 0) {
                funded.add(plugin.messages().raw("profile.delete-bank-personal",
                        "amount", plugin.bank().money(personal), "player", member.name()));
            }
        }
        if (!funded.isEmpty()) {
            plugin.messages().sendPlain(player, "profile.delete-banks",
                    "banks", String.join(plugin.messages().raw("profile.delete-bank-separator"), funded));
            return CompletableFuture.completedFuture(false);
        }

        // The island's host claim comes first: while a member has it loaded on another server the delete is
        // refused there, and nothing below may have happened by then.
        // fresh: a coop member on another server may have just created the island
        Island island = plugin.islands().getIslandByProfileFresh(targetId);
        CompletableFuture<Void> claimed = island != null
                ? plugin.islands().claimForDelete(island.id())
                : CompletableFuture.completedFuture(null);
        return afterClaim(claimed, () -> removeMembersForDelete(uuid, target), () -> {
            deleting.add(targetId);
            pendingInvites.values().removeIf(invite -> invite.profileId().equals(targetId));
            profileCache.remove(targetId);
            forgetProfiles(uuid);
            CompletableFuture<Void> worldDelete = island != null
                    ? plugin.islands().deleteIsland(island.id())
                    : CompletableFuture.completedFuture(null);
            return worldDelete
                    .thenRun(() -> storage.deleteProfile(targetId))
                    .thenApply(ignored -> true)
                    .whenComplete((ok, error) -> {
                        profileCache.remove(targetId);   // anything that re-read it meanwhile
                        deleting.remove(targetId);
                    });
        });
    }

    /**
     * Runs {@code strip} and then {@code rest} once {@code claim} succeeded; a failed claim fails the result
     * with neither run.
     */
    static <T> CompletableFuture<T> afterClaim(CompletableFuture<?> claim, Runnable strip,
                                              java.util.function.Supplier<CompletableFuture<T>> rest) {
        return claim.thenCompose(ignored -> {
            strip.run();
            return rest.get();
        });
    }

    // Everyone but the owner leaves, exactly as if kicked, so each is moved off or recorded as owed and gets
    // their carried items back. Their bank savings were checked before. Main thread.
    private void removeMembersForDelete(UUID owner, Profile target) {
        List<ProfileMember> others = target.members().stream()
                .filter(member -> !member.uuid().equals(owner))
                .toList();
        for (ProfileMember member : others) {
            target.removeMember(member.uuid());
            storage.removeMember(target.id(), member.uuid());
        }
        for (ProfileMember member : others) {
            Player online = Bukkit.getPlayer(member.uuid());
            if (online != null) {
                plugin.messages().send(online, "coop.profile-deleted", "profile", target.name());
            }
            owePayout(member.uuid(), target.id());
        }
    }

    /** Invite a player to the inviter's active Coop profile. Returns why not, or null on success. */
    public @Nullable Reason invite(Player inviter, Player target) {
        Profile active = getActiveProfile(inviter);
        if (active == null) {
            return new Reason("coop.errors.no-profile");
        }
        if (active.gamemode() != Gamemode.COOP) {
            return new Reason("coop.errors.not-coop-profile",
                    "gamemode", active.gamemode().name().toLowerCase(Locale.ROOT), "profile", active.name());
        }
        IslandRole role = active.roleOf(inviter.getUniqueId());
        if (role != IslandRole.OWNER && role != IslandRole.CO_OWNER) {
            return new Reason("coop.errors.invite-not-owner");
        }
        if (target.getUniqueId().equals(inviter.getUniqueId())) {
            return new Reason("coop.errors.invite-self");
        }
        if (active.isMember(target.getUniqueId())) {
            return new Reason("coop.errors.already-member", "player", target.getName());
        }
        Island island = plugin.islands().getIslandByProfile(active.id());
        int max = island != null ? plugin.upgrades().coopMemberCap(island)
                : plugin.conf().getInt("coop.max-members", 4);
        if (active.memberCount() >= max) {
            return new Reason("coop.errors.full", "max", String.valueOf(max));
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
        if (deleting.contains(invite.profileId())) {
            return new AcceptResult(null, "none");       // being deleted: joining would bring it back
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
        ProfileMember joining = new ProfileMember(target.getUniqueId(), target.getName(),
                IslandRole.MEMBER, Instant.now().toEpochMilli());
        // counted in the database: another server may have filled the roster since this copy was read
        switch (storage.addMember(profile.id(), joining, max)) {
            case FULL -> {
                return new AcceptResult(null, "full");
            }
            case FAILED -> {
                return new AcceptResult(null, "none");
            }
            case ALREADY, ADDED -> profile.putMember(joining);
        }
        forgetProfiles(target.getUniqueId());
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

    /** Kick a member from the actor's active Coop profile. Returns why not, or null on success. */
    public @Nullable Reason kick(Player actor, String targetName) {
        Profile active = getActiveProfile(actor);
        if (active == null || active.gamemode() != Gamemode.COOP) {
            return new Reason("coop.errors.not-on-coop");
        }
        IslandRole role = active.roleOf(actor.getUniqueId());
        if (role != IslandRole.OWNER && role != IslandRole.CO_OWNER) {
            return new Reason("coop.errors.kick-not-owner");
        }
        ProfileMember target = active.members().stream()
                .filter(m -> m.name().equalsIgnoreCase(targetName)).findFirst().orElse(null);
        if (target == null) {
            return new Reason("coop.errors.no-member", "player", targetName);
        }
        if (target.role() == IslandRole.OWNER) {
            return new Reason("coop.errors.kick-owner");
        }
        if (role == IslandRole.CO_OWNER && target.role() == IslandRole.CO_OWNER) {
            return new Reason("coop.errors.kick-co-owner");
        }
        if (target.uuid().equals(actor.getUniqueId())) {
            return new Reason("coop.errors.kick-self");
        }
        active.removeMember(target.uuid());
        storage.removeMember(active.id(), target.uuid());
                owePayout(target.uuid(), active.id());
        return null;
    }

    /** Leave the player's active Coop profile (non-owner only). Returns why not, or null on success. */
    public @Nullable Reason leave(Player player) {
        Profile active = getActiveProfile(player);
        if (active == null || active.gamemode() != Gamemode.COOP) {
            return new Reason("coop.errors.not-on-coop");
        }
        if (active.roleOf(player.getUniqueId()) == IslandRole.OWNER) {
            return new Reason("coop.errors.owner-cant-leave");
        }
        if (!active.isMember(player.getUniqueId())) {
            return new Reason("coop.errors.not-member");
        }
        active.removeMember(player.getUniqueId());
        storage.removeMember(active.id(), player.getUniqueId());
                owePayout(player.getUniqueId(), active.id());
        return null;
    }

    /** Promote a member to co-owner. Owner only. Returns why not, or null on success. */
    public @Nullable Reason promote(Player actor, String targetName) {
        return changeRole(actor, targetName, IslandRole.MEMBER, IslandRole.CO_OWNER,
                new Reason("coop.errors.already-co-owner", "player", targetName));
    }

    /** Demote a co-owner back to member. Owner only. Returns why not, or null on success. */
    public @Nullable Reason demote(Player actor, String targetName) {
        return changeRole(actor, targetName, IslandRole.CO_OWNER, IslandRole.MEMBER,
                new Reason("coop.errors.not-co-owner", "player", targetName));
    }

    private @Nullable Reason changeRole(Player actor, String targetName, IslandRole from, IslandRole to,
                                        Reason wrongState) {
        Profile active = getActiveProfile(actor);
        if (active == null || active.gamemode() != Gamemode.COOP) {
            return new Reason("coop.errors.not-on-coop");
        }
        if (active.roleOf(actor.getUniqueId()) != IslandRole.OWNER) {
            return new Reason("coop.errors.rank-not-owner");
        }
        ProfileMember target = memberByName(active, targetName);
        if (target == null) {
            return new Reason("coop.errors.no-member", "player", targetName);
        }
        if (target.uuid().equals(actor.getUniqueId())) {
            return new Reason("coop.errors.rank-self");
        }
        if (target.role() != from) {
            return wrongState;
        }
        active.putMember(new ProfileMember(target.uuid(), target.name(), to, target.joinedAt()));
        storage.setMemberRole(active.id(), target.uuid(), to);
                return null;
    }

    /**
     * Transfer ownership of the actor's active Coop profile to another member: the target becomes OWNER
     * and the current owner steps down to co-owner. Current owner only. Returns why not, or null on ok.
     */
    public @Nullable Reason transferOwnership(Player actor, String targetName) {
        Profile active = getActiveProfile(actor);
        if (active == null || active.gamemode() != Gamemode.COOP) {
            return new Reason("coop.errors.not-on-coop");
        }
        if (active.roleOf(actor.getUniqueId()) != IslandRole.OWNER || !active.owner().equals(actor.getUniqueId())) {
            return new Reason("coop.errors.transfer-not-owner");
        }
        ProfileMember target = memberByName(active, targetName);
        if (target == null) {
            return new Reason("coop.errors.no-member", "player", targetName);
        }
        if (target.uuid().equals(actor.getUniqueId())) {
            return new Reason("coop.errors.transfer-self");
        }
        // the coop counts towards the new owner's profile cap from now on
        int max = plugin.conf().getInt("profiles.max-profiles", 3);
        if (storage.getProfilesByOwner(target.uuid()).size() >= max) {
            return new Reason("coop.errors.transfer-profile-limit", "max", String.valueOf(max), "player", target.name());
        }
        ProfileMember self = active.member(actor.getUniqueId());
        long selfJoined = self != null ? self.joinedAt() : Instant.now().toEpochMilli();
        active.putMember(new ProfileMember(target.uuid(), target.name(), IslandRole.OWNER, target.joinedAt()));
        active.putMember(new ProfileMember(actor.getUniqueId(), actor.getName(), IslandRole.CO_OWNER, selfJoined));
        active.setOwner(target.uuid());
        storage.transferOwner(active.id(), target.uuid(), actor.getUniqueId());
                // upgrade and perk grants were made to the old owner by name; give the new owner the same
        Island island = plugin.islands().getIslandByProfile(active.id());
        if (island != null) {
            plugin.upgrades().replayUnlockCommands(island);
            plugin.perks().replayUnlockCommands(island);
        }
        return null;
    }

    private ProfileMember memberByName(Profile profile, String name) {
        return profile.members().stream()
                .filter(m -> m.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    // if the player is online and on profileId, move them to one of their own profiles
    private void moveOffProfileIfActive(UUID player, UUID profileId) {
        Player online = Bukkit.getPlayer(player);
        if (online == null || !profileId.equals(getActiveProfileId(player))) {
            return;
        }
        // any open menu was drawn for the profile they lost; a kick arrives from outside the GUI, so close it
        online.closeInventory();
        List<Profile> owned = storage.getProfilesByOwner(player);
        // A player with no other profile gets a fresh one to land on, where their coop payout is delivered.
        // Avoid Ironman (no outside help) unless they own nothing else and have no room for another profile.
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

    // A member is off the coop: move them off it if they are on it, then pay out what was theirs. Their row
    // and bank account on the coop are kept until the payout lands.
    private void owePayout(UUID member, UUID coop) {
        forgetProfiles(member);
        storage.addCoopPayout(member, coop);
        moveOffProfileIfActive(member, coop);   // saves what they carry into the coop row first
        Player online = Bukkit.getPlayer(member);
        if (online != null) {
            deliverCoopPayouts(online);
        }
        // offline: delivered on their next join, onto whichever profile they land on
    }

    /**
     * Deliver every payout {@code player} is owed: personal bank savings from each coop they left into
     * their purse, and their carried items into inventory, then ender chest. Anything that doesn't fit or
     * can't be paid now stays owed and is retried on next join. Main thread.
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
            // coop coins and items would be outside help, which Ironman forbids; they stay owed until the player
            // is on another profile
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
            String name = coop != null ? coop.name() : plugin.messages().raw("coop.former-coop");
            boolean settled = true;

            double paid = plugin.bank().payOutAll(player, BankService.personalId(from, uuid),
                    plugin.messages().raw("bank.notes.coop-payout", "profile", name, "player", player.getName()));
            if (paid < 0) {
                settled = false;
            } else if (paid > 0) {
                plugin.messages().send(player, "coop.payout-coins", "amount", plugin.bank().money(paid), "profile", name);
            }

            ProfileData row = state.savedData(from, uuid);
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
                    // save the current profile before touching the coop row, so a crash can at worst duplicate the items,
                    // never lose them
                    if (current != null) {
                        state.save(player, current);
                    }
                    if (left.isEmpty()) {
                        state.rows().deleteNow(from, uuid);
                        plugin.messages().send(player, "coop.payout-items", "count", String.valueOf(given), "profile", name);
                    } else {
                        ProfileData rest = state.leftoverRow(left);
                        if (rest != null && state.rows().saveNow(from, uuid, rest)) {
                            plugin.messages().send(player, "coop.payout-partial", "count", String.valueOf(left.size()),
                                    "profile", name);
                        } else {
                            plugin.getLogger().severe("Could not store the rest of " + player.getName()
                                    + "'s coop payout from " + from + "; the original row is kept.");
                        }
                        settled = false;
                    }
                } else {
                    state.rows().deleteNow(from, uuid);
                }
            }
            if (settled) {
                storage.removeCoopPayout(uuid, from);
            }
        }
    }

    public String describe(Profile profile) {
        return plugin.messages().raw("profile.label",
                "gamemode", profile.gamemode().name().toLowerCase(Locale.ROOT), "name", profile.name());
    }
}
