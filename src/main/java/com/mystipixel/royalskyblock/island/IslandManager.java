package com.mystipixel.royalskyblock.island;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.data.IslandDatabase;
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
 * The island lifecycle: create, resolve, teleport home, and delete. Ties together the metadata store
 * ({@link IslandDatabase}), the world backend ({@link IslandWorldService}) and the starter builder,
 * and keeps a small in-memory cache so hot lookups don't hit the DB.
 *
 * <p>World and player touches happen on the server thread; DB and slime I/O happen off it. The public
 * methods return futures that complete once the whole flow is done.
 */
public final class IslandManager {

    private final RoyalSkyblockPlugin plugin;
    private final IslandDatabase database;
    private final IslandWorldService worlds;

    private final Map<UUID, Island> byId = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerIndex = new ConcurrentHashMap<>(); // player -> island id

    public IslandManager(RoyalSkyblockPlugin plugin, IslandDatabase database, IslandWorldService worlds) {
        this.plugin = plugin;
        this.database = database;
        this.worlds = worlds;
    }

    // ── lookups ─────────────────────────────────────────────────────────────────

    /** The island {@code player} belongs to (as owner or member), loading it from the DB if needed. */
    public @Nullable Island getPlayerIsland(UUID player) {
        UUID cached = playerIndex.get(player);
        if (cached != null) {
            Island island = getIsland(cached);
            if (island != null && island.isMember(player)) {
                return island;
            }
            playerIndex.remove(player);
        }
        UUID id = database.getIslandIdByMember(player);
        return id != null ? getIsland(id) : null;
    }

    /**
     * Resolve which island a world belongs to. Because every island is its own world named
     * {@code <prefix><islandId>}, this is a direct parse — no region lookup. Returns {@code null} for
     * non-island worlds (the hub, the overworld, etc.).
     */
    public @Nullable Island getIslandByWorld(World world) {
        if (world == null) {
            return null;
        }
        String prefix = plugin.getConfig().getString("world.world-name-prefix", "island_");
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

    /** Fetch an island by id, from cache or the DB. */
    public @Nullable Island getIsland(UUID id) {
        Island cached = byId.get(id);
        if (cached != null) {
            return cached;
        }
        Island loaded = database.getIsland(id);
        if (loaded != null) {
            cache(loaded);
        }
        return loaded;
    }

    public boolean hasIsland(UUID player) {
        return playerIndex.containsKey(player) || database.getIslandIdByMember(player) != null;
    }

    private void cache(Island island) {
        byId.put(island.id(), island);
        for (IslandMember member : island.members()) {
            playerIndex.put(member.uuid(), island.id());
        }
    }

    // ── create ──────────────────────────────────────────────────────────────────

    /**
     * Create a brand-new island for {@code player}: allocate a slime world, paste the starter island,
     * persist metadata, and teleport them home. Fails (completes exceptionally) if they already have one.
     */
    public CompletableFuture<Island> createIsland(Player player) {
        UUID pid = player.getUniqueId();
        if (hasIsland(pid)) {
            return CompletableFuture.failedFuture(new IllegalStateException("You already have an island."));
        }

        UUID islandId = UUID.randomUUID();
        String prefix = plugin.getConfig().getString("world.world-name-prefix", "island_");
        String worldName = prefix + islandId;

        ConfigurationSection paste = section("island.paste");
        ConfigurationSection homeOff = section("island.home-offset");
        int px = paste != null ? paste.getInt("x", 0) : 0;
        int py = paste != null ? paste.getInt("y", 100) : 100;
        int pz = paste != null ? paste.getInt("z", 0) : 0;

        int startingRadius = plugin.getConfig().getInt("island.starting-radius", 50);
        long now = Instant.now().toEpochMilli();

        return worlds.createIsland(worldName)
                .thenCompose(world -> onMain(() -> {
                    StarterIslandBuilder.paste(world, px, py, pz,
                            section("island.starter"), plugin.getLogger());

                    double hx = px + (homeOff != null ? homeOff.getInt("x", 0) : 0) + 0.5;
                    double hy = py + (homeOff != null ? homeOff.getInt("y", 1) : 1);
                    double hz = pz + (homeOff != null ? homeOff.getInt("z", 0) : 0) + 0.5;
                    float yaw = homeOff != null ? (float) homeOff.getDouble("yaw", 0) : 0f;
                    float pitch = homeOff != null ? (float) homeOff.getDouble("pitch", 0) : 0f;
                    world.setSpawnLocation((int) hx, (int) hy, (int) hz);

                    Island island = new Island(islandId, pid, worldName, now);
                    island.setRadius(startingRadius);
                    island.setHome(hx, hy, hz, yaw, pitch);
                    island.putMember(new IslandMember(pid, player.getName(), IslandRole.OWNER, now));
                    applyBorder(world, island);
                    return island;
                }))
                .thenApply(island -> {
                    cache(island);
                    // Persist the pasted blocks and the metadata, then teleport home.
                    worlds.saveIsland(worldName);
                    CompletableFuture.runAsync(() -> database.saveIsland(island),
                            r -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, r));
                    onMain(() -> {
                        teleportTo(player, island);
                        return null;
                    });
                    return island;
                });
    }

