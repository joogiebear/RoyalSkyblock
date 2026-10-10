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
 * RoyalSkyblock's island state, published to libreforge as conditions usable by any eco plugin. All
 * resolve a [Player] from the dispatcher and fail closed: no player, no island, or a service not yet up
 * means not met.
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

    // the island the player is physically standing on, or null
    private fun islandUnderfoot(player: Player): Island? {
        val plugin = RoyalSkyblockPlugin.get()
        return plugin.islands().getIslandByWorld(player.world)
    }

    // the island of the player's active profile, which may not be the one they are on
    private fun ownIsland(player: Player): Island? {
        val plugin = RoyalSkyblockPlugin.get()
        val active = plugin.profiles().getActiveProfileId(player.uniqueId) ?: return null
        return plugin.islands().getIslandByProfile(active)
    }

    /**
     * `on_own_island`: the player is standing on their own active profile's island. A visitor is on
     * [OnAnyIsland] but not this.
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

    /** `on_any_island`: the player is on some island world, theirs or anyone's. */
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
     * `island_level_above`: the island the player is standing on is above the given level. Pair with
     * [OnOwnIsland] to mean their own island.
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
     * `has_island_upgrade`: the island has bought at least the given tier of an upgrade track. `tier` is
     * optional and defaults to 1.
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

    /** `is_island_member`: the player is a member of the island's profile at any role (not a visitor). */
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

// The four-argument require has no default arguments, so it doesn't link against libreforge's
// synthetic require$default bridge, whose signature changes between versions (NoSuchMethodError).
private fun ConfigArgumentsBuilder.requireStable(name: String, message: String) {
    require<Any?>(name, message, { key: String -> this.get(key) }, { value: Any? -> value != null })
}
