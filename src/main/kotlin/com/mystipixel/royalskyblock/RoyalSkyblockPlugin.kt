package com.mystipixel.royalskyblock

import com.mystipixel.royalskyblock.bank.BankLevelManager
import com.mystipixel.royalskyblock.bank.BankService
import com.mystipixel.royalskyblock.border.BorderService
import com.mystipixel.royalskyblock.command.BankCommand
import com.mystipixel.royalskyblock.command.CommandIsland
import com.mystipixel.royalskyblock.config.ConfigValidator
import com.mystipixel.royalskyblock.currency.CurrencyService
import com.mystipixel.royalskyblock.data.EcoStorage
import com.mystipixel.royalskyblock.data.SqlStorage
import com.mystipixel.royalskyblock.data.SqliteMigration
import com.mystipixel.royalskyblock.data.Storage
import com.mystipixel.royalskyblock.gui.GuiManager
import com.mystipixel.royalskyblock.hooks.CombatLevelSource
import com.mystipixel.royalskyblock.hooks.EcoMobsIslandMobProvider
import com.mystipixel.royalskyblock.hooks.EcoMobsStrengthBridge
import com.mystipixel.royalskyblock.hooks.EcoProfileBridge
import com.mystipixel.royalskyblock.hooks.EcoSkillsCombatSource
import com.mystipixel.royalskyblock.hooks.EcoSkillsStatSource
import com.mystipixel.royalskyblock.hooks.IslandMobProvider
import com.mystipixel.royalskyblock.hooks.IslandMobTargetingBridge
import com.mystipixel.royalskyblock.hooks.IslandPlaceholders
import com.mystipixel.royalskyblock.hooks.RoyalSkyblockExpansion
import com.mystipixel.royalskyblock.hooks.VaultHook
import com.mystipixel.royalskyblock.island.GeneratorService
import com.mystipixel.royalskyblock.island.IslandManager
import com.mystipixel.royalskyblock.island.IslandMobSpawnService
import com.mystipixel.royalskyblock.island.IslandUnloadService
import com.mystipixel.royalskyblock.island.NoOpSchematics
import com.mystipixel.royalskyblock.island.SchematicService
import com.mystipixel.royalskyblock.island.WorldEditSchematics
import com.mystipixel.royalskyblock.level.LevelService
import com.mystipixel.royalskyblock.libreforge.ConditionMinionCount
import com.mystipixel.royalskyblock.libreforge.EcoPlaceholders
import com.mystipixel.royalskyblock.libreforge.IslandConditions
import com.mystipixel.royalskyblock.libreforge.IslandTriggers
import com.mystipixel.royalskyblock.libreforge.MenuChains
import com.mystipixel.royalskyblock.libreforge.MinionTriggers
import com.mystipixel.royalskyblock.libreforge.RoyalHolderListener
import com.mystipixel.royalskyblock.libreforge.RoyalHolders
import com.mystipixel.royalskyblock.listener.CommandGateListener
import com.mystipixel.royalskyblock.listener.FlowLimiterListener
import com.mystipixel.royalskyblock.listener.GeneratorListener
import com.mystipixel.royalskyblock.listener.IslandPortalListener
import com.mystipixel.royalskyblock.listener.IslandRulesListener
import com.mystipixel.royalskyblock.listener.ProfileListener
import com.mystipixel.royalskyblock.listener.ProtectionListener
import com.mystipixel.royalskyblock.listener.VoidListener
import com.mystipixel.royalskyblock.message.MessageManager
import com.mystipixel.royalskyblock.perk.PerkService
import com.mystipixel.royalskyblock.profile.GamemodeManager
import com.mystipixel.royalskyblock.profile.PlayerStateService
import com.mystipixel.royalskyblock.profile.ProfileManager
import com.mystipixel.royalskyblock.simulation.AgeCropSimulator
import com.mystipixel.royalskyblock.simulation.IslandScanner
import com.mystipixel.royalskyblock.simulation.StackingPlantSimulator
import com.mystipixel.royalskyblock.upgrade.UpgradeManager
import com.mystipixel.royalskyblock.world.IslandWorldRules
import com.mystipixel.royalskyblock.world.IslandWorldService
import com.mystipixel.royalskyblock.world.NoOpIslandWorldService
import com.mystipixel.royalskyblock.world.asp.AspIslandWorldService
import com.willfp.eco.core.bstats.EcoMetricsChart
import com.willfp.libreforge.loader.LibreforgePlugin
import com.willfp.libreforge.loader.configs.ConfigCategory
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * RoyalSkyblock entry point.
 *
 * Scalable per-island Skyblock: every island is its own Advanced Slime Paper world, loaded on
 * demand and unloaded when empty, with island metadata in the shared dual-dialect (SQLite/MySQL)
 * store.
 *
 * ## Why this class is Kotlin while the rest of the plugin is Java
 *
 * RoyalSkyblock is built on eco/libreforge, so the entry point must extend [LibreforgePlugin],
 * whose lifecycle and element-registration APIs are Kotlin. Only this class moved; the other 22
 * packages are still Java and compile in the same module (Kotlin scans `src/main/java` too, so
 * joint compilation resolves both directions). The class keeps its original name and its entire
 * public accessor surface, so no Java caller changed.
 *
 * ## Lifecycle mapping — the parts that are not obvious
 *
 * [org.bukkit.plugin.java.JavaPlugin.onEnable]/`onDisable`/`reload` are all `final` on `EcoPlugin`,
 * so the work moved to [handleEnable]/[handleDisable]/[handleReload]. Two consequences drove the
 * shape of this class:
 *
 *  - **Every repeating task lives in [createTasks].** `EcoPlugin.reload()` calls
 *    `scheduler.cancelAll()`, which is `Bukkit.getScheduler().cancelTasks(plugin)` — it cancels
 *    *every* task this plugin owns, including ones registered straight through Bukkit. Anything
 *    registered in [handleEnable] would be killed by the first reload and never come back, silently
 *    stopping island unloading, upgrade timers and perk ticks. eco re-invokes [createTasks] after
 *    that cancellation, which is exactly the recovery point. eco only calls it from `reload()`,
 *    never from `onEnable`, so [handleEnable] calls it once itself for first boot.
 *  - **Config reads go through [conf], never `getConfig()`.** `EcoPlugin.getConfig()` logs
 *    "Call to default config method in eco plugin!" on *every* call and re-converts the config each
 *    time; with ~96 call sites, some per-mob-spawn, that would flood the console.
 */
