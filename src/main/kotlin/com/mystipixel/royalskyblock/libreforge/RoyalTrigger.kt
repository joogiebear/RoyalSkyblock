package com.mystipixel.royalskyblock.libreforge

import com.willfp.libreforge.toDispatcher
import com.willfp.libreforge.triggers.Trigger
import com.willfp.libreforge.triggers.TriggerData
import com.willfp.libreforge.triggers.TriggerParameter
import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * A libreforge trigger this plugin dispatches itself rather than from a Bukkit event listener (minion
 * triggers from a reflectively registered executor, island triggers from the plugin's services).
 *
 * Every one carries the same parameters: the player, the location, a `text` naming the subject (a
 * minion type, an upgrade track, an island world) and a per-trigger `value`.
 */
class RoyalTrigger(
    id: String,
    override val description: String,
    category: String
) : Trigger(id) {

    override val parameters = setOf(
        TriggerParameter.PLAYER,
        TriggerParameter.LOCATION,
        TriggerParameter.TEXT,
        TriggerParameter.VALUE
    )

    override val categories = setOf(category)

    /** Dispatch to this player. [location] falls back to the player's own when the caller has none. */
    fun fire(player: Player, location: Location?, subject: String?, value: Double) {
        dispatch(
            player.toDispatcher(),
            TriggerData(
                player = player,
                location = location ?: player.location,
                text = subject ?: "",
                value = value
            )
        )
    }
}