    // ── teleport ─────────────────────────────────────────────────────────────────

    /**
     * Send {@code player} to their island home, loading the island world first if it isn't loaded.
     * Completes with {@code false} if they have no island.
     */
    public CompletableFuture<Boolean> goHome(Player player) {
        Island island = getPlayerIsland(player.getUniqueId());
        if (island == null) {
            return CompletableFuture.completedFuture(false);
        }
        return worlds.loadIsland(island.worldName())
                .thenCompose(world -> onMain(() -> {
                    applyBorder(world, island);
                    teleportTo(player, island);
                    return true;
                }));
    }

    /**
     * Send {@code visitor} to another player's island (as a visitor — build protection applies),
     * loading the world first. Completes with {@code false} if the target has no island.
     */
    public CompletableFuture<Boolean> visit(Player visitor, UUID targetPlayer) {
        Island island = getPlayerIsland(targetPlayer);
        if (island == null) {
            return CompletableFuture.completedFuture(false);
        }
        return worlds.loadIsland(island.worldName())
                .thenCompose(world -> onMain(() -> {
                    applyBorder(world, island);
                    teleportTo(visitor, island);
                    return true;
                }));
    }

    /**
     * Apply a world border at the island edge: centred on the island's paste origin, sized to its
     * radius. No-op if borders are disabled in config. Main thread only.
     */
    private void applyBorder(World world, Island island) {
        if (!plugin.getConfig().getBoolean("island.border.enabled", true)) {
            return;
        }
        ConfigurationSection paste = section("island.paste");
        double cx = (paste != null ? paste.getInt("x", 0) : 0) + 0.5;
        double cz = (paste != null ? paste.getInt("z", 0) : 0) + 0.5;
        org.bukkit.WorldBorder border = world.getWorldBorder();
        border.setCenter(cx, cz);
        border.setSize(Math.max(1.0, island.radius() * 2.0));
        border.setWarningDistance(Math.max(0, plugin.getConfig().getInt("island.border.warning-blocks", 2)));
        border.setDamageAmount(0.0);
        border.setDamageBuffer(0.0);
    }

    /** Teleport a player to an island home, nudging them upward to a safe spot. Main thread only. */
    private void teleportTo(Player player, Island island) {
        Location home = island.homeLocation();
        if (home == null) {
            player.sendMessage("§cYour island world isn't loaded yet — try again in a moment.");
            return;
        }
        player.teleport(safeLocation(home));
    }

    /** Scan upward from {@code base} for a spot with solid footing and two air blocks. */
    private Location safeLocation(Location base) {
        int scan = plugin.getConfig().getInt("teleport.safe-scan-height", 8);
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
     * Permanently delete {@code player}'s island: evict cache, drop metadata, and remove the slime
     * world. Only the owner may delete. Completes with {@code false} if they own no island.
     */
    public CompletableFuture<Boolean> deleteOwnIsland(Player player) {
        Island island = getPlayerIsland(player.getUniqueId());
        if (island == null || !island.owner().equals(player.getUniqueId())) {
            return CompletableFuture.completedFuture(false);
        }
        UUID islandId = island.id();
        String worldName = island.worldName();

        byId.remove(islandId);
        playerIndex.values().removeIf(id -> id.equals(islandId));

        // Evacuate everyone still on the island BEFORE unloading it — Bukkit.unloadWorld() fails
        // while players are inside, which would otherwise leave the deleted world lingering in memory.
        return onMain(() -> {
            evacuate(worldName, "&eThe island you were on has been deleted.");
            return null;
        }).thenCompose(ignored -> worlds.deleteIsland(worldName))
                .thenCompose(ignored -> CompletableFuture.runAsync(
                        () -> database.deleteIsland(islandId),
                        r -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, r)))
                .thenApply(ignored -> true);
    }

    /**
     * Move every player currently in the named world to the configured spawn/hub. Main thread only.
     * Used before an island world is unloaded or deleted so nobody is stranded in it.
     */
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

    /**
     * The configured spawn/hub location: the {@code spawn.world}'s own spawn, or explicit coords.
     * Falls back to the primary world's spawn if the configured world isn't loaded.
     */
    public @Nullable Location resolveSpawnLocation() {
        String worldName = plugin.getConfig().getString("spawn.world", "world");
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            List<World> worldList = plugin.getServer().getWorlds();
            if (worldList.isEmpty()) {
                return null;
            }
            plugin.getLogger().warning("spawn.world '" + worldName + "' is not loaded; using '"
                    + worldList.get(0).getName() + "' spawn instead.");
            world = worldList.get(0);
            return world.getSpawnLocation();
        }
        if (plugin.getConfig().getBoolean("spawn.use-world-spawn", true)) {
            return world.getSpawnLocation();
        }
        return new Location(world,
                plugin.getConfig().getDouble("spawn.x", 0.5),
                plugin.getConfig().getDouble("spawn.y", 100.0),
                plugin.getConfig().getDouble("spawn.z", 0.5),
                (float) plugin.getConfig().getDouble("spawn.yaw", 0.0),
                (float) plugin.getConfig().getDouble("spawn.pitch", 0.0));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private @Nullable ConfigurationSection section(String path) {
        return plugin.getConfig().getConfigurationSection(path);
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