class RoyalSkyblockPlugin : LibreforgePlugin() {

    private var generatorService: GeneratorService? = null
    private var storage: Storage? = null
    private var worldService: IslandWorldService? = null
    private var islandManager: IslandManager? = null
    private var worldRules: IslandWorldRules? = null
    private var mobSpawnService: IslandMobSpawnService? = null
    private var combatSource: CombatLevelSource? = null
    private var intimidationSource: CombatLevelSource? = null
    private var schematicService: SchematicService? = null
    private var profileManager: ProfileManager? = null
    private var stateService: PlayerStateService? = null
    private var gamemodeManager: GamemodeManager? = null
    private var currencyService: CurrencyService? = null
    private var upgradeManager: UpgradeManager? = null
    private var levelService: LevelService? = null
    private var perkService: PerkService? = null
    private var unloadService: IslandUnloadService? = null
    private var islandScanner: IslandScanner? = null
    private var bankLevels: BankLevelManager? = null
    private var bankService: BankService? = null
    private var borderService: BorderService? = null

    /** Wallet lookups for the bank "deposit all". */
    private var vaultHook: VaultHook? = null
    private var ecoBridge: EcoProfileBridge? = null
    private var messageManager: MessageManager? = null
    private var guiManager: GuiManager? = null
    /** Resolves every placeholder. Independent of PlaceholderAPI; both front ends share it. */
    private var placeholders: IslandPlaceholders? = null
    private var papiRegistered = false

    /**
     * SPIKE: which eco test-slot each player is currently on (defaults to 1). Removed once the real
     * profile system lands.
     */
    private val ecoSlot: MutableMap<UUID, Int> = ConcurrentHashMap()

    /** True once the ASP world backend initialised. When false, island world ops are unavailable. */
    @Volatile
    private var worldBackendReady = false

    /**
     * Bukkit-shaped view of eco's `config.yml`, cached because [com.willfp.eco.core.config.interfaces.Config.toBukkit]
     * re-converts on each call. Invalidated on reload and whenever [setConfigValue] writes.
     */
    @Volatile
    private var cachedConfig: FileConfiguration? = null

