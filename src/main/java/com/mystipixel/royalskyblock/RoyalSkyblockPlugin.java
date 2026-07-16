package com.mystipixel.royalskyblock;

import com.mystipixel.royalskyblock.command.IslandCommand;
import com.mystipixel.royalskyblock.data.Storage;
import com.mystipixel.royalskyblock.gui.GuiManager;
import com.mystipixel.royalskyblock.hooks.EcoProfileBridge;
import com.mystipixel.royalskyblock.island.IslandManager;
import com.mystipixel.royalskyblock.island.NoOpSchematics;
import com.mystipixel.royalskyblock.island.SchematicService;
import com.mystipixel.royalskyblock.island.WorldEditSchematics;
import com.mystipixel.royalskyblock.listener.ProfileListener;
import com.mystipixel.royalskyblock.listener.ProtectionListener;
import com.mystipixel.royalskyblock.listener.CommandGateListener;
import com.mystipixel.royalskyblock.message.MessageManager;
import com.mystipixel.royalskyblock.profile.GamemodeManager;
import com.mystipixel.royalskyblock.profile.PlayerStateService;
import com.mystipixel.royalskyblock.profile.ProfileManager;
import com.mystipixel.royalskyblock.world.IslandWorldService;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    private Storage storage;
    private IslandWorldService worldService;
    private IslandManager islandManager;
    private SchematicService schematicService;
    private ProfileManager profileManager;
    private PlayerStateService stateService;
    private GamemodeManager gamemodeManager;
    private EcoProfileBridge ecoBridge;
    private MessageManager messageManager;
    private GuiManager guiManager;

    /** SPIKE: which eco test-slot each player is currently on (defaults to 1). Removed once the real
     *  profile system lands. */
    private final Map<UUID, Integer> ecoSlot = new ConcurrentHashMap<>();

    /** True once the ASP world backend initialised. When false, island world ops are unavailable. */
    private volatile boolean worldBackendReady;

    public static RoyalSkyblockPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        this.messageManager = new MessageManager(this);

        this.storage = new Storage(this);
        if (!storage.connect()) {
            getLogger().severe("Storage failed to initialise — disabling RoyalSkyblock.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.ecoBridge = new EcoProfileBridge();
        this.worldService = aspAvailable() ? new AspIslandWorldService(this) : new NoOpIslandWorldService();
        this.schematicService = worldEditAvailable() ? new WorldEditSchematics(this) : new NoOpSchematics();
        this.islandManager = new IslandManager(this, storage, worldService);
        this.stateService = new PlayerStateService(this, storage);
        this.gamemodeManager = new GamemodeManager(this);
        this.profileManager = new ProfileManager(this, storage, stateService);
        this.guiManager = new GuiManager(this);

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
        getServer().getPluginManager().registerEvents(new ProfileListener(this), this);
        getServer().getPluginManager().registerEvents(new CommandGateListener(this), this);
        getServer().getPluginManager().registerEvents(guiManager, this);

        getLogger().info("RoyalSkyblock enabled — metadata store: "
                + getConfig().getString("storage.type", "sqlite").toUpperCase()
                + ", island world source: " + getConfig().getString("world.slime-data-source", "file") + ".");
    }

    @Override
    public void onDisable() {
        if (worldService != null) {
            worldService.shutdown();
        }
        if (storage != null) {
            storage.close();
        }
        getLogger().info("RoyalSkyblock disabled.");
        instance = null;
    }

    /** Reload config + messages from disk. Extended in later phases to re-read levels/menus. */
    public void reload() {
        reloadConfig();
        messageManager.reload();
        gamemodeManager.reload();
        guiManager.reload();
    }

    public GamemodeManager gamemodes() {
        return gamemodeManager;
    }

    public MessageManager messages() {
        return messageManager;
    }

    public GuiManager gui() {
        return guiManager;
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

    /** Whether WorldEdit/FAWE is on the classpath (so we can safely load the WE schematic impl). */
    private boolean worldEditAvailable() {
        if (!getServer().getPluginManager().isPluginEnabled("WorldEdit")
                && !getServer().getPluginManager().isPluginEnabled("FastAsyncWorldEdit")) {
            return false;
        }
        try {
            Class.forName("com.sk89q.worldedit.WorldEdit", false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException noWorldEdit) {
            return false;
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

    public SchematicService schematics() {
        return schematicService;
    }

    public IslandWorldService worlds() {
        return worldService;
    }

    public Storage storage() {
        return storage;
    }

    public ProfileManager profiles() {
        return profileManager;
    }

    public PlayerStateService playerState() {
        return stateService;
    }

    public boolean isWorldBackendReady() {
        return worldBackendReady;
    }

    public EcoProfileBridge eco() {
        return ecoBridge;
    }

    public int ecoSlot(UUID player) {
        return ecoSlot.getOrDefault(player, 1);
    }

    public void setEcoSlot(UUID player, int slot) {
        ecoSlot.put(player, slot);
    }
}
