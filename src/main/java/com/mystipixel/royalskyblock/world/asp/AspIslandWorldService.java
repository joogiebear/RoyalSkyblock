package com.mystipixel.royalskyblock.world.asp;

import com.infernalsuite.asp.api.AdvancedSlimePaperAPI;
import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.loaders.SlimeLoader;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.SlimeWorldInstance;
import com.infernalsuite.asp.api.world.properties.SlimeProperties;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.world.IslandWorldService;
import com.mystipixel.royalskyblock.world.asp.loaders.RsbFileLoader;
import com.mystipixel.royalskyblock.world.asp.loaders.RsbMysqlLoader;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * The one and only Advanced Slime Paper adapter. Everything ASP-specific lives here; the rest of the
 * plugin sees only {@link IslandWorldService}.
 *
 * <p>Threading contract from ASP: {@code createEmptyWorld}/{@code readWorld}/{@code saveWorld} may run
 * off the main thread (and should, since they hit the data source), while {@code loadWorld} and any
 * Bukkit world touch must run on the server thread. This class hops threads accordingly and hands
 * callers a plain Bukkit {@link World}.
 */
public final class AspIslandWorldService implements IslandWorldService {

    private final RoyalSkyblockPlugin plugin;
    private final Executor async;

    private AdvancedSlimePaperAPI asp;
    private SlimeLoader loader;