    init {
        instance = this
    }

    companion object {
        private var instance: RoyalSkyblockPlugin? = null

        @JvmStatic
        fun get(): RoyalSkyblockPlugin = instance!!
    }

    /**
     * No config categories yet — island/upgrade configs become libreforge categories in a later
     * phase. Declared explicitly so the intent is visible rather than inherited by accident.
     */
    override fun loadConfigCategories(): List<ConfigCategory> = emptyList()

    /**
     * Read-side config accessor. Use this instead of `getConfig()` everywhere in the plugin — see
     * the class docs for why `getConfig()` is unusable under eco.
     *
     * Reads `config.yml` off disk rather than going through `configYml.toBukkit()`. eco's conversion
     * does not round-trip *nested* sections as Bukkit [org.bukkit.configuration.ConfigurationSection]s:
     * `getConfigurationSection("currencies")` resolves but `getConfigurationSection("coins")` inside
     * it comes back null, so CurrencyService silently skipped every currency and the config check
     * started reporting "currency 'coins' isn't defined". Eleven call sites use nested sections, and
     * most of them fall back to defaults rather than failing loudly, so this went unnoticed until the
     * validator caught it. Loading the file gives a genuine YamlConfiguration with the exact
     * semantics the pre-port `getConfig()` had.
     *
     * eco still owns writing and merging the file; this only reads it. Safe from [handleEnable]
     * onwards — eco writes config.yml before the plugin enables.
     */
    fun conf(): FileConfiguration {
        cachedConfig?.let { return it }
        val loaded = YamlConfiguration.loadConfiguration(File(dataFolder, "config.yml"))
        cachedConfig = loaded
        return loaded
    }

    /**
     * Persist a single `config.yml` value. Writes must go through eco's config rather than
     * `conf().set(...)` + `saveConfig()`: [conf] hands out a converted copy, so a write there would
     * be silently dropped on the next read. Used by `/is admin border <colour>`.
     */
    fun setConfigValue(path: String, value: Any?) {
        configYml.set(path, value)
        try {
            configYml.save()
        } catch (e: IOException) {
            logger.warning("Failed to save config.yml after setting $path: ${e.message}")
        }
        cachedConfig = null
    }

    /**
     * Pick the metadata store from `storage.type`.
     *
     * `SQLITE`/`MYSQL` use this plugin's own database; `ECO` hands everything to eco's data layer,
     * which is how the rest of the suite works and what makes islands network-shared without a
     * second database to configure. SQLite stays the default so no existing server changes backend
     * on an update.
     *
     * Switching an existing server to `ECO` carries its `islands.db` across on that first boot — see
     * [migrateSqliteIfPresent], which runs after the store is open.
     */
    private fun createStorage(): Storage {
        val type = conf().getString("storage.type", "SQLITE")!!.uppercase(Locale.ROOT)
        return if (type == "ECO") EcoStorage(this) else SqlStorage(this)
    }

    /**
     * Carry an existing `islands.db` into eco, once, on the boot that switches to it.
     *
     * Returns false to abort startup. That is the whole point: the alternatives to stopping are
     * coming up on a half-populated store, or coming up empty — and an empty skyblock server looks
     * exactly like one whose islands have all been deleted. The source file is left untouched unless
     * every row was written *and* read back, so aborting always leaves a server that still works on
     * `storage.type: sqlite`.
     */
    private fun migrateSqliteIfPresent(store: EcoStorage): Boolean {
        val file = File(dataFolder, conf().getString("storage.sqlite-file", "islands.db")!!)
        if (!file.isFile) {
            return true
        }
        // A store that already has islands and was never migrated into belongs to something else.
        // Merging one server's islands into another's is not a thing anyone asked for by dropping a
        // file in a folder, so it stops instead. A retry after a half-finished run is fine: that
        // store carries the marker, and every write is keyed by an id that does not change.
        if (store.hasIslands() && store.migrationMarker().isBlank()) {
            logger.severe("${file.name} is present, but eco already holds islands that did not come")
            logger.severe("from a migration. Refusing to merge two sets of islands together. Move")
            logger.severe("${file.name} aside if it is the stale one.")
            return false
        }

        logger.info("storage.type is ECO and ${file.name} is present — migrating it into eco's data layer.")
        val migration = SqliteMigration(this, file, store)
        val report = migration.run()

        if (!report.ok()) {
            logger.severe("Migration FAILED. ${file.name} has been left exactly as it was:")
            for (problem in report.problems()) {
                logger.severe("  - $problem")
            }
            logger.severe("Nothing was deleted. Set storage.type back to sqlite to keep running on it.")
            return false
        }

        logger.info("Migrated ${report.summary()} — every row read back and matched.")
        store.setMigrationMarker(file.name)
        if (!migration.retireSource()) {
            return false
        }
        migration.cleanSidecars()
        return true
    }

