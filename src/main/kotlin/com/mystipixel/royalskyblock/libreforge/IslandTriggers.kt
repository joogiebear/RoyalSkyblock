package com.mystipixel.royalskyblock.libreforge

import com.mystipixel.royalskyblock.island.Island
import com.willfp.libreforge.triggers.Triggers
import org.bukkit.entity.Player

/**
 * Island events, published to libreforge as triggers. The plugin's own services call [fire] where the
 * thing happens; there is no Bukkit event behind them.
 *
 * Each is dispatched per online member (a trigger needs a player, and an island belongs to a profile),
 * so a level-up with three members online fires three times.
 */
object IslandTriggers {

    /** Fires for each online member when their island crosses an integer level. `value` is the new level. */
    val LEVEL_UP = RoyalTrigger(
        "island_level_up",
        "Fires for each online member when their island reaches a new level.",
        "skyblock"
    )

    /** Fires when a player buys an upgrade tier. `text` is the track id, `value` the tier bought. */
    val UPGRADE_PURCHASE = RoyalTrigger(
        "island_upgrade_purchase",
        "Fires when a player purchases an island upgrade tier.",
        "skyblock"
    )

    /** Fires when a player walks onto an island. `text` is the island's world, `value` its level. */
    val ENTER = RoyalTrigger(
        "island_enter",
        "Fires when a player enters an island.",
        "skyblock"
    )

    /** Fires when a player leaves an island. `text` is the island's world, `value` its level. */
    val LEAVE = RoyalTrigger(
        "island_leave",
        "Fires when a player leaves an island.",
        "skyblock"
    )

    /** Register every island trigger. Must run before any config that uses them is compiled. */
    @JvmStatic
    fun register() {
        Triggers.register(LEVEL_UP)
        Triggers.register(UPGRADE_PURCHASE)
        Triggers.register(ENTER)
        Triggers.register(LEAVE)
    }

    /** Called by LevelService for each online member as an island crosses a level. */
    @JvmStatic
    fun levelUp(player: Player, island: Island, level: Int) {
        LEVEL_UP.fire(player, player.location, island.worldName(), level.toDouble())
    }

    /** Called by UpgradeManager when a player pays for a tier. */
    @JvmStatic
    fun upgradePurchased(player: Player, track: String, tier: Int) {
        UPGRADE_PURCHASE.fire(player, player.location, track, tier.toDouble())
    }

    /** Called when a player crosses into or out of an island world. */
    @JvmStatic
    fun crossed(player: Player, island: Island, entering: Boolean) {
        val trigger = if (entering) ENTER else LEAVE
        trigger.fire(player, player.location, island.worldName(), island.level())
    }
}
