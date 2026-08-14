package com.mystipixel.royalskyblock.command

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin
import com.mystipixel.royalskyblock.profile.Profile
import com.willfp.eco.core.command.impl.PluginCommand
import com.willfp.eco.core.command.impl.Subcommand
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale

/**
 * `/island` on eco's command framework.
 *
 * Twenty-two of the twenty-three plugins in the suite register their commands this way, and this was
 * the last place RoyalSkyblock still did something of its own. Moving over means eco handles
 * registration, aliases, permission and players-only gating, subcommand dispatch and the tab
 * completion of subcommand names — and it does the gating with the strings in this plugin's own
 * `lang.yml`, under `messages.no-permission` / `messages.not-player`. Those keys have shipped since
 * lang.yml was written, labelled "eco framework strings", and were inert until now.
 *
 * ## What this file is and is not
 *
 * It is the command *tree*: which subcommands exist, what each needs, and what completes after it.
 * The behaviour still lives in [IslandCommand], which is now a plain holder of handlers rather than a
 * `CommandExecutor`. Keeping the two apart is what made the port safe to do in one pass — nothing
 * inside a handler moved, so nothing inside a handler could break.
 *
 * ## Permissions
 *
 * A subcommand declares a permission **only where the handler already enforced one**. `plugin.yml`
 * also declares `royalskyblock.home`, `.upgrade` and `.bank`, which nothing has ever checked; gating
 * them here would be a new restriction dressed up as a refactor, so they stay unenforced and stay
 * that way deliberately rather than by omission.
 *
 * ## `profile` and `admin`
 *
 * Both route their own second level rather than nesting eco subcommands. Their thirteen handlers take
 * an already-resolved [Player] and read a full argument array, so nesting them would mean rewriting
 * every one of those signatures to buy a player exactly nothing: the completion below is the part
 * anyone actually notices, and it is here. That is a deliberate stop, not an unfinished edge.
 */