    override fun handleEnable() {
        this.messageManager = MessageManager(this)

        val store = createStorage()
        this.storage = store
        if (!store.connect()) {
            logger.severe("Storage failed to initialise — disabling RoyalSkyblock.")
            server.pluginManager.disablePlugin(this)
            return
        }
        if (store is EcoStorage && !migrateSqliteIfPresent(store)) {
            logger.severe("Disabling RoyalSkyblock rather than running on incomplete data.")
            server.pluginManager.disablePlugin(this)
            return
        }

        this.ecoBridge = EcoProfileBridge()
        this.worldService = if (aspAvailable()) AspIslandWorldService(this) else NoOpIslandWorldService()
        this.schematicService = if (worldEditAvailable()) WorldEditSchematics(this) else NoOpSchematics()
        this.islandManager = IslandManager(this, store, worldService)
        this.worldRules = IslandWorldRules(this)
        this.stateService = PlayerStateService(this, store)
        this.gamemodeManager = GamemodeManager(this)
        this.currencyService = CurrencyService(this)
        this.upgradeManager = UpgradeManager(this)
        this.levelService = LevelService(this)
        this.perkService = PerkService(this)
        this.unloadService = IslandUnloadService(this)
        this.islandScanner = IslandScanner(this)
        this.profileManager = ProfileManager(this, store, stateService)
        this.vaultHook = resolveVault()
        this.bankLevels = BankLevelManager(this)
        this.bankService = BankService(this, bankLevels, vaultHook)
        this.borderService = BorderService(this)
        this.guiManager = GuiManager(this)

        // Bring up the world backend asynchronously. If the server isn't running ASP, keep the plugin
        // enabled but flag island world ops as unavailable so commands can explain clearly.
        worldService!!.initialize().whenComplete { _, error ->
            if (error != null) {
                logger.severe("Island world backend unavailable: ${rootMessage(error)}")
                worldBackendReady = false
            } else {
                worldBackendReady = true
            }
            printStatusPanel() // once ASP status is known, print the boot summary
        }

        registerCommands()
        server.pluginManager.registerEvents(ProtectionListener(this), this)
        server.pluginManager.registerEvents(ProfileListener(this), this)
        server.pluginManager.registerEvents(IslandRulesListener(this), this)
        server.pluginManager.registerEvents(VoidListener(this), this)
        server.pluginManager.registerEvents(CommandGateListener(this), this)
        server.pluginManager.registerEvents(FlowLimiterListener(this), this)
        server.pluginManager.registerEvents(guiManager!!, this)
        server.pluginManager.registerEvents(borderService!!, this)

        // Placeholders. Registered with eco unconditionally, so %royalskyblock_...% resolves inside
        // eco configs — effect chains, menu titles, item lore — on any server, with or without
        // PlaceholderAPI. The PAPI expansion is an additional front end onto the same resolver, for
        // TAB, scoreboards and chat.
        val resolver = IslandPlaceholders(this)
        this.placeholders = resolver
        EcoPlaceholders.register(this, resolver)
        if (server.pluginManager.isPluginEnabled("PlaceholderAPI")) {
            papiRegistered = RoyalSkyblockExpansion(this, resolver).register()
        }

        // Resume in-progress upgrade timers and complete any that elapsed while offline.
        upgradeManager!!.loadPending()

        // Offline simulation. The scanner walks a returning island once and dispatches its blocks to
        // registered BlockSimulators; addons register their own the same way these do. Anything that
        // isn't block-shaped (minions) listens to IslandCatchupEvent directly instead.
        val scanner = islandScanner!!
        scanner.register(AgeCropSimulator(this))
        scanner.register(StackingPlantSimulator(this))
        server.pluginManager.registerEvents(scanner, this)

        // EcoMobs strength bridge — soft hook. The bridge resolves EcoMobs' spawn event reflectively
        // (no publishable artifact exists to compile against; see the class docs) and reports whether
        // it managed to bind.
        if (server.pluginManager.isPluginEnabled("EcoMobs")) {
            if (EcoMobsStrengthBridge.register(this)) {
                logger.info("EcoMobs detected — island-level mob strength scaling active.")
            } else {
                logger.warning(
                    "EcoMobs is installed but its spawn event could not be resolved — "
                        + "island mob strength scaling is off."
                )
            }
        }

        this.generatorService = GeneratorService(this)
        server.pluginManager.registerEvents(GeneratorListener(this), this)
        server.pluginManager.registerEvents(IslandPortalListener(this), this)

        // RoyalSkyblock's own island state, published to libreforge so any eco plugin — and this
        // plugin's own perks and upgrades — can ask questions about an island in config.
        IslandConditions.register()
        IslandTriggers.register()
        MenuChains.register()

        // Publish EcoMinions activity to libreforge. EcoMinions registers no elements of its own, so
        // without this nothing in any eco config can react to a minion.
        //
        // MUST come before RoyalHolders.register, which compiles every perk and upgrade chain: a
        // config referencing an element that is not registered yet fails to compile and is reported as
        // an unknown id. That is exactly what happened to the Minion Overseer perk the first time.
        if (server.pluginManager.isPluginEnabled("EcoMinions")) {
            if (MinionTriggers.register(this) && ConditionMinionCount.register()) {
                logger.info("EcoMinions detected — registered minion triggers (minion_pickup, "
                    + "minion_place, minion_upgrade, minion_fuel) and the minion_count_above condition.")
            } else {
                logger.warning(
                    "EcoMinions is installed but its API could not be read — minion elements are off."
                )
            }
        }

        // Perks and island upgrades as libreforge effect holders. Registered after the services they
        // read (perks, upgrades, islands, profiles) exist, and after every element their chains may use.
        RoyalHolders.register(this)
        server.pluginManager.registerEvents(RoyalHolderListener(), this)

        startIslandMobSpawning()

        logger.info(
            "RoyalSkyblock enabled — metadata store: "
                + conf().getString("storage.type", "sqlite")!!.uppercase(Locale.ROOT)
                + ", island world source: " + conf().getString("world.slime-data-source", "file") + "."
        )

        // eco only invokes createTasks() from reload(), so start the repeating tasks once here.
        createTasks()

        // Deferred a tick: worlds (Multiverse) and Vault economies register during other plugins'
        // enable, which may run after ours — checking inline would flag a healthy hub/economy as missing.
        server.scheduler.runTask(this, Runnable { ConfigValidator(this).validate() })
        // The full status panel prints once the ASP backend finishes initialising (see above).
    }

