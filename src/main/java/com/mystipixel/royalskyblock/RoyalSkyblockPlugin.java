package com.mystipixel.royalskyblock;

import com.mystipixel.royalskyblock.bank.BankLevelManager;
import com.mystipixel.royalskyblock.bank.BankService;
import com.mystipixel.royalskyblock.command.BankCommand;
import com.mystipixel.royalskyblock.command.IslandCommand;
import com.mystipixel.royalskyblock.currency.CurrencyService;
import com.mystipixel.royalskyblock.hooks.VaultHook;
import com.mystipixel.royalskyblock.data.Storage;
import com.mystipixel.royalskyblock.gui.GuiManager;
import com.mystipixel.royalskyblock.hooks.EcoProfileBridge;
import com.mystipixel.royalskyblock.island.IslandManager;
import com.mystipixel.royalskyblock.island.NoOpSchematics;
import com.mystipixel.royalskyblock.island.SchematicService;
import com.mystipixel.royalskyblock.island.WorldEditSchematics;
import com.mystipixel.royalskyblock.level.LevelService;
import com.mystipixel.royalskyblock.listener.ProfileListener;
import com.mystipixel.royalskyblock.listener.ProtectionListener;
import com.mystipixel.royalskyblock.listener.CommandGateListener;
import com.mystipixel.royalskyblock.listener.FlowLimiterListener;
import com.mystipixel.royalskyblock.message.MessageManager;
import com.mystipixel.royalskyblock.profile.GamemodeManager;
import com.mystipixel.royalskyblock.profile.PlayerStateService;
import com.mystipixel.royalskyblock.profile.ProfileManager;
import com.mystipixel.royalskyblock.upgrade.UpgradeManager;
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
    private CurrencyService currencyService;
    private UpgradeManager upgradeManager;
    private LevelService levelService;
    private BankLevelManager bankLevels;
    private BankService bankService;
    private VaultHook vaultHook; // wallet lookups for the bank "deposit all"
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
        this.currencyService = new CurrencyService(this);
        this.upgradeManager = new UpgradeManager(this);
        this.levelService = new LevelService(this);
        this.profileManager = new ProfileManager(this, storage, stateService);
        this.vaultHook = resolveVault();
        this.bankLevels = new BankLevelManager(this);
        this.bankService = new BankService(this, bankLevels, vaultHook);
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
        getServer().getPluginManager().registerEvents(new FlowLimiterListener(this), this);
        getServer().getPluginManager().registerEvents(guiManager, this);

        // Resume in-progress upgrade timers and complete any that elapsed while offline.
        upgradeManager.loadPending();
        getServer().getScheduler().runTaskTimer(this, () -> upgradeManager.tick(), 40L, 20L);
        // Live upgrade-menu countdowns (self-guards to no-op when nothing is cooking).
        getServer().getScheduler().runTaskTimer(this, () -> guiManager.tickOpenMenus(), 20L, 20L);
        // Background island-level refresh for occupied islands (0 = off; change needs a restart).
        long autoRecalcMinutes = levelService.config().autoRecalcMinutes();
        if (autoRecalcMinutes > 0) {
            long period = autoRecalcMinutes * 60L * 20L;
            getServer().getScheduler().runTaskTimer(this, () -> levelService.autoRecalcActiveIslands(), period, period);
        }

        getLogger().info("RoyalSkyblock enabled — metadata store: "
                + getConfig().getString("storage.type", "sqlite").toUpperCase()
                + ", island world source: " + getConfig().getString("world.slime-data-source", "file") + ".");
        getLogger().info("Schematic backend: " + (schematicService.isAvailable()
                ? "WorldEdit/FAWE (.schem supported)" : "built-in generator (install WorldEdit/FAWE for .schem)"));
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
        currencyService.reload();
        upgradeManager.reload();
        levelService.reload();
        bankLevels.reload();
        guiManager.reload();
    }

    public UpgradeManager upgrades() {
        return upgradeManager;
    }

    public LevelService levels() {
        return levelService;
    }

    public GamemodeManager gamemodes() {
        return gamemodeManager;
    }

    public CurrencyService currency() {
        return currencyService;
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
        PluginCommand bank = getCommand("bank");
        if (bank != null) {
            bank.setExecutor(new BankCommand(this));
        }
    }

    public BankService bank() {
        return bankService;
    }

    /** The player's Vault wallet balance (0 if no economy). Used by the coop-bank "deposit all" button. */
    public double purseBalance(org.bukkit.entity.Player player) {
        return vaultHook != null ? vaultHook.balance(player) : 0.0;
    }

    /** Guarded Vault lookup — only links {@code net.milkbowl.vault.*} when Vault is actually present. */
    private VaultHook resolveVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return null;
        }
        try {
            Class.forName("net.milkbowl.vault.economy.Economy");
            VaultHook hook = new VaultHook();
            return hook.isReady() ? hook : null;
        } catch (Throwable notVault) {
            return null;
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
