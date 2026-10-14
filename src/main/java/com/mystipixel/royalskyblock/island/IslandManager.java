package com.mystipixel.royalskyblock.island;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.api.IslandCatchupEvent;
import com.mystipixel.royalcommon.storage.DbExecutor;
import com.mystipixel.royalcommon.storage.VersionPoller;
import com.mystipixel.royalskyblock.data.IslandHostLock;
import com.mystipixel.royalskyblock.data.SqlStorage;
import com.mystipixel.royalskyblock.data.Storage;
import com.mystipixel.royalskyblock.data.StorageException;
import com.mystipixel.royalskyblock.world.IslandWorldService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Creates islands (slime world plus starter), teleports players in, and deletes them. Islands belong
 * to profiles; who may build resolves through {@link com.mystipixel.royalskyblock.profile.ProfileManager}.
 */
public final class IslandManager {

    private final RoyalSkyblockPlugin plugin;
    private final Storage storage;
    private final IslandWorldService worlds;
    private final com.mystipixel.royalskyblock.world.IslandTrash trash;

    private final Map<UUID, Island> byId = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> profileToIsland = new ConcurrentHashMap<>();
    // profiles the store had no island for, until when (millis); expires so one made on another server shows up
    private final Map<UUID, Long> noIsland = new ConcurrentHashMap<>();
    private static final long NO_ISLAND_TTL_MILLIS = 30_000L;
    // profiles a placeholder peek is looking up on the database thread
    private final Set<UUID> peeking = ConcurrentHashMap.newKeySet();
    // in-flight creations, so two rapid ensureIsland calls can't both allocate a world
    private final Map<UUID, CompletableFuture<Island>> creating = new ConcurrentHashMap<>();
    // null on stores nothing else writes to (eco storage)
    private volatile @Nullable VersionPoller<UUID> poller;
    // islands this server is claiming or loading right now, with how many loads are in flight for each
    private final Map<UUID, Integer> claiming = new ConcurrentHashMap<>();
    // world uid to the island id its name carries, or NOT_ISLAND; cleared when the config (and so the prefix) changes
    private final Map<UUID, UUID> worldToIsland = new ConcurrentHashMap<>();
    private static final UUID NOT_ISLAND = new UUID(0L, 0L);
    private volatile @Nullable FileConfiguration prefixFrom;

    public IslandManager(RoyalSkyblockPlugin plugin, Storage storage, IslandWorldService worlds) {
        this.plugin = plugin;
        this.storage = storage;
        this.worlds = worlds;
        this.trash = new com.mystipixel.royalskyblock.world.IslandTrash(plugin);
    }

    /** The island trash, where deleted worlds are archived. */
    public com.mystipixel.royalskyblock.world.IslandTrash trash() {
        return trash;
    }

    public @Nullable Island getIsland(UUID islandId) {
        Island cached = byId.get(islandId);
        if (cached != null) {
            return cached;
        }
        Island loaded = storage.getIsland(islandId);
        if (loaded != null) {
            cache(loaded);
        }
        return loaded;
    }

    /**
     * The profile's island. A profile found to have none is remembered for a short while, so callers on hot paths
     * (perks, holders, menus) don't query again each time; use {@link #getIslandByProfileFresh} before creating one.
     */
    public @Nullable Island getIslandByProfile(UUID profileId) {
        UUID cached = profileToIsland.get(profileId);
        if (cached != null) {
            return getIsland(cached);
        }
        if (knownMissing(profileId)) {
            return null;
        }
        Island island = storage.getIslandByProfile(profileId);
        if (island != null) {
            cache(island);
        } else {
            markMissing(profileId);
        }
        return island;
    }

    /** As {@link #getIslandByProfile}, but asks the store even when the profile was recently seen with no island. */
    public @Nullable Island getIslandByProfileFresh(UUID profileId) {
        noIsland.remove(profileId);
        return getIslandByProfile(profileId);
    }