    /**
     * Every repeating task. Re-invoked by eco after `reload()` cancels all of this plugin's tasks —
     * see the class docs. Must stay idempotent: it runs once per enable and once per reload, always
     * after a cancellation, so it never double-registers.
     */
    override fun createTasks() {
        val upgrades = upgradeManager ?: return // enable aborted (storage failure); nothing to tick

        server.scheduler.runTaskTimer(this, Runnable { upgrades.tick() }, 40L, 20L)
        // Live upgrade-menu countdowns (self-guards to no-op when nothing is cooking).
        server.scheduler.runTaskTimer(this, Runnable { guiManager?.tickOpenMenus() }, 20L, 20L)
        // Background island-level refresh for occupied islands (0 = off).
        val autoRecalcMinutes = levelService!!.config().autoRecalcMinutes()
        if (autoRecalcMinutes > 0) {
            val period = autoRecalcMinutes * 60L * 20L
            server.scheduler.runTaskTimer(
                this, Runnable { levelService?.autoRecalcActiveIslands() }, period, period
            )
        }
        // Level-gated perks tick (self-guards to a no-op when perks are disabled).
        val perkPeriod = perkService!!.refreshSeconds() * 20L
        server.scheduler.runTaskTimer(this, Runnable { perkService?.tick() }, perkPeriod, perkPeriod)
        // Drop empty island worlds. Without this an island ticks forever once visited, so the
        // server's cost scales with islands-ever-visited instead of players online.
        server.scheduler.runTaskTimer(this, Runnable { unloadService?.tick() }, 200L, 100L)

        // The level leaderboard is refreshed off-thread. No longer gated on PlaceholderAPI: the rank
        // placeholder is served to eco as well, so the cache has to be warm regardless.
        server.scheduler.runTaskTimerAsynchronously(this, Runnable {
            try {
                placeholders?.refreshLeaderboard()
            } catch (ex: Exception) {
                logger.warning("Placeholder leaderboard refresh failed: ${ex.message}")
            }
        }, 20L, 60L * 20L)
    }

