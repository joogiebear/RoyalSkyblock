package com.mystipixel.royalskyblock.command

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin
import com.mystipixel.royalskyblock.profile.Profile
import com.willfp.eco.core.command.impl.PluginCommand
import com.willfp.eco.core.command.impl.Subcommand
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale

/**
 * `/island` on eco's command framework: eco handles registration, aliases, permission and players-only
 * gating (with `messages.no-permission` / `messages.not-player` from lang.yml), dispatch and
 * subcommand completion. This file is the command tree; the behaviour lives in [IslandCommand].
 *
 * A subcommand declares its own permission only where the handler enforces one; everything else needs
 * `royalskyblock.use`. Never pass `""`: eco checks the string as given, and Bukkit treats an unknown
 * permission as op-only.
 *
 * `profile` and `admin` route their own second level, since their handlers read the full argument array.
 */
class CommandIsland(private val plugin: RoyalSkyblockPlugin) :
    PluginCommand(plugin, "island", "royalskyblock.use", false) {

    private val handlers = IslandCommand(plugin)

    init {
        // plain actions, in the order /is help lists them
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

        // actions taking a member of your own island
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
        // deleting an island asks for the word rather than a click-through, so it completes it
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
     * `/is` on its own, and anything eco couldn't match to a subcommand. eco routes unknown subcommands
     * here, so a mistype has to be reported explicitly; `help` is matched explicitly too.
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

    // Handlers get the argument array they always had (their own name first, then the rest), since eco
    // strips the subcommand name and every handler reads args[1] onwards.
    private fun leaf(
        name: String,
        permission: String = "royalskyblock.use",
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

    private fun firstArg(args: List<String>, options: List<String>): List<String> =
        if (args.size <= 1) startingWith(options, args.lastOrNull()) else emptyList()

    // online players the sender can see; a vanished player must not show up in tab completion
    private fun onlinePlayers(sender: CommandSender, args: List<String>): List<String> =
        firstArg(args, plugin.server.onlinePlayers
            .filter { sender !is Player || sender.canSee(it) }
            .map { it.name })

    // everyone on your island except you
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
                "schematic", "upgrade", "chesttest", "split-content", "trash", "orphans", "npc-open"), args.lastOrNull())
        }
        if (args.size != 2) {
            return emptyList()
        }
        return when (args[0].lowercase(Locale.ROOT)) {
            "border" -> startingWith(listOf("blue", "red", "green", "off"), args[1])
            "schematic" -> startingWith(listOf("save"), args[1])
            "split-content" -> startingWith(listOf("confirm"), args[1])
            "mobspawn" -> startingWith(listOf("status", "test"), args[1])
            "trash" -> startingWith(listOf("list", "restore"), args[1])
            "orphans" -> startingWith(listOf("purge"), args[1])
            "upgrade" -> startingWith(plugin.upgrades().all().map { it.key() }, args[1])
            else -> emptyList()
        }
    }

    private fun startingWith(options: List<String>, prefix: String?): List<String> {
        val p = (prefix ?: "").lowercase(Locale.ROOT)
        return options.filter { it.lowercase(Locale.ROOT).startsWith(p) }
    }
}