class CommandIsland(private val plugin: RoyalSkyblockPlugin) :
    PluginCommand(plugin, "island", "", false) {

    private val handlers = IslandCommand(plugin)

    init {
        // Plain actions, in the order /is help lists them.
        leaf("menu") { s, _ -> handlers.handleMenu(s) }
        leaf("create", permission = "royalskyblock.create") { s, _ -> handlers.handleCreate(s) }
        leaf("home") { s, _ -> handlers.handleHome(s) }
        leaf("go") { s, _ -> handlers.handleHome(s) }
        leaf("visit", permission = "royalskyblock.visit", complete = ::onlinePlayers) { s, a ->
            handlers.handleVisit(s, a)
        }
        leaf("accept") { s, _ -> handlers.handleAccept(s) }
        leaf("deny") { s, _ -> handlers.handleDeny(s) }
        leaf("decline") { s, _ -> handlers.handleDeny(s) }
        leaf("leave") { s, _ -> handlers.handleLeave(s) }
        leaf("members") { s, _ -> handlers.handleMembers(s) }
        leaf("manage") { s, _ -> handlers.handleManage(s) }
        leaf("bank") { s, _ -> handlers.handleBank(s) }
        leaf("top") { s, _ -> handlers.handleTop(s) }
        leaf("perks") { s, _ -> handlers.handlePerks(s) }
        leaf("settings", permission = "royalskyblock.settings") { s, _ -> handlers.handleSettings(s) }
        leaf("upgrade") { s, _ -> handlers.handleUpgrades(s) }
        leaf("upgrades") { s, _ -> handlers.handleUpgrades(s) }
        leaf("sethome") { s, _ -> handlers.handleSetSpawn(s, false) }
        leaf("setspawn") { s, _ -> handlers.handleSetSpawn(s, false) }
        leaf("setguestspawn") { s, _ -> handlers.handleSetSpawn(s, true) }
        leaf("kickall") { s, _ -> handlers.handleKickAll(s) }

        // Actions taking a member of your own island.
        leaf("invite", permission = "royalskyblock.invite", complete = ::onlinePlayers) { s, a ->
            handlers.handleInvite(s, a)
        }
        leaf("kick", complete = ::otherMembers) { s, a -> handlers.handleKick(s, a) }
        leaf("transfer", complete = ::otherMembers) { s, a -> handlers.handleTransfer(s, a) }
        leaf("promote", complete = ::otherMembers) { s, a -> handlers.handlePromote(s, a) }
        leaf("demote", complete = ::otherMembers) { s, a -> handlers.handleDemote(s, a) }

        leaf("level", complete = { _, args -> firstArg(args, listOf("recalc")) }) { s, a ->
            handlers.handleLevel(s, a)
        }
        // Deleting an island asks for the word rather than a click-through, so it completes it.
        leaf("delete", complete = { _, args -> firstArg(args, listOf("confirm")) }) { s, a ->
            handlers.handleDelete(s, a)
        }

        leaf("reload", permission = "royalskyblock.admin", playersOnly = false) { s, _ ->
            handlers.handleReload(s)
        }

        for (name in listOf("profile", "profiles")) {
            leaf(name, complete = ::completeProfile) { s, a -> handlers.handleProfile(s, a) }
        }
        leaf("admin", permission = "royalskyblock.admin", playersOnly = false, complete = ::completeAdmin) { s, a ->
            handlers.handleAdmin(s, a)
        }
    }

    /**
     * `/is` on its own, and anything eco could not match to a subcommand.
     *
     * eco routes an unrecognised subcommand here rather than reporting one, so telling someone they
     * mistyped has to be done on purpose or `/is hoem` silently prints the help screen as though it
     * had worked. `help` is matched explicitly because it never was a subcommand — the old dispatcher
     * answered `/is help` with "Unknown subcommand /is help. Try /is help.", which the help text
     * itself tells people to run.
     */
    override fun onExecute(sender: CommandSender, args: List<String>) {
        val first = args.firstOrNull()
        if (first != null && !first.equals("help", ignoreCase = true)) {
            plugin.messages().sendPlain(sender, "general.unknown-subcommand", "command", first)
            return
        }
        handlers.sendHelp(sender)
    }

    override fun getAliases(): List<String> = listOf("is", "sb", "skyblock")

    override fun getDescription(): String = "RoyalSkyblock island command."

    /**
     * Register one subcommand.
     *
     * The handler is handed the argument array it has always been handed — its own name first, then
     * the rest — because eco strips the subcommand name and every handler reads `args[1]` onwards.
     * Rebuilding it here rather than reindexing thirty handlers is the whole reason this port did not
     * need to touch their bodies.
     */
    private fun leaf(
        name: String,
        permission: String = "",
        playersOnly: Boolean = true,
        complete: (CommandSender, List<String>) -> List<String> = { _, _ -> emptyList() },
        run: (CommandSender, Array<String>) -> Unit
    ) {
        addSubcommand(object : Subcommand(plugin, name, permission, playersOnly) {
            override fun onExecute(sender: CommandSender, args: List<String>) {
                run(sender, (listOf(name) + args).toTypedArray())
            }

            override fun tabComplete(sender: CommandSender, args: List<String>): List<String> =
                complete(sender, args)
        })
    }

    // ── completion ─────────────────────────────────────────────────────────────

    private fun firstArg(args: List<String>, options: List<String>): List<String> =
        if (args.size <= 1) startingWith(options, args.lastOrNull()) else emptyList()

    private fun onlinePlayers(sender: CommandSender, args: List<String>): List<String> =
        firstArg(args, plugin.server.onlinePlayers.map { it.name })

    /** Everyone on your island except you — the only people worth kicking or promoting. */
    private fun otherMembers(sender: CommandSender, args: List<String>): List<String> {
        val player = sender as? Player ?: return emptyList()
        val active = plugin.profiles().getActiveProfile(player) ?: return emptyList()
        return firstArg(args, active.members()
            .filter { it.uuid() != player.uniqueId }
            .mapNotNull { it.name() })
    }

    private fun completeProfile(sender: CommandSender, args: List<String>): List<String> {
        if (args.size <= 1) {
            return startingWith(listOf("list", "create", "switch", "delete"), args.lastOrNull())
        }
        if (args.size != 2) {
            return emptyList()
        }
        return when (args[0].lowercase(Locale.ROOT)) {
            "create" -> startingWith(listOf("solo", "coop", "ironman"), args[1])
            "switch", "delete" -> {
                val player = sender as? Player ?: return emptyList()
                startingWith(plugin.profiles().getProfiles(player.uniqueId).map(Profile::name), args[1])
            }
            else -> emptyList()
        }
    }

    private fun completeAdmin(sender: CommandSender, args: List<String>): List<String> {
        if (args.size <= 1) {
            return startingWith(listOf("status", "border", "mobspawn", "testworld", "loadtest",
                "schematic", "upgrade", "chesttest", "split-content"), args.lastOrNull())
        }
        if (args.size != 2) {
            return emptyList()
        }
        return when (args[0].lowercase(Locale.ROOT)) {
            "border" -> startingWith(listOf("blue", "red", "green", "off"), args[1])
            "schematic" -> startingWith(listOf("save"), args[1])
            "split-content" -> startingWith(listOf("confirm"), args[1])
            "mobspawn" -> startingWith(listOf("status", "test"), args[1])
            "upgrade" -> startingWith(plugin.upgrades().all().map { it.key() }, args[1])
            else -> emptyList()
        }
    }

    private fun startingWith(options: List<String>, prefix: String?): List<String> {
        val p = (prefix ?: "").lowercase(Locale.ROOT)
        return options.filter { it.lowercase(Locale.ROOT).startsWith(p) }
    }
}