    /** A one-glance boot summary: which dependencies are active and what an admin should configure first. */
    private fun printStatusPanel() {
        val vault = vaultHook?.isReady == true
        val storageType = conf().getString("storage.type", "sqlite")!!.uppercase(Locale.ROOT)
        val worldSource = conf().getString("world.slime-data-source", "file")
        logger.info("======================== RoyalSkyblock ========================")
        logger.info(
            " Islands (ASP)    : " + if (worldBackendReady) "READY (source: $worldSource)"
            else "UNAVAILABLE — install Advanced Slime Paper (island create/teleport off)"
        )
        logger.info(" Economy (Vault)  : " + if (vault) "READY" else "NOT FOUND — bank & coin costs disabled")
        logger.info(
            " Bank             : " + if (bankService!!.available()) "READY (native, $storageType)"
            else "needs Vault + bank.yml levels"
        )
        logger.info(
            " Schematics       : " + if (schematicService!!.isAvailable) "WorldEdit/FAWE (.schem)"
            else "built-in generator (install WorldEdit/FAWE for .schem)"
        )
        logger.info(
            " Progression (eco): " + if (ecoBridge!!.isPresent) "linked (skills/coins are per-profile)"
            else "not found (progression is not per-profile)"
        )
        logger.info(" Metadata storage : $storageType")
        logger.info(
            " Perks            : " + if (perkService!!.enabled()) "ON (${perkService!!.perkCount()} perks)"
            else "off (optional — enable in perks.yml)"
        )
        logger.info(
            " Placeholders     : registered with eco (%royalskyblock_...%)"
                + if (papiRegistered) " + PlaceholderAPI" else " — PlaceholderAPI not found"
        )
        logger.info(" ---------------------------------------------------------------")
        logger.info(" Configure first  : spawn.world + currencies in config.yml")
        logger.info(" Commands: /is help  ·  Reload: /is reload  ·  See README.md")
        logger.info("===============================================================")
    }

    override fun handleDisable() {
        // Save everyone still online and every loaded island SYNCHRONOUSLY. On shutdown the Bukkit
        // scheduler is stopping, so the normal async profile saves and the unload-tick's island save
        // won't run — without this, anyone online at shutdown (or when the server restarts for a
        // deploy) loses whatever they did since the last periodic save: inventory, eco/skills, blocks.
        var savedPlayers = 0
        var savedIslands = 0
        profileManager?.let { profiles ->
            for (player in server.onlinePlayers) {
                try {
                    profiles.handleQuit(player)
                    savedPlayers++
                } catch (e: Exception) {
                    logger.warning("Failed to save ${player.name} on shutdown: ${e.message}")
                }
            }
        }
        val worlds = worldService
        val islands = islandManager
        if (worlds != null && islands != null) {
            for (world in server.worlds) {
                if (islands.getIslandByWorld(world) == null) {
                    continue // not an island (hub, base world, ...)
                }
                try {
                    // Synchronous on purpose: Bukkit won't schedule tasks for a disabling plugin, so
                    // the async saveIsland() path throws here and the save is silently lost.
                    worlds.saveIslandNow(world.name)
                    savedIslands++
                } catch (e: Exception) {
                    logger.warning("Failed to save island ${world.name} on shutdown: ${e.message}")
                }
            }
        }
        logger.info("Shutdown save: $savedPlayers player(s), $savedIslands island(s).")

        worldService?.shutdown()
        storage?.close()
        logger.info("RoyalSkyblock disabled.")
        instance = null
    }

