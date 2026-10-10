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
import com.mystipixel.royalskyblock.hooks.EcoProfileBridge
import com.mystipixel.royalskyblock.hooks.EcoProfileResolver
import com.mystipixel.royalskyblock.api.Integrations
import com.mystipixel.royalskyblock.api.ProgressionProvider
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
import com.mystipixel.royalskyblock.libreforge.EcoPlaceholders
import com.mystipixel.royalskyblock.libreforge.IslandConditions
import com.mystipixel.royalskyblock.libreforge.IslandTriggers
import com.mystipixel.royalskyblock.libreforge.MenuChains
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
 * RoyalSkyblock entry point. Every island is its own Advanced Slime Paper world, loaded on demand and
 * unloaded when empty. Kotlin because [LibreforgePlugin]'s APIs are; the rest of the plugin is Java.
 *
 * `onEnable`/`onDisable`/`reload` are final on `EcoPlugin`, so the work is in
 * [handleEnable]/[handleDisable]/[handleReload]:
 *  - Every repeating task lives in [createTasks]. `EcoPlugin.reload()` cancels all of this plugin's
 *    tasks and then re-invokes [createTasks]; a task registered anywhere else dies on the first reload.
 *  - Config reads go through [conf], never `getConfig()`, which logs a warning on every call under eco.
 */
class RoyalSkyblockPlugin : LibreforgePlugin() {

    // Built with the plugin, not in handleEnable: eco enables extensions before the host's handleEnable,
    // and they register into this.
    private val integrationRegistry = Integrations()

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

    // wallet lookups for the bank "deposit all"
    private var vaultHook: VaultHook? = null
    private var ecoBridge: EcoProfileBridge? = null
    private var messageManager: MessageManager? = null
    private var guiManager: GuiManager? = null
    // resolves every placeholder; independent of PlaceholderAPI, both front ends share it
    private var placeholders: IslandPlaceholders? = null
    private var papiRegistered = false

    // SPIKE: which eco test slot each player is on (defaults to 1)
    private val ecoSlot: MutableMap<UUID, Int> = ConcurrentHashMap()

    // true once the ASP world backend initialised; when false, island world ops are unavailable
    @Volatile
    private var worldBackendReady = false

    // cached Bukkit view of config.yml; invalidated on reload and whenever setConfigValue writes
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

    override fun loadConfigCategories(): List<ConfigCategory> = emptyList()

    /**
     * Read-side config accessor. Use this instead of `getConfig()` everywhere in the plugin.
     *
     * Reads `config.yml` off disk rather than via `configYml.toBukkit()`, whose nested sections don't
     * round-trip as Bukkit sections (a section inside `currencies` comes back null). eco still owns
     * writing and merging the file. Safe from [handleEnable] onwards.
     */
    fun conf(): FileConfiguration {
        cachedConfig?.let { return it }
        val loaded = YamlConfiguration.loadConfiguration(File(dataFolder, "config.yml"))
        cachedConfig = loaded
        return loaded
    }

