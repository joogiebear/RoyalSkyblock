package com.mystipixel.royalskyblock;

import com.mystipixel.royalskyblock.command.IslandCommand;
import com.mystipixel.royalskyblock.data.IslandDatabase;
import com.mystipixel.royalskyblock.island.IslandManager;
import com.mystipixel.royalskyblock.listener.ProtectionListener;
import com.mystipixel.royalskyblock.world.IslandWorldService;
import com.mystipixel.royalskyblock.world.NoOpIslandWorldService;
import com.mystipixel.royalskyblock.world.asp.AspIslandWorldService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * RoyalSkyblock entry point.
 *
 * <p>Scalable per-island Skyblock: every island is its own Advanced Slime Paper world, loaded on
 * demand and unloaded when empty, with island metadata in the shared dual-dialect (SQLite/MySQL)
 * store. Part of the Royal plugin suite and built to the same eco-style config conventions.
 */
public final class RoyalSkyblockPlugin extends JavaPlugin {

    private static RoyalSkyblockPlugin instance;

    private IslandDatabase database;
    private IslandWorldService worldService;
    private IslandManager islandManager;

    /** True once the ASP world backend initialised. When false, island world ops are unavailable. */
    private volatile boolean worldBackendReady;

    public static RoyalSkyblockPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.database = new IslandDatabase(this);
        if (!database.connect()) {
            getLogger().severe("Storage failed to initialise — disabling RoyalSkyblock.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.worldService = aspAvailable() ? new AspIslandWorldService(this) : new NoOpIslandWorldService();
        this.islandManager = new IslandManager(this, database, worldService);

        // Bring up the world backend asynchronously. If the server isn't running ASP, keep the plugin
        // enabled but flag island world ops as unavailable so commands can explain clearly.
        worldService.initialize().whenComplete((ignored, error) -> {
            if (error != null) {
                getLogger().severe("Island world backend unavailable: " + rootMessage(error));
                getLogger().severe("Island creation/teleport is disabled until the server runs Advanced Slime Paper.");
                worldBackendReady = false;
            } else {
                worldBackendReady = true;
            }
        });

        registerCommands();
        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);

        getLogger().info("RoyalSkyblock enabled — metadata store: "
                + getConfig().getString("storage.type", "sqlite").toUpperCase()
                + ", island world source: " + getConfig().getString("world.slime-data-source", "file") + ".");
    }

    @Override
    public void onDisable() {
        if (worldService != null) {
            worldService.shutdown();
        }
        if (database != null) {
            database.close();
        }
        getLogger().info("RoyalSkyblock disabled.");
        instance = null;
    }

    /** Reload config from disk. Extended in later phases to re-read levels, upgrades and menus. */
    public void reload() {
        reloadConfig();
    }

    private void registerCommands() {
        IslandCommand island = new IslandCommand(this);
        PluginCommand command = getCommand("island");
        if (command != null) {
            command.setExecutor(island);
            command.setTabCompleter(island);
        } else {
            getLogger().warning("Command 'island' missing from plugin.yml — command not registered.");
        }
    }

    /** Whether the ASP world API is on the classpath (i.e. the server is the ASP fork). */
    private boolean aspAvailable() {
        try {
            Class.forName("com.infernalsuite.asp.api.AdvancedSlimePaperAPI", false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException notAsp) {
            return false;
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    public IslandManager islands() {
        return islandManager;
    }

    public IslandWorldService worlds() {
        return worldService;
    }

    public IslandDatabase database() {
        return database;
    }

    public boolean isWorldBackendReady() {
        return worldBackendReady;
    }
}
