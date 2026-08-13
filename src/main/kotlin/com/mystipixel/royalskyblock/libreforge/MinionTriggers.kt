package com.mystipixel.royalskyblock.libreforge

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin
import com.willfp.libreforge.triggers.Triggers
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.lang.reflect.Method

/**
 * Exposes EcoMinions activity to libreforge as triggers.
 *
 * EcoMinions fires perfectly good Bukkit events but registers no libreforge elements of its own, so
 * nothing in any eco config can react to a minion doing anything. Registering the triggers here closes
 * that: libreforge elements registered by *any* plugin work in *all* of them, so this one file makes
 * EcoItems, EcoQuests, Talismans, Boosters and RoyalSkyblock's own perks and upgrades minion-aware at
 * once — a talisman that pays out when a minion harvests, a quest for placing your tenth minion, an
 * island perk that scales with how many you run.
 *
 * ## Why reflection
 *
 * EcoMinions is not a compile dependency. The suite's published artifacts are not reliable — every
 * released `com.willfp:EcoMobs` jar turned out to be an empty stub — so nothing here is linked at
 * compile time. The events are resolved from EcoMinions' own classloader at runtime and registered
 * with a dynamic executor, the same approach `EcoMobsStrengthBridge` uses. Absent or reshaped
 * EcoMinions means the triggers simply aren't registered.
 *
 * ## What each trigger carries
 *
 * `player` and `location` (the minion's), plus `text` set to the minion's type id and `value` to a
 * number that means something per event — the minion's level, or the tier reached for an upgrade.
 * `text` is what lets a config say "only my farm minions" without RoyalSkyblock inventing a filter.
 */
object MinionTriggers {

    private const val API_CLASS = "com.exanthiax.ecominions.api.EcoMinionsApi"
    private const val EVENT_PACKAGE = "com.exanthiax.ecominions.api.event."

    /** Fires when a player collects the contents of a minion. `value` is the minion's level. */
    val PICKUP = RoyalTrigger("minion_pickup", "Fires when a player collects from a minion.", "minions")

    /** Fires when a player places a minion. `value` is the minion's level. */
    val PLACE = RoyalTrigger("minion_place", "Fires when a player places a minion.", "minions")

    /** Fires when a player upgrades a minion. `value` is the tier reached. */
    val UPGRADE = RoyalTrigger("minion_upgrade", "Fires when a player upgrades a minion.", "minions")

    /** Fires when a player fuels a minion. `value` is the minion's level. */
    val FUEL = RoyalTrigger("minion_fuel", "Fires when a player fuels a minion.", "minions")

    /**
     * Register the triggers and bind them to EcoMinions' events. Returns false when EcoMinions is
     * absent or its API is not shaped as expected; the caller logs and carries on.
     *
     * The triggers are only registered when they can actually fire. Registering them unconditionally
     * would let a config reference `minion_pickup` on a server with no minion plugin and simply do
     * nothing, which is harder to diagnose than libreforge reporting an unknown trigger id.
     */
    fun register(plugin: RoyalSkyblockPlugin): Boolean {
        val ecoMinions = Bukkit.getPluginManager().getPlugin("EcoMinions") ?: return false
        val loader = ecoMinions.javaClass.classLoader
        return try {
            // Presence of the API type is the compatibility check — if EcoMinions ever drops it, bail
            // rather than binding to events whose shape we then cannot read.
            Class.forName(API_CLASS, false, loader)

            bind(plugin, loader, "MinionPickupEvent", PICKUP, MinionValue.LEVEL)
            bind(plugin, loader, "MinionPlaceEvent", PLACE, MinionValue.LEVEL)
            bind(plugin, loader, "MinionUpgradeEvent", UPGRADE, MinionValue.TIER)
            bind(plugin, loader, "MinionFuelEvent", FUEL, MinionValue.LEVEL)
            true
        } catch (notEcoMinions: Throwable) {
            false
        }
    }

    /** Which number a given event should put in the trigger's `value`. */
    private enum class MinionValue { LEVEL, TIER }

    private fun bind(
        plugin: RoyalSkyblockPlugin,
        loader: ClassLoader,
        simpleName: String,
        trigger: RoyalTrigger,
        value: MinionValue
    ) {
        @Suppress("UNCHECKED_CAST")
        val eventClass = Class.forName(EVENT_PACKAGE + simpleName, false, loader) as Class<out Event>

        val getMinion = eventClass.getMethod("getMinion")
        val getPlayer = eventClass.getMethod("getPlayer")
        // Resolve the minion accessors off the DECLARED return type — the public Minion interface —
        // rather than the runtime object's class, which may be a non-public implementation that would
        // reject the call.
        val minionType = getMinion.returnType
        val getLocation = minionType.getMethod("getLocation")
        val getLevel = minionType.getMethod("getLevel")
        val getTypeId = minionType.getMethod("getType").returnType.getMethod("getId")
        val getType = minionType.getMethod("getType")
        val getTier = if (value == MinionValue.TIER) eventClass.getMethod("getTier") else null

        Triggers.register(trigger)

        Bukkit.getPluginManager().registerEvent(
            eventClass,
            EmptyListener,
            EventPriority.MONITOR, // observing only: never alter what EcoMinions is doing
            { _, event ->
                if (eventClass.isInstance(event)) {
                    try {
                        val minion = getMinion.invoke(event)
                        val player = getPlayer.invoke(event) as? Player
                        if (minion != null && player != null) {
                            trigger.fire(
                                player,
                                getLocation.invoke(minion) as? Location,
                                getTypeId.invoke(getType.invoke(minion)) as? String,
                                ((getTier?.invoke(event) ?: getLevel.invoke(minion)) as? Int)?.toDouble() ?: 0.0
                            )
                        }
                    } catch (failed: Throwable) {
                        // EcoMinions changed shape underneath us — drop this dispatch rather than
                        // throwing inside another plugin's event.
                    }
                }
            },
            plugin
        )
    }

    /** The executor does the work; Bukkit just needs a listener instance to own the registration. */
    private object EmptyListener : Listener
}
