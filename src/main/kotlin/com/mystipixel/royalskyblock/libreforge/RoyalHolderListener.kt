package com.mystipixel.royalskyblock.libreforge

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import com.mystipixel.royalskyblock.RoyalSkyblockPlugin
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent

/**
 * Refreshes libreforge's cached holders when a player changes world, which on islands means entering or
 * leaving one; otherwise island buffs would stick after teleporting away.
 */
class RoyalHolderListener : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChangeWorld(event: PlayerChangedWorldEvent) {
        RoyalHolders.refresh(event.player)

        val islands = RoyalSkyblockPlugin.get().islands()
        islands.getIslandByWorld(event.from)?.let { IslandTriggers.crossed(event.player, it, false) }
        islands.getIslandByWorld(event.player.world)?.let { IslandTriggers.crossed(event.player, it, true) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        RoyalHolders.refresh(event.player)
    }
}