    /**
     * The profile's island if it is cached, never touching the store; for placeholders, which may run off the
     * main thread. A miss starts a lookup on the database thread, so a later call finds it.
     */
    public @Nullable Island peekIslandByProfile(UUID profileId) {
        UUID cached = profileToIsland.get(profileId);
        if (cached != null) {
            return byId.get(cached);
        }
        if (knownMissing(profileId)) {
            return null;
        }
        DbExecutor db = plugin.db();
        if (db != null && peeking.add(profileId)) {
            db.submit("peek island of " + profileId, () -> storage.getIslandByProfile(profileId))
                    .whenCompleteAsync((island, error) -> {
                        peeking.remove(profileId);
                        // a create, load or delete on main while this ran knows better
                        if (error != null || profileToIsland.containsKey(profileId)) {
                            return;
                        }
                        if (island == null) {
                            markMissing(profileId);
                        } else if (!byId.containsKey(island.id())) {
                            cache(island);
                        }
                    }, db.mainThread());
        }
        return null;
    }

    /** Cache an island read off-thread before its player joined, unless a fresher copy is already here. Main thread. */
    public void preloaded(Island island) {
        if (!profileToIsland.containsKey(island.profileId()) && !byId.containsKey(island.id())) {
            cache(island);
        }
    }

    /** Forget that the profile had no island, e.g. when one of its members joins from another server. */
    public void forgetMissing(UUID profileId) {
        noIsland.remove(profileId);
    }

    private boolean knownMissing(UUID profileId) {
        Long until = noIsland.get(profileId);
        return until != null && until > System.currentTimeMillis();
    }

    private void markMissing(UUID profileId) {
        long now = System.currentTimeMillis();
        if (noIsland.size() > 1024) {
            noIsland.values().removeIf(until -> until <= now);
        }
        noIsland.put(profileId, now + NO_ISLAND_TTL_MILLIS);
    }

    /** The island a world belongs to; each island's world is named {@code <prefix><islandId>}. */
    public @Nullable Island getIslandByWorld(World world) {
        if (world == null) {
            return null;
        }
        FileConfiguration conf = plugin.conf();
        if (conf != prefixFrom || worldToIsland.size() > 4096) {
            worldToIsland.clear();
            prefixFrom = conf;
        }
        UUID islandId = worldToIsland.computeIfAbsent(world.getUID(),
                uid -> islandIdOf(world.getName(), conf.getString("world.world-name-prefix", "island_")));
        return NOT_ISLAND.equals(islandId) ? null : getIsland(islandId);
    }

    private static UUID islandIdOf(String worldName, String prefix) {
        if (!worldName.startsWith(prefix)) {
            return NOT_ISLAND;
        }
        try {
            return UUID.fromString(worldName.substring(prefix.length()));
        } catch (IllegalArgumentException notAnIslandWorld) {
            return NOT_ISLAND;
        }
    }

    private void cache(Island island) {
        byId.put(island.id(), island);
        profileToIsland.put(island.profileId(), island.id());
        noIsland.remove(island.profileId());
        VersionPoller<UUID> poller = this.poller;
        if (poller != null) {
            poller.seen(island.id(), island.version());
        }
    }

    /**
     * Polls the cached islands' versions, so a change another server made to one shows up here within a few
     * seconds. Called from createTasks; a no-op unless the store is a shared (MySQL) one.
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
                    VersionPoller.byColumn("islands", "id", "version", UUID::fromString), this::reload);
            poller = current;
        }
        current.start(plugin, periodTicks, () -> List.copyOf(byId.keySet()));
    }

    // main thread: reread the changed islands on the database thread, then update the cached copies in place
    private void reload(Set<UUID> changed) {
        DbExecutor db = plugin.db();
        if (db == null) {
            return;
        }
        db.submit("reload islands", () -> {
            Map<UUID, Island> fresh = new HashMap<>();
            for (UUID id : changed) {
                fresh.put(id, storage.getIsland(id));
            }
            return fresh;
        }).thenAcceptAsync(fresh -> fresh.forEach(this::refresh), db.mainThread());
    }

    // main thread; fresh is null when another server deleted the island
    private void refresh(UUID id, @Nullable Island fresh) {
        Island cached = byId.get(id);
        if (cached == null) {
            return;
        }
        if (fresh == null) {
            if (!worlds.isLoaded(cached.worldName())) {
                byId.remove(id);
                profileToIsland.remove(cached.profileId(), id);
            }
            return;
        }
        int radius = cached.radius();
        UUID profile = cached.profileId();
        cached.copyFrom(fresh);
        if (!profile.equals(fresh.profileId())) {
            profileToIsland.remove(profile, id);
            profileToIsland.put(fresh.profileId(), id);
            noIsland.remove(fresh.profileId());
        }
        if (radius != fresh.radius() && worlds.isLoaded(cached.worldName())) {
            plugin.upgrades().reapplyBorder(cached);
        }
    }

    /**
     * Get the profile's island, creating it (world plus starter) if needed. A second call while the first
     * is still building joins the in-flight future rather than allocating another world.
     */
    public CompletableFuture<Island> ensureIsland(UUID profileId) {
        Island existing;
        try {
            existing = getIslandByProfileFresh(profileId);
        } catch (StorageException e) {
            // unknown is not absent: creating here would give the profile a second island
            return CompletableFuture.failedFuture(e);
        }
        if (existing != null) {
            return CompletableFuture.completedFuture(existing);
        }
        return creating.computeIfAbsent(profileId, id ->
                createIslandForProfile(id).whenComplete((island, error) -> creating.remove(id)));
    }