    /**
     * Reload config + messages from disk. Reached through `EcoPlugin.reload()`, which is `final` —
     * it refreshes eco's own configs and cancels this plugin's tasks first, then calls this, then
     * re-runs [createTasks]. Deliberately does not touch the scheduler itself.
     */
    override fun handleReload() {
        cachedConfig = null // eco reloaded configYml underneath us
        messageManager?.reload()
        gamemodeManager?.reload()
        currencyService?.reload()
        upgradeManager?.reload()
        levelService?.reload()
        generatorService?.reload()
        perkService?.reload()
        bankLevels?.reload()
        borderService?.reload()
        borderService?.refreshAll() // re-apply borders live (colour/size/toggle changes)
        guiManager?.reload()
        MenuChains.invalidate() // recompile menu click chains from the edited configs
        mobSpawnService?.reloadSettings() // toggling island-mobs.enabled on/off still needs a restart
        RoyalHolders.reload(this) // recompile perk/upgrade effect chains and re-provide them
        ConfigValidator(this).validate()
    }

    /**
     * Anonymous usage reporting. eco registers bStats itself from the id in `eco.yml`, so the plugin
     * only supplies the custom charts. Server owners who want no reporting disable it globally in
     * plugins/bStats/config.yml.
     */
    override fun getCustomCharts(): List<EcoMetricsChart> = listOf(
        EcoMetricsChart.simplePie("storage_backend") {
            conf().getString("storage.type", "SQLITE")!!.uppercase(Locale.ROOT)
        },
        EcoMetricsChart.simplePie("island_world_backend") { if (worldBackendReady) "asp" else "none" },
        EcoMetricsChart.simplePie("island_mobs_enabled") {
            conf().getBoolean("island-mobs.enabled", false).toString()
        },
        EcoMetricsChart.simplePie("perks_enabled") { (perkService?.enabled() == true).toString() }
    )

    /**
     * Register the commands with eco.
     *
     * eco injects them into the server's command map itself, so neither needs a `commands:` entry in
     * plugin.yml any more — the name, aliases, description, permission and players-only flag all come
     * from the command class, which is where every other plugin in the suite declares them.
     */
    private fun registerCommands() {
        CommandIsland(this).register()
        BankCommand(this).register()
    }

    /** Whether a Vault economy is present and ready (bank & coin costs depend on it). */
    fun economyReady(): Boolean = vaultHook?.isReady == true

    /** The player's Vault wallet balance (0 if no economy). Used by the coop-bank "deposit all" button. */
    fun purseBalance(player: Player): Double = vaultHook?.balance(player) ?: 0.0

    /** Guarded Vault lookup — only links `net.milkbowl.vault.*` when Vault is actually present. */
    private fun resolveVault(): VaultHook? {
        if (server.pluginManager.getPlugin("Vault") == null) {
            return null
        }
        return try {
            Class.forName("net.milkbowl.vault.economy.Economy")
            VaultHook().takeIf { it.isReady }
        } catch (notVault: Throwable) {
            null
        }
    }

    /** Whether WorldEdit/FAWE is on the classpath (so we can safely load the WE schematic impl). */
    private fun worldEditAvailable(): Boolean {
        if (!server.pluginManager.isPluginEnabled("WorldEdit")
            && !server.pluginManager.isPluginEnabled("FastAsyncWorldEdit")
        ) {
            return false
        }
        return try {
            Class.forName("com.sk89q.worldedit.WorldEdit", false, javaClass.classLoader)
            true
        } catch (noWorldEdit: ClassNotFoundException) {
            false
        }
    }

    /** Whether the ASP world API is on the classpath (i.e. the server is the ASP fork). */
    private fun aspAvailable(): Boolean = try {
        Class.forName("com.infernalsuite.asp.api.AdvancedSlimePaperAPI", false, javaClass.classLoader)
        true
    } catch (notAsp: ClassNotFoundException) {
        false
    }

    /** Human-readable intimidation state for a player — what /is admin mobspawn status shows. */
    fun intimidationSummary(player: Player): String {
        val combat = combatSource
        val intimidation = intimidationSource
        if (combat == null || intimidation == null) {
            return "intimidation bridge not active"
        }
        val combatLevel = combat.levelOf(player)
        val stat = intimidation.levelOf(player)
        val ignore = if (conf().getBoolean("island-mobs.intimidation.cap-to-combat-level", true)) {
            minOf(combatLevel, stat)
        } else {
            stat
        }
        return "combat $combatLevel, intimidation $stat -> island mobs of level $ignore and below ignore you"
    }

