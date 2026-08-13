package com.mystipixel.royalskyblock.libreforge

import com.willfp.libreforge.toDispatcher
import com.willfp.libreforge.triggers.Trigger
import com.willfp.libreforge.triggers.TriggerData
import com.willfp.libreforge.triggers.TriggerParameter
import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * A libreforge trigger this plugin fires itself, rather than one that listens for a Bukkit event.
 *
 * Triggers normally carry their own `@EventHandler`, which needs the event type at compile time. Both
 * of RoyalSkyblock's trigger families are dispatched from elsewhere instead — the minion ones from a
 * reflectively-registered executor, because EcoMinions is not a compile dependency, and the island
 * ones from the plugin's own service code, because "an island levelled up" is not a Bukkit event.
 * `Trigger.dispatch` is public, so neither needs special access.
 *
 * Every trigger built on this carries the same four parameters, which keeps configs predictable: the
 * player it happened to, where, a `text` naming the subject (a minion type, an upgrade track, an
 * island world) and a `value` that means something per trigger. `text` is what lets a config target
 * one kind of thing without the plugin inventing a filter for it.
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
