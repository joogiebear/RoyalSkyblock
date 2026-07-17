package com.mystipixel.royalskyblock;

import com.mystipixel.royalskyblock.bank.BankLevelManager;
import com.mystipixel.royalskyblock.bank.BankService;
import com.mystipixel.royalskyblock.border.BorderService;
import com.mystipixel.royalskyblock.command.BankCommand;
import com.mystipixel.royalskyblock.command.IslandCommand;
import com.mystipixel.royalskyblock.config.ConfigValidator;
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
    private com.mystipixel.royalskyblock.perk.PerkService perkService;
    private com.mystipixel.royalskyblock.island.IslandUnloadService unloadService;
    private BankLevelManager bankLevels;
    private BankService bankService;
    private BorderService borderService;
    private VaultHook vaultHook; // wallet lookups for the bank "deposit all"
    private EcoProfileBridge ecoBridge;
    private MessageManager messageManager;
    private GuiManager guiManager;
    private com.mystipixel.royalskyblock.hooks.RoyalSkyblockExpansion rsbExpansion;
    private boolean papiRegistered;

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
        this.perkService = new com.mystipixel.royalskyblock.perk.PerkService(this);
        this.unloadService = new com.mystipixel.royalskyblock.island.IslandUnloadService(this);
        this.profileManager = new ProfileManager(this, storage, stateService);
        this.vaultHook = resolveVault();
        this.bankLevels = new BankLevelManager(this);
        this.bankService = new BankService(this, bankLevels, vaultHook);
        this.borderService = new BorderService(this);
        this.guiManager = new GuiManager(this);

        // Bring up the world backend asynchronously. If the server isn't running ASP, keep the plugin
        // enabled but flag island world ops as unavailable so commands can explain clearly.
        worldService.initialize().whenComplete((ignored, error) -> {
            if (error != null) {
                getLogger().severe("Island world backend unavailable: " + rootMessage(error));
                worldBackendReady = false;
            } else {
                worldBackendReady = true;
            }
            printStatusPanel(); // once ASP status is known, print the boot summary
        });

        registerCommands();
        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new ProfileListener(this), this);
        getServer().getPluginManager().registerEvents(new CommandGateListener(this), this);
        getServer().getPluginManager().registerEvents(new FlowLimiterListener(this), this);
        getServer().getPluginManager().registerEvents(guiManager, this);
        getServer().getPluginManager().registerEvents(borderService, this);

        // PlaceholderAPI expansion (%royalskyblock_...%) for TAB / scoreboards / chat. Soft — only
        // registers if PlaceholderAPI is installed; the level leaderboard is refreshed off-thread.
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            this.rsbExpansion = new com.mystipixel.royalskyblock.hooks.RoyalSkyblockExpansion(this);
            if (rsbExpansion.register()) {
                papiRegistered = true;
                getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                    try {
                        rsbExpansion.refreshLeaderboard();
                    } catch (Exception ex) {
                        getLogger().warning("Placeholder leaderboard refresh failed: " + ex.getMessage());
                    }
                }, 20L, 60L * 20L);
            }
        }

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
        // Level-gated perks tick (self-guards to a no-op when perks are disabled).
        long perkPeriod = perkService.refreshSeconds() * 20L;
        getServer().getScheduler().runTaskTimer(this, () -> perkService.tick(), perkPeriod, perkPeriod);
        // Drop empty island worlds. Without this an island ticks forever once visited, so the
        // server's cost scales with islands-ever-visited instead of players online.
        getServer().getScheduler().runTaskTimer(this, () -> unloadService.tick(), 200L, 100L);
        // Native offline simulation. Anything else that should catch up (minions, etc.) listens to
        // IslandCatchupEvent instead of being wired in here.
        getServer().getPluginManager().registerEvents(
                new com.mystipixel.royalskyblock.simulation.CropSimulator(this), this);

        getLogger().info("RoyalSkyblock enabled — metadata store: "
                + getConfig().getString("storage.type", "sqlite").toUpperCase()
                + ", island world source: " + getConfig().getString("world.slime-data-source", "file") + ".");
        // Deferred a tick: worlds (Multiverse) and Vault economies register during other plugins' enable,
        // which may run after ours — checking inline would flag a healthy hub/economy as missing.
        getServer().getScheduler().runTask(this, () -> new ConfigValidator(this).validate());
        // The full status panel prints once the ASP backend finishes initialising (see above).
    }

    /** A one-glance boot summary: which dependencies are active and what an admin should configure first. */
    private void printStatusPanel() {
        boolean vault = vaultHook != null && vaultHook.isReady();
        String storage = getConfig().getString("storage.type", "sqlite").toUpperCase();
        String worldSource = getConfig().getString("world.slime-data-source", "file");
        getLogger().info("======================== RoyalSkyblock ========================");
        getLogger().info(" Islands (ASP)    : " + (worldBackendReady
                ? "READY (source: " + worldSource + ")"
                : "UNAVAILABLE — install Advanced Slime Paper (island create/teleport off)"));
        getLogger().info(" Economy (Vault)  : " + (vault ? "READY" : "NOT FOUND — bank & coin costs disabled"));
        getLogger().info(" Bank             : " + (bankService.available()
                ? "READY (native, " + storage + ")" : "needs Vault + bank.yml levels"));
        getLogger().info(" Schematics       : " + (schematicService.isAvailable()
                ? "WorldEdit/FAWE (.schem)" : "built-in generator (install WorldEdit/FAWE for .schem)"));
        getLogger().info(" Progression (eco): " + (ecoBridge.isPresent()
                ? "linked (skills/coins are per-profile)" : "not found (progression is not per-profile)"));
        getLogger().info(" Metadata storage : " + storage);
        getLogger().info(" Perks            : " + (perkService.enabled()
                ? "ON (" + perkService.perkCount() + " perks)" : "off (optional — enable in perks.yml)"));
        getLogger().info(" Placeholders     : " + (papiRegistered
                ? "registered (%royalskyblock_...%)" : "PlaceholderAPI not found"));
        getLogger().info(" ---------------------------------------------------------------");
        getLogger().info(" Configure first  : spawn.world + currencies in config.yml");
        getLogger().info(" Commands: /is help  ·  Reload: /is reload  ·  See README.md");
        getLogger().info("===============================================================");
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
        perkService.reload();
        bankLevels.reload();
        borderService.reload();
        borderService.refreshAll(); // re-apply borders live (colour/size/toggle changes)
        guiManager.reload();
        new ConfigValidator(this).validate();
    }

    public UpgradeManager upgrades() {
        return upgradeManager;
    }

    public LevelService levels() {
        return levelService;
    }

    public com.mystipixel.royalskyblock.island.IslandUnloadService unloads() {
        return unloadService;
    }

    public com.mystipixel.royalskyblock.perk.PerkService perks() {
        return perkService;
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

    public BorderService borders() {
        return borderService;
    }

    /** Whether a Vault economy is present and ready (bank & coin costs depend on it). */
    public boolean economyReady() {
        return vaultHook != null && vaultHook.isReady();
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
