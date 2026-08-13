package com.mystipixel.royalskyblock.libreforge

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent

/**
 * Keeps libreforge's cached holders honest across the two events that silently change which island a
 * player is standing on. Every island is its own world, so crossing a world boundary *is* entering or
 * leaving an island — without this a player would keep an island's buffs after teleporting away, and
 * arrive at their own island with none.
 */
class RoyalHolderListener : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChangeWorld(event: PlayerChangedWorldEvent) {
        RoyalHolders.refresh(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        RoyalHolders.refresh(event.player)
    }
}
