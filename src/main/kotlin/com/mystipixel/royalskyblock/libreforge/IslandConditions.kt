package com.mystipixel.royalskyblock.libreforge

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin
import com.mystipixel.royalskyblock.island.Island
import com.willfp.eco.core.config.interfaces.Config
import com.willfp.libreforge.ConfigArgumentsBuilder
import com.willfp.libreforge.Dispatcher
import com.willfp.libreforge.NoCompileData
import com.willfp.libreforge.ProvidedHolder
import com.willfp.libreforge.arguments
import com.willfp.libreforge.conditions.Condition
import com.willfp.libreforge.conditions.Conditions
import com.willfp.libreforge.get
import org.bukkit.entity.Player

/**
 * RoyalSkyblock's island state, published to libreforge as conditions.
 *
 * Until these existed the plugin was a curious one-way citizen of the suite: it registered EcoMinions'
 * activity so everything else could react to a minion, while nothing anywhere — including its own
 * perks and upgrades — could ask a single question about an island. Answering "is this player home?"
 * meant writing Java.
 *
 * Because libreforge shares elements across every plugin that uses it, registering them here means an
 * EcoItem can be gated on island level, a quest can require co-op membership, a Talisman can only work
 * at home, and RoyalSkyblock's own perks can drop their hand-rolled checks in favour of config.
 *
 * All of these resolve a [Player] from the dispatcher and fail closed: no player, no island, or a
 * service not yet up all mean "not met" rather than an exception inside someone else's effect chain.
 */
object IslandConditions {

    /** Register every island condition. Must run before any config that uses them is compiled. */
    fun register() {
        Conditions.register(OnOwnIsland)
        Conditions.register(OnAnyIsland)
        Conditions.register(IslandLevelAbove)
        Conditions.register(HasIslandUpgrade)
        Conditions.register(IsIslandMember)
    }

    /** The island the player is physically standing on, or null. */
    private fun islandUnderfoot(player: Player): Island? {
        val plugin = RoyalSkyblockPlugin.get()
        return plugin.islands().getIslandByWorld(player.world)
    }

    /** The island belonging to the player's *active profile*, which may not be the one they are on. */
    private fun ownIsland(player: Player): Island? {
        val plugin = RoyalSkyblockPlugin.get()
        val active = plugin.profiles().getActiveProfileId(player.uniqueId) ?: return null
        return plugin.islands().getIslandByProfile(active)
    }

    /**
     * `on_own_island` — the player is standing on the island of their own active profile.
     *
     * The distinction from [OnAnyIsland] matters: a visitor is on *an* island, not *theirs*. This is
     * the condition perks already imply in Java, and the one most content will want.
     */
    object OnOwnIsland : Condition<NoCompileData>("on_own_island") {
        override val description = "Passes when the player is on the island of their active profile."
        override val categories = setOf("skyblock")

        override fun isMet(
            dispatcher: Dispatcher<*>, config: Config, holder: ProvidedHolder, compileData: NoCompileData
        ): Boolean {
            val player = dispatcher.get<Player>() ?: return false
            val here = islandUnderfoot(player) ?: return false
            return ownIsland(player)?.id() == here.id()
        }
    }

    /** `on_any_island` — the player is on some island world, theirs or anyone's. */
    object OnAnyIsland : Condition<NoCompileData>("on_any_island") {
        override val description = "Passes when the player is on any island, including someone else's."
        override val categories = setOf("skyblock")

        override fun isMet(
            dispatcher: Dispatcher<*>, config: Config, holder: ProvidedHolder, compileData: NoCompileData
        ): Boolean {
            val player = dispatcher.get<Player>() ?: return false
            return islandUnderfoot(player) != null
        }
    }

    /**
     * `island_level_above` — the island the player is standing on is above the given level.
     *
     * Reads the island underfoot rather than the player's own, so a visitor is judged by the island
     * they are actually on. Pair with [OnOwnIsland] when you mean "their own island is this good".
     */
    object IslandLevelAbove : Condition<NoCompileData>("island_level_above") {
        override val description = "Passes when the island the player is on is above the given level."
        override val categories = setOf("skyblock")

        override val arguments = arguments {
            requireStable("level", "You must specify the island level!")
        }

        override fun isMet(
            dispatcher: Dispatcher<*>, config: Config, holder: ProvidedHolder, compileData: NoCompileData
        ): Boolean {
            val player = dispatcher.get<Player>() ?: return false
            val island = islandUnderfoot(player) ?: return false
            return island.level() > config.getDouble("level")
        }
    }

    /**
     * `has_island_upgrade` — the island has bought at least the given tier of an upgrade track.
     *
     * `tier` is optional and defaults to 1, so the common case reads as "has this upgrade at all".
     */
    object HasIslandUpgrade : Condition<NoCompileData>("has_island_upgrade") {
        override val description = "Passes when the island has an upgrade track at or above a tier."
        override val categories = setOf("skyblock")

        override val arguments = arguments {
            requireStable("upgrade", "You must specify the upgrade track!")
        }

        override fun isMet(
            dispatcher: Dispatcher<*>, config: Config, holder: ProvidedHolder, compileData: NoCompileData
        ): Boolean {
            val player = dispatcher.get<Player>() ?: return false
            val island = islandUnderfoot(player) ?: return false
            val track = config.getString("upgrade") ?: return false
            val required = if (config.has("tier")) config.getInt("tier") else 1
            return island.upgradeTier(track) >= required
        }
    }

    /**
     * `is_island_member` — the player is a member of the island's profile, at any role.
     *
     * True for the owner and every co-op member, false for a visitor. This is the condition that
     * separates "someone who lives here" from "someone passing through", which co-op content wants far
     * more often than ownership.
     */
    object IsIslandMember : Condition<NoCompileData>("is_island_member") {
        override val description = "Passes when the player is a member of the island they are on."
        override val categories = setOf("skyblock")

        override fun isMet(
            dispatcher: Dispatcher<*>, config: Config, holder: ProvidedHolder, compileData: NoCompileData
        ): Boolean {
            val player = dispatcher.get<Player>() ?: return false
            val island = islandUnderfoot(player) ?: return false
            val profile = RoyalSkyblockPlugin.get().profiles().getProfile(island.profileId()) ?: return false
            return profile.isMember(player.uniqueId)
        }
    }
}

/**
 * `require(name, message)` without the binary fragility.
 *
 * libreforge's two-argument `require` is a Kotlin default-argument call, so callers link against a
 * synthetic `require$default` bridge whose signature includes every parameter. Auxilor adds
 * parameters to it now and then (2026.35.1 did), and each time that happens a jar compiled against
 * an older libreforge throws NoSuchMethodError in this class's static initialiser and RoyalSkyblock
 * fails to enable. The four-argument overload has no defaults, so it is a plain method call that
 * survives those additions. The getter and predicate are exactly libreforge's own defaults.
 */
private fun ConfigArgumentsBuilder.requireStable(name: String, message: String) {
    require<Any?>(name, message, { key: String -> this.get(key) }, { value: Any? -> value != null })
}