    /** Allocate a fresh slime world + starter island for a profile and persist it. Does not teleport. */
    public CompletableFuture<Island> createIslandForProfile(UUID profileId) {
        UUID islandId = UUID.randomUUID();
        String prefix = plugin.conf().getString("world.world-name-prefix", "island_");
        String worldName = prefix + islandId;

        ConfigurationSection paste = section("island.paste");
        ConfigurationSection homeOff = section("island.home-offset");
        int px = paste != null ? paste.getInt("x", 0) : 0;
        int py = paste != null ? paste.getInt("y", 100) : 100;
        int pz = paste != null ? paste.getInt("z", 0) : 0;
        int startingRadius = plugin.conf().getInt("island.starting-radius", 50);
        long now = Instant.now().toEpochMilli();

        beginClaim(islandId);
        return claimHost(islandId, false)
                .thenCompose(claim -> worlds.createIsland(worldName))
                .thenCompose(world -> onMain(() -> {
                    // prefer a WorldEdit/FAWE schematic; fall back to the code-generated starter
                    String schematic = plugin.conf().getString("island.starter.schematic", "default");
                    if (!plugin.schematics().tryPasteSchematic(world, px, py, pz, schematic)) {
                        StarterIslandBuilder.paste(world, px, py, pz, section("island.starter"), plugin.getLogger());
                    }

                    double hx = px + (homeOff != null ? homeOff.getInt("x", 0) : 0) + 0.5;
                    double hy = py + (homeOff != null ? homeOff.getInt("y", 1) : 1);
                    double hz = pz + (homeOff != null ? homeOff.getInt("z", 0) : 0) + 0.5;
                    float yaw = homeOff != null ? (float) homeOff.getDouble("yaw", 0) : 0f;
                    float pitch = homeOff != null ? (float) homeOff.getDouble("pitch", 0) : 0f;
                    world.setSpawnLocation((int) hx, (int) hy, (int) hz);

                    Island island = new Island(islandId, profileId, worldName, now);
                    island.setRadius(startingRadius);
                    island.setHome(hx, hy, hz, yaw, pitch);
                    applyBorder(world, island);
                    plugin.worldRules().applyGameRules(world);
                    return island;
                }))
                .thenCompose(island -> {
                    worlds.saveIsland(worldName);        // the starter build, now that it is pasted
                    // Await the row: an island only in the cache is gone after a restart and the profile would get a
                    // second one. On failure the fresh world is removed so a retry starts clean.
                    return runAsyncFuture(() -> {
                        if (!storage.insertIsland(island)) {
                            throw new IllegalStateException("the new island could not be saved to the database");
                        }
                    }).thenApply(ignored -> {
                        cache(island);
                        return island;
                    }).exceptionallyCompose(error -> worlds.deleteIsland(worldName)
                            .handle((ignored, cleanupError) -> null)
                            .thenCompose(ignored -> CompletableFuture.<Island>failedFuture(error)));
                })
                .whenComplete((island, error) -> endClaim(islandId, worldName, error));
    }