    /**
     * Persist a single `config.yml` value through eco's config. Not `conf().set(...)` + `saveConfig()`:
     * [conf] is a converted copy, so that write would be dropped on the next read.
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

    // SQLITE/MYSQL use this plugin's own database; ECO uses eco's data layer like the rest of the suite.
    // SQLite stays the default. Switching to ECO migrates islands.db on first boot (migrateSqliteIfPresent).
    private fun createStorage(): Storage {
        val type = conf().getString("storage.type", "SQLITE")!!.uppercase(Locale.ROOT)
        if (type == "ECO") {
            warnIfEcoStorageShared()
            return EcoStorage(this)
        }
        return SqlStorage(this)
    }

    // ECO storage is single-server: eco caches non-player data for the whole uptime and has no atomic
    // update, so servers sharing a handler drop each other's changes. Warn whenever the handler is shareable.
    private fun warnIfEcoStorageShared() {
        val ecoFolder = server.pluginManager.getPlugin("eco")?.dataFolder ?: return
        val handler = org.bukkit.configuration.file.YamlConfiguration
            .loadConfiguration(java.io.File(ecoFolder, "config.yml"))
            .getString("data-handler", "")!!.lowercase(Locale.ROOT)
        if (handler in setOf("mysql", "mariadb", "mongodb", "mongo")) {
            logger.warning("storage.type is ECO on eco's shared '$handler' handler. ECO storage is for ONE server:")
            logger.warning("  if more than one server runs RoyalSkyblock on this database, they overwrite each other's")
            logger.warning("  island list, pending upgrades and profile lists. For a network, use storage.type: mysql.")
        }
    }

    // Carry an existing islands.db into eco, once, on the boot that switches to it. Returns false to abort
    // startup rather than come up on a half-populated or empty store; the source file stays untouched
    // unless every row was written and read back.
    private fun migrateSqliteIfPresent(store: EcoStorage): Boolean {
        val file = File(dataFolder, conf().getString("storage.sqlite-file", "islands.db")!!)
        if (!file.isFile) {
            return true
        }
        // Migrated on an earlier boot: the marker can only have come from eco's storage, so eco saved the
        // migration and the file can go. (A blank marker just re-runs it; every write is keyed by a stable id.)
        if (store.migrationMarker() == file.name) {
            logger.info("eco has saved everything migrated from ${file.name}: retiring it now.")
            val confirmed = SqliteMigration(this, file, store)
            if (!confirmed.retireSource()) {
                return false
            }
            confirmed.cleanSidecars()
            return true
        }
        // A store with islands and no marker belongs to something else: stop rather than merge two servers'
        // islands. A retry after a half-finished run carries the marker, so it's fine.
        if (store.hasIslands() && store.migrationMarker().isBlank()) {
            logger.severe("${file.name} is present, but eco already holds islands that did not come")
            logger.severe("from a completed migration. Refusing to merge two sets of islands together. If")
            logger.severe("this server migrated last boot and then crashed, eco saved only part of it: move")
            logger.severe("eco's partial data aside, or set storage.type back to sqlite. ${file.name} is untouched.")
            return false
        }

        logger.info("storage.type is ECO and ${file.name} is present: migrating it into eco's data layer.")
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

        logger.info("Migrated ${report.summary()}: every row read back and matched.")
        store.setMigrationMarker(file.name)
        // Not retired yet: the read-back saw eco's memory, not its storage. The next boot confirms and retires it.
        logger.info("${file.name} is kept until the next restart confirms eco has saved all of it.")
        return true
    }

    // Background metadata writes go to one thread this plugin owns, so they run in order and can be
    // drained at shutdown (Bukkit cancels a disabling plugin's queued async tasks).
    private var storageWriter: java.util.concurrent.ExecutorService? = null

    /** Run [task] (a storage write) on the storage thread; inline if that thread is gone (shutdown). */
    fun writeAsync(task: Runnable) {
        val writer = storageWriter
        if (writer == null || writer.isShutdown) {
            task.run()
            return
        }
        writer.execute {
            try {
                task.run()
            } catch (e: Exception) {
                logger.warning("A background save failed: ${e.message}")
            }
        }
    }

