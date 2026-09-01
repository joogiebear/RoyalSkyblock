package com.mystipixel.royalskyblock.island;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.api.IslandCatchupEvent;
import com.mystipixel.royalskyblock.data.Storage;
import com.mystipixel.royalskyblock.world.IslandWorldService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Manages islands, which now belong to <em>profiles</em> rather than players. Creates the slime world +
 * starter island, teleports players in, and deletes islands. Player-facing flows (whose island, who
 * may build) resolve through {@link com.mystipixel.royalskyblock.profile.ProfileManager}.
 */
public final class IslandManager {

    private final RoyalSkyblockPlugin plugin;
    private final Storage storage;
    private final IslandWorldService worlds;
    private final com.mystipixel.royalskyblock.world.IslandTrash trash;

    private final Map<UUID, Island> byId = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> profileToIsland = new ConcurrentHashMap<>();
    /** In-flight creations, so two rapid ensureIsland calls can't both allocate a world. */
    private final Map<UUID, CompletableFuture<Island>> creating = new ConcurrentHashMap<>();

    public IslandManager(RoyalSkyblockPlugin plugin, Storage storage, IslandWorldService worlds) {
        this.plugin = plugin;
        this.storage = storage;
        this.worlds = worlds;
        this.trash = new com.mystipixel.royalskyblock.world.IslandTrash(plugin);
        // Retention pruning, shortly after startup and daily after — the trash must not become the
        // unbounded island graveyard it exists to prevent worlds becoming.
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, trash::pruneOld,
                20L * 120L, 20L * 60L * 60L * 24L);
    }

    /** The island trash can — where deleted worlds go instead of oblivion. */
    public com.mystipixel.royalskyblock.world.IslandTrash trash() {
        return trash;
    }

    // ── lookups ─────────────────────────────────────────────────────────────────

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

    public @Nullable Island getIslandByProfile(UUID profileId) {
        UUID cached = profileToIsland.get(profileId);
        if (cached != null) {
            return getIsland(cached);
        }
        Island island = storage.getIslandByProfile(profileId);
        if (island != null) {
            cache(island);
        }
        return island;
    }

    /**
     * Resolve which island a world belongs to. Every island is its own world named
     * {@code <prefix><islandId>}, so this is a direct parse — no region lookup.
     */
    public @Nullable Island getIslandByWorld(World world) {
        if (world == null) {
            return null;
        }
        String prefix = plugin.conf().getString("world.world-name-prefix", "island_");
        String name = world.getName();
        if (!name.startsWith(prefix)) {
            return null;
        }
        try {
            return getIsland(UUID.fromString(name.substring(prefix.length())));
        } catch (IllegalArgumentException notAnIslandWorld) {
            return null;
        }
    }

    private void cache(Island island) {
        byId.put(island.id(), island);
        profileToIsland.put(island.profileId(), island.id());
    }

    // ── create ──────────────────────────────────────────────────────────────────

    /**
     * Get the profile's island, creating (world + starter) it if it doesn't exist yet.
     *
     * <p>Creation is deduplicated per profile: a second call arriving while the first is still building
     * (a double-clicked switch button, say) joins the in-flight future instead of allocating a second
     * world that nothing would ever reference again.
     */
    public CompletableFuture<Island> ensureIsland(UUID profileId) {
        Island existing = getIslandByProfile(profileId);
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

        return worlds.createIsland(worldName)
                .thenCompose(world -> onMain(() -> {
                    // Prefer a WorldEdit/FAWE schematic; fall back to the code-generated starter.
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
                .thenApply(island -> {
                    cache(island);
                    worlds.saveIsland(worldName);
                    runAsync(() -> storage.saveIsland(island));
                    return island;
                });
    }

    // ── teleport ─────────────────────────────────────────────────────────────────

    /**
     * Load an island's world and settle the time it spent unloaded. Every path that brings an island
     * back must go through here, not {@code worlds.loadIsland} directly — an island loaded without
     * its catch-up silently loses whatever should have happened while it slept.
     */
    private CompletableFuture<World> loadWithCatchup(Island island) {
        return worlds.loadIsland(island.worldName()).thenCompose(world -> onMain(() -> {
            plugin.unloads().forget(island.worldName());
            plugin.worldRules().applyGameRules(world);
            fireCatchup(island, world);
            return world;
        }));
    }

    /**
     * Fire {@link IslandCatchupEvent} for the offline window, then clear the stamp so a second load
     * can't pay the same time twice. Main thread.
     */
    private void fireCatchup(Island island, World world) {
        long unloadedAt = island.unloadedAt();
        if (unloadedAt <= 0) {
            return;                             // never unloaded, or already settled
        }
        island.setUnloadedAt(0);
        runAsync(() -> storage.saveIsland(island));

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

    // ── delete ────────────────────────────────────────────────────────────────────

    /**
     * Evacuate anyone on the island, archive its world to the trash, then remove it from the store
     * and its metadata row.
     *
     * <p>The world is saved before archiving so the trash holds its final state, and an archive
     * failure aborts the whole delete — an island that could not be archived stays an island,
     * because the alternative is exactly the unrecoverable loss the trash exists to prevent. The
     * caches are cleared only once everything committed, so an aborted delete leaves a working
     * island rather than a ghost.
     */
    public CompletableFuture<Void> deleteIsland(UUID islandId) {
        Island island = getIsland(islandId);
        if (island == null) {
            return CompletableFuture.completedFuture(null);
        }
        String worldName = island.worldName();

        return onMain(() -> {
            evacuate(worldName, plugin.messages().raw("delete.evicted"));
            return (Void) null;
        }).thenCompose(ignored -> worlds.unloadIsland(worldName, true))   // final save → fresh archive
                .thenCompose(ignored -> runAsyncFuture(() -> {
                    try {
                        trash.archive(worldName, false);
                    } catch (Exception e) {
                        throw new RuntimeException("Could not archive the island before deleting it — "
                                + "the delete was aborted and the island is untouched: " + e.getMessage(), e);
                    }
                }))
                .thenCompose(ignored -> worlds.deleteIsland(worldName))
                .thenCompose(ignored -> runAsyncFuture(() -> storage.deleteIsland(islandId)))
                .thenRun(() -> {
                    byId.remove(islandId);
                    profileToIsland.remove(island.profileId());
                });
    }

    /**
     * The restore half of the trash: write archived world bytes into the store under a fresh island
     * id and give the profile a row pointing at it. Home and radius come from config exactly like a
     * new island's — the upgrades and level history lived in rows that died with the old island, but
     * the blocks are the part that cannot be re-earned by clicking.
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

        return runAsyncFuture(() -> {
            try {
                worlds.importWorld(worldName, worldData);
            } catch (Exception e) {
                throw new RuntimeException("Could not import the archived world: " + e.getMessage(), e);
            }
        }).thenApply(ignored -> {
            Island island = new Island(islandId, profileId, worldName, now);
            island.setRadius(startingRadius);
            island.setHome(hx, hy, hz, yaw, pitch);
            cache(island);
            runAsync(() -> storage.saveIsland(island));
            return island;
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
        // Borders are enforced per-player (so admins with royalskyblock.bypass can pass through); the
        // world's own border is kept wide open so it never enforces anyone. See BorderService.
        world.getWorldBorder().setSize(59_999_968.0); // Bukkit's max world-border size
        plugin.borders().applyToWorld(world);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private @Nullable ConfigurationSection section(String path) {
        return plugin.conf().getConfigurationSection(path);
    }

    private void runAsync(Runnable runnable) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable);
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