    /**
     * Stand up island mob spawning if it's enabled and a usable provider is installed. Soft in every
     * direction: no EcoMobs -> skip; no EcoSkills -> mobs fall back to level 1; disabled -> nothing runs.
     */
    private fun startIslandMobSpawning() {
        if (!conf().getBoolean("island-mobs.enabled", false)) {
            return
        }
        val providerId = conf().getString("island-mobs.provider", "ecomobs")!!.lowercase(Locale.ROOT)
        var provider: IslandMobProvider? = null
        if (providerId == "ecomobs" && server.pluginManager.isPluginEnabled("EcoMobs")) {
            provider = EcoMobsIslandMobProvider()
        }
        // future: mythicmobs, leveledmobs — add here, no other change needed.
        if (provider == null || !provider.available()) {
            logger.warning(
                "island-mobs is enabled but provider '$providerId' isn't available — island mob spawning is off."
            )
            return
        }

        var combat = CombatLevelSource { 1 }
        val skillId = conf().getString("island-mobs.combat-skill", "combat")
        if (server.pluginManager.isPluginEnabled("EcoSkills")) {
            val ecoSkills = EcoSkillsCombatSource(skillId, 1)
            if (ecoSkills.valid()) {
                combat = ecoSkills
            } else {
                logger.warning("EcoSkills has no '$skillId' skill — island mobs default to level 1.")
            }
        } else {
            logger.info("EcoSkills not installed — island mobs default to level 1.")
        }

        val service = IslandMobSpawnService(this, provider, combat)
        this.mobSpawnService = service
        service.start()
        logger.info("Island mob spawning: provider ${provider.id()}, ${service.familyCount()} families.")

        // Intimidation targeting bridge. The Talismans intimidation chain only GRANTS the stat
        // (add_stat) — the stat itself has no effects, so this is what actually makes weak island
        // mobs ignore the player.
        if (conf().getBoolean("island-mobs.intimidation.enabled", true)
            && server.pluginManager.isPluginEnabled("EcoSkills")
        ) {
            val statId = conf().getString("island-mobs.intimidation.stat", "intimidation")
            val stat = EcoSkillsStatSource(statId, 0)
            if (stat.valid()) {
                this.combatSource = combat
                this.intimidationSource = stat
                server.pluginManager.registerEvents(IslandMobTargetingBridge(this, combat, stat), this)
                logger.info("Intimidation bridge active (stat: $statId).")
            } else {
                logger.warning("Intimidation bridge off — EcoSkills stat '$statId' unavailable.")
            }
        }
    }

    fun generators(): GeneratorService = generatorService!!

    fun upgrades(): UpgradeManager = upgradeManager!!

    fun levels(): LevelService = levelService!!

    fun unloads(): IslandUnloadService = unloadService!!

    /**
     * Offline-simulation registry. Register a [com.mystipixel.royalskyblock.api.BlockSimulator] from
     * your onEnable to teach RoyalSkyblock how a block catches up on time its island spent unloaded;
     * register nothing for a material and that material simply doesn't grow.
     */
    fun simulators(): IslandScanner = islandScanner!!

    fun perks(): PerkService = perkService!!

    fun gamemodes(): GamemodeManager = gamemodeManager!!

    fun currency(): CurrencyService = currencyService!!

    fun messages(): MessageManager = messageManager!!

    fun gui(): GuiManager = guiManager!!

    fun bank(): BankService = bankService!!

    fun borders(): BorderService = borderService!!

    fun islands(): IslandManager = islandManager!!

    fun worldRules(): IslandWorldRules = worldRules!!

    /** Null when island mob spawning is disabled or no provider was available. */
    fun mobSpawns(): IslandMobSpawnService? = mobSpawnService

    fun schematics(): SchematicService = schematicService!!

    fun worlds(): IslandWorldService = worldService!!

    fun storage(): Storage = storage!!

    fun profiles(): ProfileManager = profileManager!!

    fun playerState(): PlayerStateService = stateService!!

    fun isWorldBackendReady(): Boolean = worldBackendReady

    fun eco(): EcoProfileBridge = ecoBridge!!

    fun ecoSlot(player: UUID): Int = ecoSlot.getOrDefault(player, 1)

    fun setEcoSlot(player: UUID, slot: Int) {
        ecoSlot[player] = slot
    }
}

private fun rootMessage(t: Throwable): String {
    var cause: Throwable = t
    while (cause.cause != null) {
        cause = cause.cause!!
    }
    return cause.message ?: cause.javaClass.simpleName
}
