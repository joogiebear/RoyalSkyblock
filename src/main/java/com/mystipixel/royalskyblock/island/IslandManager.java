package com.mystipixel.royalskyblock.island;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
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

    private final Map<UUID, Island> byId = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> profileToIsland = new ConcurrentHashMap<>();

    public IslandManager(RoyalSkyblockPlugin plugin, Storage storage, IslandWorldService worlds) {
        this.plugin = plugin;
        this.storage = storage;
        this.worlds = worlds;
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

    private void cache(Island island) {
        byId.put(island.id(), island);
        profileToIsland.put(island.profileId(), island.id());
    }

    // ── create ──────────────────────────────────────────────────────────────────

    /** Get the profile's island, creating (world + starter) it if it doesn't exist yet. */
    public CompletableFuture<Island> ensureIsland(UUID profileId) {
        Island existing = getIslandByProfile(profileId);
        if (existing != null) {
            return CompletableFuture.completedFuture(existing);
        }
        return createIslandForProfile(profileId);
    }

    /** Allocate a fresh slime world + starter island for a profile and persist it. Does not teleport. */
    public CompletableFuture<Island> createIslandForProfile(UUID profileId) {
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
                    StarterIslandBuilder.paste(world, px, py, pz, section("island.starter"), plugin.getLogger());

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

    /** Load the island's world (if needed) and teleport the player to its home. */
    public CompletableFuture<Boolean> teleportToIsland(Player player, Island island) {
        return worlds.loadIsland(island.worldName())
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

    /** Evacuate anyone on the island, then remove its world and metadata row. */
    public CompletableFuture<Void> deleteIsland(UUID islandId) {
        Island island = getIsland(islandId);
        if (island == null) {
            return CompletableFuture.completedFuture(null);
        }
        String worldName = island.worldName();
        byId.remove(islandId);
        profileToIsland.remove(island.profileId());

        return onMain(() -> {
            evacuate(worldName, plugin.messages().raw("delete.evicted"));
            return null;
        }).thenCompose(ignored -> worlds.deleteIsland(worldName))
                .thenCompose(ignored -> runAsyncFuture(() -> storage.deleteIsland(islandId)));
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
        String worldName = plugin.getConfig().getString("spawn.world", "world");
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

    /** Send a player to the configured spawn/hub. Main thread only. */
    public void sendToSpawn(Player player) {
        Location spawn = resolveSpawnLocation();
        if (spawn != null) {
            player.teleport(spawn);
        }
    }

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

    // ── helpers ────────────────────────────────────────────────────────────────────

    private @Nullable ConfigurationSection section(String path) {
        return plugin.getConfig().getConfigurationSection(path);
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