    // Every path that loads an island must go through here, not worlds.loadIsland directly, or the
    // island loses its offline catch-up and is not claimed for this server.
    private CompletableFuture<World> loadWithCatchup(Island island) {
        if (worlds.isLoaded(island.worldName())) {
            // already hosted here; the heartbeat keeps the claim alive
            return worlds.loadIsland(island.worldName()).thenCompose(world -> onMain(() -> {
                plugin.unloads().forget(island.worldName());
                return world;
            }));
        }
        UUID id = island.id();
        beginClaim(id);
        return claimHost(id, true)
                .thenCompose(claim -> worlds.loadIsland(island.worldName()).thenCompose(world -> onMain(() -> {
                    if (claim.fresh() != null) {
                        island.copyFrom(claim.fresh());   // what the last host saved, not this server's old copy
                    }
                    plugin.unloads().forget(island.worldName());
                    plugin.worldRules().applyGameRules(world);
                    fireCatchup(island, world, claim.unloadedAt());
                    plugin.upgrades().onIslandLoaded(island);
                    return world;
                })))
                .whenComplete((world, error) -> endClaim(id, island.worldName(), error));
    }

    private record Claim(long unloadedAt, @Nullable Island fresh, @Nullable String hostedOn) {
    }

    // Claims the island for this server on the database thread. When loading, also takes the unload stamp
    // (so only this load pays that catch-up) and rereads the row.
    private CompletableFuture<Claim> claimHost(UUID islandId, boolean loading) {
        DbExecutor db = plugin.db();
        if (db == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("RoyalSkyblock is shutting down"));
        }
        IslandHostLock hosts = storage instanceof SqlStorage sql ? sql.hosts() : null;
        return db.submit("claim island " + islandId, () -> {
            if (hosts != null && !hosts.tryAcquireOrRenew(islandId)) {
                String holder = hosts.holderOf(islandId);
                return new Claim(0, null, holder != null ? holder : "?");
            }
            if (!loading) {
                return new Claim(0, null, null);
            }
            return new Claim(storage.claimUnloadStamp(islandId), storage.getIsland(islandId), null);
        }).thenCompose(claim -> claim.hostedOn() == null ? CompletableFuture.completedFuture(claim)
                : CompletableFuture.failedFuture(new IslandHostedElsewhereException(islandId, claim.hostedOn())));
    }

    private void beginClaim(UUID islandId) {
        claiming.merge(islandId, 1, Integer::sum);
    }

    // a failed load or create gives the island back, unless another load here got it loaded meanwhile
    private void endClaim(UUID islandId, String worldName, @Nullable Throwable error) {
        claiming.computeIfPresent(islandId, (id, loads) -> loads > 1 ? loads - 1 : null);
        if (error != null && IslandHostedElsewhereException.find(error) == null) {
            onMain(() -> {
                if (!worlds.isLoaded(worldName) && !claiming.containsKey(islandId)) {
                    releaseHost(islandId);
                }
                return null;
            });
        }
    }

    /** Lets another server host the island at once. Call after its world is unloaded here. */
    public void releaseHost(UUID islandId) {
        if (!(storage instanceof SqlStorage sql)) {
            return;
        }
        plugin.writeAsync(() -> {
            try {
                sql.hosts().release(islandId);
            } catch (java.sql.SQLException e) {
                plugin.getLogger().warning("Could not release island " + islandId + "; another server can host it"
                        + " once the claim expires: " + e.getMessage());
            }
        });
    }

    /**
     * Runs a targeted update of the island on the database thread. Main thread. When nobody else wrote the row
     * in between, the version it returns is marked as seen, so the poll does not reload this server's own
     * change (a reload of a row read before a later local change would briefly undo that change).
     */
    public void writeIsland(Island island, java.util.function.LongSupplier update) {
        DbExecutor db = plugin.db();
        if (db == null) {
            update.getAsLong();
            return;
        }
        db.submit("write island", update::getAsLong).whenCompleteAsync((version, error) -> {
            if (error != null) {
                plugin.getLogger().warning("Could not save island " + island.id() + ": " + error.getMessage());
            } else {
                ownWrite(island, version);
            }
        }, db.mainThread());
    }

    // main thread
    private void ownWrite(Island island, long version) {
        if (version <= 0 || version != island.version() + 1) {
            return;                              // failed, or another server wrote too: let the poll reload it
        }
        island.setVersion(version);
        VersionPoller<UUID> current = poller;
        if (current != null) {
            current.seen(island.id(), version);
        }
    }

    /** Ids of the islands whose world is loaded here. Main thread. */
    public Set<UUID> hostedIslandIds() {
        Set<UUID> hosted = new java.util.HashSet<>();
        for (Island island : byId.values()) {
            if (worlds.isLoaded(island.worldName())) {
                hosted.add(island.id());
            }
        }
        return hosted;
    }

    /**
     * Another server took these islands while this one still had them loaded (its claims lapsed, e.g. during a
     * database outage). The other host's copy wins: move everyone off and unload without saving. Main thread.
     */
    public void hostLost(Set<UUID> islandIds) {
        for (UUID id : islandIds) {
            Island island = byId.get(id);
            if (island == null || !worlds.isLoaded(island.worldName()) || claiming.containsKey(id)) {
                continue;
            }
            plugin.getLogger().severe("Island " + id + " was taken over by another server while loaded here;"
                    + " unloading it without saving, so changes made here since the last save are lost.");
            evacuate(island.worldName(), plugin.messages().raw("island.host-lost"));
            worlds.unloadIsland(island.worldName(), false).whenComplete((ignored, error) -> {
                if (error != null) {
                    plugin.getLogger().severe("Could not unload lost island " + island.worldName() + ": "
                            + error.getMessage());
                }
            });
        }
    }

    // fire IslandCatchupEvent for the downtime since unloadedAt, the stamp this load claimed; main thread
    private void fireCatchup(Island island, World world, long unloadedAt) {
        island.setUnloadedAt(0);
        if (unloadedAt <= 0) {
            return;                             // never unloaded, or another load already paid it
        }
        if (!plugin.conf().getBoolean("simulation.enabled", true)) {
            return;
        }
        long raw = Math.max(0, (System.currentTimeMillis() - unloadedAt) / 1000L);
        if (raw < 60) {
            return;                             // a hub round-trip owes the island nothing
        }
        long cap = Math.max(0, plugin.conf().getLong("simulation.max-offline-hours", 24)) * 3600L;
        long simulated = cap > 0 ? Math.min(raw, cap) : raw;
        if (simulated <= 0) {
            return;
        }
        plugin.getServer().getPluginManager().callEvent(
                new IslandCatchupEvent(island, world, simulated, raw));
    }

    /** Load the island's world (if needed) and teleport the player to its home. */
    public CompletableFuture<Boolean> teleportToIsland(Player player, Island island) {
        return loadWithCatchup(island)
                .thenCompose(world -> onMain(() -> {
                    applyBorder(world, island);
                    teleportTo(player, island);
                    return true;
                }));
    }

    private void teleportTo(Player player, Island island) {
        Location home = island.homeLocation();
        if (home == null) {
            plugin.messages().send(player, "island.world-not-loaded");
            return;
        }
        player.teleport(safeLocation(home));
    }

    /** Load the island world and teleport a visitor to its guest spawn (or home if none is set). */
    public CompletableFuture<Boolean> teleportVisitor(Player player, Island island) {
        return loadWithCatchup(island)
                .thenCompose(world -> onMain(() -> {
                    applyBorder(world, island);
                    Location loc = island.guestOrHomeLocation();
                    if (loc == null) {
                        plugin.messages().send(player, "island.world-not-loaded");
                        return false;
                    }
                    player.teleport(safeLocation(loc));
                    return true;
                }));
    }

    private Location safeLocation(Location base) {
        int scan = plugin.conf().getInt("teleport.safe-scan-height", 8);
        World world = base.getWorld();
        if (world == null) {
            return base;
        }
        int bx = base.getBlockX();
        int bz = base.getBlockZ();
        for (int dy = 0; dy <= scan; dy++) {
            int y = base.getBlockY() + dy;
            Block feet = world.getBlockAt(bx, y, bz);
            Block head = world.getBlockAt(bx, y + 1, bz);
            Block below = world.getBlockAt(bx, y - 1, bz);
            if (feet.getType() == Material.AIR && head.getType() == Material.AIR && below.getType().isSolid()) {
                return new Location(world, bx + 0.5, y, bz + 0.5, base.getYaw(), base.getPitch());
            }
        }
        return base;
    }

    /**
     * Evacuate the island, archive its world to the trash, then remove it from the store and its row.
     * An archive failure aborts the whole delete, and caches are cleared only once everything committed,
     * so a failed delete leaves a working island.
     */
    /**
     * Takes the island's host claim before a delete, so the caller can wait for it before changing anything
     * else: fails with {@link IslandHostedElsewhereException} while another server has it loaded. Completes on
     * the main thread. deleteIsland releases the claim when it finishes.
     */
    public CompletableFuture<Void> claimForDelete(UUID islandId) {
        Island island = getIsland(islandId);
        if (island == null || worlds.isLoaded(island.worldName())) {
            return CompletableFuture.completedFuture(null);
        }
        return claimHost(islandId, false).thenCompose(claim -> onMain(() -> (Void) null));
    }

    public CompletableFuture<Void> deleteIsland(UUID islandId) {
        Island island = getIsland(islandId);
        if (island == null) {
            return CompletableFuture.completedFuture(null);
        }
        String worldName = island.worldName();

        // claimed first: deleting an island another server has loaded would let that server save it back
        CompletableFuture<?> claimed = worlds.isLoaded(worldName) ? CompletableFuture.completedFuture(null)
                : claimHost(islandId, false);
        return claimed.thenCompose(c -> onMain(() -> {
            evacuate(worldName, plugin.messages().raw("delete.evicted"));
            return (Void) null;
        })).thenCompose(ignored -> worlds.unloadIsland(worldName, true))   // final save, then a fresh archive
                .thenCompose(ignored -> runAsyncFuture(() -> {
                    try {
                        trash.archive(worldName, false);
                    } catch (Exception e) {
                        throw new RuntimeException("Could not archive the island before deleting it, so "
                                + "the delete was aborted and the island is untouched: " + e.getMessage(), e);
                    }
                }))
                // Row before world: a row failure aborts with the island intact, and a world failure afterwards only
                // leaves an archived, unreferenced world that /is admin orphans finds.
                .thenCompose(ignored -> runAsyncFuture(() -> {
                    if (!storage.deleteIsland(islandId)) {
                        throw new IllegalStateException("its database row could not be removed, so the delete was"
                                + " aborted and the island is untouched");
                    }
                }))
                .thenRun(() -> {
                    byId.remove(islandId);
                    profileToIsland.remove(island.profileId());
                })
                .thenCompose(ignored -> worlds.deleteIsland(worldName).exceptionally(error -> {
                    plugin.getLogger().warning("Island " + islandId + " is deleted, but its world " + worldName
                            + " could not be removed (" + error.getMessage() + "). It is archived in the trash;"
                            + " /is admin orphans purge will clear it.");
                    return null;
                }))
                .whenComplete((ignored, error) -> {
                    if (error == null || !worlds.isLoaded(worldName)) {
                        releaseHost(islandId);
                    }
                });
    }

    /**
     * Restore archived world bytes into the store under a fresh island id and give the profile a row
     * pointing at it. Home and radius come from config like a new island's; upgrades and level history
     * are not restored.
     */
    public CompletableFuture<Island> restoreIsland(UUID profileId, byte[] worldData) {
        UUID islandId = UUID.randomUUID();
        String prefix = plugin.conf().getString("world.world-name-prefix", "island_");
        String worldName = prefix + islandId;

        ConfigurationSection paste = section("island.paste");
        ConfigurationSection homeOff = section("island.home-offset");
        int px = paste != null ? paste.getInt("x", 0) : 0;
        int py = paste != null ? paste.getInt("y", 100) : 100;
        int pz = paste != null ? paste.getInt("z", 0) : 0;
        double hx = px + (homeOff != null ? homeOff.getInt("x", 0) : 0) + 0.5;
        double hy = py + (homeOff != null ? homeOff.getInt("y", 1) : 1);
        double hz = pz + (homeOff != null ? homeOff.getInt("z", 0) : 0) + 0.5;
        float yaw = homeOff != null ? (float) homeOff.getDouble("yaw", 0) : 0f;
        float pitch = homeOff != null ? (float) homeOff.getDouble("pitch", 0) : 0f;
        int startingRadius = plugin.conf().getInt("island.starting-radius", 50);
        long now = Instant.now().toEpochMilli();

        Island island = new Island(islandId, profileId, worldName, now);
        island.setRadius(startingRadius);
        island.setHome(hx, hy, hz, yaw, pitch);
        // claimed like a new island, so no other server can load the id before its row exists
        beginClaim(islandId);
        return claimHost(islandId, false)
                .thenCompose(claim -> runAsyncFuture(() -> {
                    try {
                        worlds.importWorld(worldName, worldData);
                    } catch (Exception e) {
                        throw new RuntimeException("Could not import the archived world: " + e.getMessage(), e);
                    }
                }))
                .thenCompose(imported -> runAsyncFuture(() -> {
                    if (!storage.insertIsland(island)) {
                        throw new IllegalStateException("the restored island could not be saved to the database");
                    }
                }).exceptionallyCompose(error -> worlds.deleteIsland(worldName)
                        .handle((ignored, cleanupError) -> null)
                        .thenCompose(ignored -> CompletableFuture.<Void>failedFuture(error))))
                .thenCompose(ignored -> onMain(() -> {
                    cache(island);
                    return island;
                }))
                .whenComplete((restored, error) -> {
                    endClaim(islandId, worldName, error);
                    if (error == null) {
                        // imported, not loaded: free for whichever server loads it first
                        onMain(() -> {
                            if (!worlds.isLoaded(worldName) && !claiming.containsKey(islandId)) {
                                releaseHost(islandId);
                            }
                            return null;
                        });
                    }
                });
    }

    /** Move everyone in the named world to the configured spawn/hub. Main thread only. */
    public void evacuate(String worldName, String message) {
        World world = plugin.getServer().getWorld(worldName);
        if (world == null || world.getPlayers().isEmpty()) {
            return;
        }
        Location fallback = resolveSpawnLocation();
        for (Player online : new ArrayList<>(world.getPlayers())) {
            if (fallback != null) {
                online.teleport(fallback);
            }
            if (message != null && !message.isEmpty()) {
                online.sendMessage(com.mystipixel.royalskyblock.util.Text.color(message));
            }
        }
    }

    public @Nullable Location resolveSpawnLocation() {
        String worldName = plugin.conf().getString("spawn.world", "world");
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            List<World> worldList = plugin.getServer().getWorlds();
            if (worldList.isEmpty()) {
                return null;
            }
            plugin.getLogger().warning("spawn.world '" + worldName + "' is not loaded; using '"
                    + worldList.get(0).getName() + "' spawn instead.");
            return worldList.get(0).getSpawnLocation();
        }
        if (plugin.conf().getBoolean("spawn.use-world-spawn", true)) {
            return world.getSpawnLocation();
        }
        return new Location(world,
                plugin.conf().getDouble("spawn.x", 0.5),
                plugin.conf().getDouble("spawn.y", 100.0),
                plugin.conf().getDouble("spawn.z", 0.5),
                (float) plugin.conf().getDouble("spawn.yaw", 0.0),
                (float) plugin.conf().getDouble("spawn.pitch", 0.0));
    }

    /** Send a player to the configured spawn/hub. Main thread only. */
    public void sendToSpawn(Player player) {
        Location spawn = resolveSpawnLocation();
        if (spawn != null) {
            player.teleport(spawn);
        }
    }

    private void applyBorder(World world, Island island) {
        // borders are per-player (see BorderService); the world's own border stays wide open
        world.getWorldBorder().setSize(59_999_968.0); // Bukkit's max world-border size
        plugin.borders().applyToWorld(world);
    }

    private @Nullable ConfigurationSection section(String path) {
        return plugin.conf().getConfigurationSection(path);
    }


    private CompletableFuture<Void> runAsyncFuture(Runnable runnable) {
        return CompletableFuture.runAsync(runnable,
                r -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, r));
    }

    private <T> CompletableFuture<T> onMain(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
        return future;
    }
}