    override fun handleEnable() {
        this.messageManager = MessageManager(this)
        this.storageWriter = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "RoyalSkyblock-storage").apply { isDaemon = true }
        }

        val store = createStorage()
        this.storage = store
        if (!store.connect()) {
            logger.severe("Storage failed to initialise: disabling RoyalSkyblock.")
            server.pluginManager.disablePlugin(this)
            return
        }
        if (store is EcoStorage && !migrateSqliteIfPresent(store)) {
            logger.severe("Disabling RoyalSkyblock rather than running on incomplete data.")
            server.pluginManager.disablePlugin(this)
            return
        }

        this.ecoBridge = EcoProfileBridge()
        // Prefer eco resolving a player's data to their active profile (the copying bridge moves every key
        // twice per switch). Only on an eco that supports it; otherwise the bridge stays in charge.
        if (conf().getBoolean("profiles.eco-resolver", true) && ecoBridge!!.isPresent) {
            if (EcoProfileResolver.install(this)) {
                ecoBridge!!.isResolverActive = true
                logger.info("eco resolves profiles directly: progression is per-profile with no copying.")
            }
        }
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

        // Bring up the world backend asynchronously. Without ASP the plugin stays enabled but island world ops
        // are flagged unavailable so commands can explain.
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

        // Registered with eco unconditionally, so %royalskyblock_...% resolves in eco configs without
        // PlaceholderAPI; the PAPI expansion is an extra front end onto the same resolver.
        val resolver = IslandPlaceholders(this)
        this.placeholders = resolver
        EcoPlaceholders.register(this, resolver)
        if (server.pluginManager.isPluginEnabled("PlaceholderAPI")) {
            papiRegistered = RoyalSkyblockExpansion(this, resolver).register()
        }

        // resume in-progress upgrade timers and complete any that elapsed while offline
        upgradeManager!!.loadPending()

        // Offline simulation: the scanner dispatches a returning island's blocks to registered
        // BlockSimulators. Non-block things (minions) listen to IslandCatchupEvent instead.
        val scanner = islandScanner!!
        scanner.register(AgeCropSimulator(this))
        scanner.register(StackingPlantSimulator(this))
        server.pluginManager.registerEvents(scanner, this)

        this.generatorService = GeneratorService(this)
        server.pluginManager.registerEvents(GeneratorListener(this), this)
        server.pluginManager.registerEvents(IslandPortalListener(this), this)

        // island state published to libreforge as conditions
        IslandConditions.register()
        IslandTriggers.register()
        MenuChains.register()

        // registered after the services they read and every element their chains may use
        RoyalHolders.register(this)
        server.pluginManager.registerEvents(RoyalHolderListener(), this)

        startIslandMobSpawning()

        logger.info(
            "RoyalSkyblock enabled; metadata store: "
                + conf().getString("storage.type", "sqlite")!!.uppercase(Locale.ROOT)
                + ", island world source: " + conf().getString("world.slime-data-source", "file") + "."
        )

        // eco only invokes createTasks() from reload(), so start the repeating tasks once here.
        createTasks()

        // Deferred a tick: worlds and Vault economies register during other plugins' enable, which may run
        // after ours.
        server.scheduler.runTask(this, Runnable { ConfigValidator(this).validate() })
        // the full status panel prints once the ASP backend finishes initialising (see above)
    }

    /**
     * Every repeating task. Re-invoked by eco after `reload()` cancels all of this plugin's tasks, so it
     * runs once per enable and once per reload, always after a cancellation.
     */
    override fun createTasks() {
        val upgrades = upgradeManager ?: return // enable aborted (storage failure); nothing to tick

        server.scheduler.runTaskTimer(this, Runnable { upgrades.tick() }, 40L, 20L)
        // live upgrade-menu countdowns (no-op when nothing is cooking)
        server.scheduler.runTaskTimer(this, Runnable { guiManager?.tickOpenMenus() }, 20L, 20L)
        // background level refresh for occupied islands (0 = off)
        val autoRecalcMinutes = levelService!!.config().autoRecalcMinutes()
        if (autoRecalcMinutes > 0) {
            val period = autoRecalcMinutes * 60L * 20L
            server.scheduler.runTaskTimer(
                this, Runnable { levelService?.autoRecalcActiveIslands() }, period, period
            )
        }
        // perks tick (no-op when perks are disabled)
        val perkPeriod = perkService!!.refreshSeconds() * 20L
        server.scheduler.runTaskTimer(this, Runnable { perkService?.tick() }, perkPeriod, perkPeriod)
        // drop empty island worlds
        server.scheduler.runTaskTimer(this, Runnable { unloadService?.tick() }, 200L, 100L)
        // trash retention pruning, shortly after startup and daily after
        server.scheduler.runTaskTimerAsynchronously(
            this, Runnable { islandManager?.trash()?.pruneOld() }, 20L * 120L, 20L * 60L * 60L * 24L
        )
        // Island mob spawning: null until startIslandMobSpawning has run; here it restarts the timer after a
        // reload. start() stops any old timer first.
        mobSpawnService?.start()

        // leaderboard refresh off-thread; the rank placeholder is served to eco too, so always keep it warm
        server.scheduler.runTaskTimerAsynchronously(this, Runnable {
            try {
                placeholders?.refreshLeaderboard()
            } catch (ex: Exception) {
                logger.warning("Placeholder leaderboard refresh failed: ${ex.message}")
            }
        }, 20L, 60L * 20L)
    }

    // one-glance boot summary: which dependencies are active and what to configure first
    private fun printStatusPanel() {
        val vault = vaultHook?.isReady == true
        val storageType = conf().getString("storage.type", "sqlite")!!.uppercase(Locale.ROOT)
        val worldSource = conf().getString("world.slime-data-source", "file")
        logger.info("======================== RoyalSkyblock ========================")
        logger.info(
            " Islands (ASP)    : " + if (worldBackendReady) "READY (source: $worldSource)"
            else "UNAVAILABLE: install Advanced Slime Paper (island create/teleport off)"
        )
        logger.info(" Economy (Vault)  : " + if (vault) "READY" else "NOT FOUND: bank & coin costs disabled")
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
            else "off (optional: set perks.enabled in config.yml)"
        )
        logger.info(
            " Placeholders     : registered with eco (%royalskyblock_...%)"
                + if (papiRegistered) " + PlaceholderAPI" else " (PlaceholderAPI not found)"
        )
        logger.info(" ---------------------------------------------------------------")
        logger.info(" Configure first  : spawn.world + currencies in config.yml")
        logger.info(" Commands: /is help  ·  Reload: /is reload  ·  See README.md")
        logger.info("===============================================================")
    }

    override fun handleDisable() {
        // Save everyone online and every loaded island synchronously: the scheduler is stopping, so the
        // normal async saves won't run.
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
                    // synchronous: Bukkit won't schedule tasks for a disabling plugin
                    worlds.saveIslandNow(world.name)
                    // Its metadata too, synchronously, stamped as unloaded so the downtime is caught up on next load.
                    islands.getIslandByWorld(world)?.let { island ->
                        island.setUnloadedAt(System.currentTimeMillis())
                        storage?.saveIsland(island)
                    }
                    savedIslands++
                } catch (e: Exception) {
                    logger.warning("Failed to save island ${world.name} on shutdown: ${e.message}")
                }
            }
        }
        logger.info("Shutdown save: $savedPlayers player(s), $savedIslands island(s).")

        // let queued background writes finish before the pool they write to is closed
        storageWriter?.let { writer ->
            writer.shutdown()
            if (!writer.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.warning("Some background saves did not finish within 15s of shutdown and were dropped.")
            }
        }
        storageWriter = null

        worldService?.shutdown()
        storage?.close()
        logger.info("RoyalSkyblock disabled.")
        instance = null
    }

    /**
     * Reload config and messages from disk. Called by `EcoPlugin.reload()` after it refreshes eco's
     * configs and cancels this plugin's tasks, and before it re-runs [createTasks]. Doesn't touch the
     * scheduler.
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
        // invalidate before the menus reload, which compiles every chain and reports broken ones
        MenuChains.invalidate()
        guiManager?.reload()
        mobSpawnService?.reloadSettings() // toggling island-mobs.enabled on/off still needs a restart
        RoyalHolders.reload(this) // recompile perk/upgrade effect chains and re-provide them
        ConfigValidator(this).validate()
    }

    /**
     * bStats custom charts. eco registers bStats itself from the id in `eco.yml`; owners opt out in
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

    // eco injects the commands into the command map, so none need a commands: entry in plugin.yml
    private fun registerCommands() {
        CommandIsland(this).register()
        BankCommand(this).register()
    }

    /** Whether a Vault economy is present and ready (bank & coin costs depend on it). */
    fun economyReady(): Boolean = vaultHook?.isReady == true

    /** The player's Vault wallet balance (0 if no economy). */
    fun purseBalance(player: Player): Double = vaultHook?.balance(player) ?: 0.0

    // only links net.milkbowl.vault.* when Vault is present
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

    // whether WorldEdit/FAWE is on the classpath, so the WE schematic impl can load
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

    // whether the ASP world API is on the classpath (the server is the ASP fork)
    private fun aspAvailable(): Boolean = try {
        Class.forName("com.infernalsuite.asp.api.AdvancedSlimePaperAPI", false, javaClass.classLoader)
        true
    } catch (notAsp: ClassNotFoundException) {
        false
    }

    /** Human-readable intimidation state for a player, shown by /is admin mobspawn status. */
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

    // The progression backend an admin named, or the only one installed when island-mobs.progression is
    // blank.
    private fun progressionBackend(): ProgressionProvider? {
        val configured = conf().getString("island-mobs.progression", "")?.trim().orEmpty()
        if (configured.isEmpty()) {
            return integrationRegistry.anyProgressionProvider()
        }
        val provider = integrationRegistry.progressionProvider(configured)
        if (provider == null) {
            logger.warning(
                "island-mobs.progression is '$configured' but nothing registered that backend. "
                    + "Registered: ${integrationRegistry.progressionProviderIds()}"
            )
        }
        return provider?.takeIf { it.available() }
    }

    // Start island mob spawning if enabled and a provider is installed. No mob backend: skip; no skills
    // backend: mobs fall back to level 1. Backends come from extensions.
    private fun startIslandMobSpawning() {
        if (!conf().getBoolean("island-mobs.enabled", false)) {
            return
        }
        val providerId = conf().getString("island-mobs.provider", "ecomobs")!!
        val provider = integrationRegistry.mobProvider(providerId)
        if (provider == null || !provider.available()) {
            logger.warning(
                "island-mobs is enabled but provider '$providerId' isn't available: island mob "
                    + "spawning is off. Registered providers: ${integrationRegistry.mobProviderIds()} "
                    + "(a backend comes from an extension in plugins/RoyalSkyblock/extensions/)."
            )
            return
        }

        var combat = CombatLevelSource { 1 }
        val skillId = conf().getString("island-mobs.combat-skill", "combat")!!
        val progression = progressionBackend()
        if (progression == null) {
            logger.info(
                "No skills backend registered: island mobs default to level 1. A backend comes from "
                    + "an extension in plugins/RoyalSkyblock/extensions/."
            )
        } else {
            val source = progression.skill(skillId, 1)
            if (source != null) {
                combat = source
            } else {
                logger.warning(
                    "${progression.id()} could not read skill '$skillId': island mobs default to level 1."
                )
            }
        }

        val service = IslandMobSpawnService(this, provider, combat)
        this.mobSpawnService = service
        service.start()
        logger.info("Island mob spawning: provider ${provider.id()}, ${service.familyCount()} families.")

        // the Talismans chain only grants the intimidation stat; this bridge makes weak island mobs ignore
        // the player
        if (conf().getBoolean("island-mobs.intimidation.enabled", true) && progression != null) {
            val statId = conf().getString("island-mobs.intimidation.stat", "intimidation")!!
            val stat = progression.stat(statId, 0)
            if (stat != null) {
                this.combatSource = combat
                this.intimidationSource = stat
                server.pluginManager.registerEvents(IslandMobTargetingBridge(this, combat, stat), this)
                logger.info("Intimidation bridge active (${progression.id()} stat: $statId).")
            } else {
                logger.warning(
                    "Intimidation bridge off: ${progression.id()} could not read stat '$statId'."
                )
            }
        }
    }

    /**
     * Where extensions register third-party backends (mob plugins, skills plugins). Safe to call from an
     * extension's `onEnable`: it exists from construction. See [Integrations].
     */
    fun integrations(): Integrations = integrationRegistry

    /**
     * Whether an extension may run, per `extensions.disabled` in config.yml. Extensions call this from
     * their own `onEnable` and return early if it says no. A missing or unreadable file reads as nothing
     * disabled.
     */
    fun extensionEnabled(name: String): Boolean =
        conf().getStringList("extensions.disabled").none { it.equals(name, ignoreCase = true) }

    fun generators(): GeneratorService = generatorService!!

    fun upgrades(): UpgradeManager = upgradeManager!!

    fun levels(): LevelService = levelService!!

    fun unloads(): IslandUnloadService = unloadService!!

    /**
     * Offline-simulation registry. Register a [com.mystipixel.royalskyblock.api.BlockSimulator] from your
     * onEnable to teach RoyalSkyblock how a block catches up on time its island spent unloaded.
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

    /** The profile manager, or null before it exists (the resolver runs during startup). */
    fun profilesOrNull(): ProfileManager? = profileManager

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