    public AspIslandWorldService(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.async = runnable -> Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                this.asp = AdvancedSlimePaperAPI.instance();
            } catch (Throwable t) {
                throw new IllegalStateException(
                        "Advanced Slime Paper API not found. RoyalSkyblock requires the server to run "
                                + "the ASP fork (aspaper), not vanilla Paper.", t);
            }
            this.loader = buildLoader();
            plugin.getLogger().info("Island world backend ready (ASP, source="
                    + plugin.getConfig().getString("world.slime-data-source", "file") + ").");
        }, async);
    }

    @Override
    public CompletableFuture<World> createIsland(String worldName) {
        SlimePropertyMap props = buildProperties();
        return CompletableFuture
                // create the empty in-memory world off-thread...
                .supplyAsync(() -> asp.createEmptyWorld(worldName, false, props, loader), async)
                // ...load it into the server on the main thread...
                .thenCompose(slime -> onMain(() -> asp.loadWorld(slime, true)))
                // ...then persist the fresh world (awaited, so a later delete/unload can't race an
                // in-flight save) before handing back the Bukkit world.
                .thenCompose(instance -> CompletableFuture
                        .runAsync(() -> save(instance), async)
                        .thenApply(ignored -> instance.getBukkitWorld()));
    }

    @Override
    public CompletableFuture<World> loadIsland(String worldName) {
        // Already loaded? Hand back the live world.
        SlimeWorldInstance loaded = asp.getLoadedWorld(worldName);
        if (loaded != null) {
            return CompletableFuture.completedFuture(loaded.getBukkitWorld());
        }
        SlimePropertyMap props = buildProperties();
        return CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return asp.readWorld(loader, worldName, false, props);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to read island world '" + worldName + "'", e);
                    }
                }, async)
                .thenCompose(slime -> onMain(() -> asp.loadWorld(slime, true)))
                .thenApply(SlimeWorldInstance::getBukkitWorld);
    }

    @Override
    public CompletableFuture<Void> saveIsland(String worldName) {
        SlimeWorldInstance instance = asp.getLoadedWorld(worldName);
        if (instance == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> save(instance), async);
    }

    @Override
    public CompletableFuture<Void> unloadIsland(String worldName, boolean save) {
        SlimeWorldInstance instance = asp.getLoadedWorld(worldName);
        if (instance == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> saved = save
                ? CompletableFuture.runAsync(() -> save(instance), async)
                : CompletableFuture.completedFuture(null);
        return saved.thenCompose(ignored -> onMain(() -> {
            World world = instance.getBukkitWorld();
            // We handle persistence via ASP above, so never let Bukkit double-save here.
            Bukkit.unloadWorld(world, false);
            return null;
        }));
    }

    @Override
    public CompletableFuture<Void> deleteIsland(String worldName) {
        return unloadIsland(worldName, false).thenCompose(ignored ->
                CompletableFuture.runAsync(() -> {
                    try {
                        loader.deleteWorld(worldName);
                    } catch (UnknownWorldException ignored2) {
                        // Already gone — nothing to delete.
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to delete island world '" + worldName + "'", e);
                    }
                }, async));
    }

    @Override
    public boolean isLoaded(String worldName) {
        return asp != null && asp.getLoadedWorld(worldName) != null;
    }

    @Override
    public void shutdown() {
        // ASP handles final world flushing on server stop. Close our loader's own resources (e.g. the
        // MySQL slime pool) if it holds any.
        if (loader instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                plugin.getLogger().warning("Error closing slime loader: " + e.getMessage());
            }
        }
        this.asp = null;
        this.loader = null;
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void save(SlimeWorld world) {
        try {
            asp.saveWorld(world);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save island world '" + world.getName() + "': " + e.getMessage());
        }
    }

    /** Build the ASP data-source loader from config. Called once during {@link #initialize()}. */
    private SlimeLoader buildLoader() {
        String source = plugin.getConfig().getString("world.slime-data-source", "file").toLowerCase();
        switch (source) {
            case "mysql" -> {
                ConfigurationSection my = plugin.getConfig().getConfigurationSection("storage.mysql");
                if (my == null) {
                    throw new IllegalStateException("world.slime-data-source is 'mysql' but storage.mysql is missing.");
                }
                try {
                    return new RsbMysqlLoader(
                            my.getString("host", "localhost"),
                            my.getInt("port", 3306),
                            my.getString("database", "royalskyblock"),
                            my.getString("username", "root"),
                            my.getString("password", ""),
                            my.getString("properties", "useSSL=false"),
                            my.getInt("pool-size", 10));
                } catch (Exception e) {
                    throw new IllegalStateException("Could not connect the RoyalSkyblock MySQL slime loader.", e);
                }
            }
            case "mongo" -> throw new IllegalStateException(
                    "world.slime-data-source 'mongo' is not wired yet — use 'file' or 'mysql'.");
            default -> {
                File dir = new File(plugin.getDataFolder(), "slime-worlds");
                return new RsbFileLoader(dir);
            }
        }
    }

    /** Build the world property map for new/loaded island worlds from config. */
    private SlimePropertyMap buildProperties() {
        SlimePropertyMap map = new SlimePropertyMap();
        ConfigurationSection p = plugin.getConfig().getConfigurationSection("world.properties");
        ConfigurationSection paste = plugin.getConfig().getConfigurationSection("island.paste");

        map.setValue(SlimeProperties.DIFFICULTY, p != null ? p.getString("difficulty", "normal") : "normal");
        map.setValue(SlimeProperties.ALLOW_MONSTERS, p == null || p.getBoolean("allow-monsters", true));
        map.setValue(SlimeProperties.ALLOW_ANIMALS, p == null || p.getBoolean("allow-animals", true));
        map.setValue(SlimeProperties.PVP, p != null && p.getBoolean("pvp", false));
        map.setValue(SlimeProperties.ENVIRONMENT, p != null ? p.getString("environment", "normal") : "normal");
        map.setValue(SlimeProperties.WORLD_TYPE, p != null ? p.getString("world-type", "default") : "default");
        map.setValue(SlimeProperties.DEFAULT_BIOME, p != null ? p.getString("default-biome", "minecraft:plains") : "minecraft:plains");

        if (paste != null) {
            map.setValue(SlimeProperties.SPAWN_X, paste.getInt("x", 0));
            map.setValue(SlimeProperties.SPAWN_Y, paste.getInt("y", 100));
            map.setValue(SlimeProperties.SPAWN_Z, paste.getInt("z", 0));
        }
        return map;
    }

    /** Run {@code supplier} on the server thread, completing the returned future with its result. */
    private <T> CompletableFuture<T> onMain(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
        return future;
    }
}
