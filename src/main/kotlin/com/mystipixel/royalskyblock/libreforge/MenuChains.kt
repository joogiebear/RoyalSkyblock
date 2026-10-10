package com.mystipixel.royalskyblock.libreforge

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin
import com.mystipixel.royalskyblock.gui.menu.MenuEffect
import com.mystipixel.royalskyblock.gui.menu.MenuSlot
import com.mystipixel.royalskyblock.gui.menu.MenuTemplate
import com.willfp.eco.core.config.TransientConfig
import com.willfp.eco.core.config.interfaces.Config
import com.willfp.libreforge.ViolationContext
import com.willfp.libreforge.effects.Chain
import com.willfp.libreforge.effects.Effects
import com.willfp.libreforge.toDispatcher
import com.willfp.libreforge.triggers.TriggerData
import com.willfp.libreforge.triggers.Triggers
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets a menu button run a libreforge effect chain. Anything in a slot's `left-click:`/`right-click:`
 * list that isn't a built-in menu action is compiled by [Effects]:
 *
 * ```yaml
 * left-click:
 *   - id: open_menu          # built-in, unchanged
 *     args:
 *       menu: upgrades
 *   - id: give_money         # any eco effect
 *     args:
 *       amount: 100
 *       chance: 50
 * ```
 *
 * Chains are compiled when menus load, so a broken one is reported at startup with its file and slot.
 */
object MenuChains {

    // the trigger chains run under; registered, so other content can react to menu_click
    private val TRIGGER = RoyalTrigger(
        "menu_click",
        "Fires when a player clicks a RoyalSkyblock menu button.",
        "skyblock"
    )

    // actions the menu engine implements itself; never hand these to libreforge
    private val BUILT_IN = setOf(
        "open_menu", "close", "player_command", "console_command", "message", "play_sound"
    )

    private val cache = ConcurrentHashMap<String, Chain>()

    @JvmStatic
    fun register() {
        Triggers.register(TRIGGER)
    }

    /** Drop every compiled chain. Called before menus reload. */
    @JvmStatic
    fun invalidate() {
        cache.clear()
    }

    /**
     * Compile every chain in a menu up front, so violations are reported at load. Safe to call
     * repeatedly; each slot is compiled once.
     */
    @JvmStatic
    fun precompile(menuId: String, template: MenuTemplate) {
        for (slot in template.slots()) {
            compileFor(menuId, slot, false)
            compileFor(menuId, slot, true)
        }
    }

    /** Whether an id is handled by the menu engine rather than libreforge. */
    @JvmStatic
    fun isBuiltIn(id: String): Boolean = id.lowercase() in BUILT_IN

    /** Run the libreforge half of a slot's click, if it has one. The caller has already run the built-ins. */
    @JvmStatic
    fun run(menuId: String, slot: MenuSlot, rightClick: Boolean, player: Player) {
        val chain = compileFor(menuId, slot, rightClick) ?: return
        chain.trigger(
            player.toDispatcher(),
            TriggerData(player = player, location = player.location),
            TRIGGER
        )
    }

    // compile (and cache) one slot's chain for one click type, or null when it has no eco effects
    private fun compileFor(menuId: String, slot: MenuSlot, rightClick: Boolean): Chain? {
        val effects: List<MenuEffect> =
            if (rightClick && slot.rightClick().isNotEmpty()) slot.rightClick() else slot.leftClick()
        val custom = effects.filterNot { isBuiltIn(it.id()) }
        if (custom.isEmpty()) {
            return null
        }
        return cache.getOrPut("$menuId:${slot.index()}:$rightClick") {
            Effects.compileChain(
                custom.map { it.toConfig() },
                ViolationContext(RoyalSkyblockPlugin.get(), "menu $menuId slot ${slot.index()}")
            )
        }
    }

    // a MenuEffect is already id + args; wrap it as the config libreforge expects
    private fun MenuEffect.toConfig(): Config = TransientConfig(
        mapOf<String, Any>("id" to id(), "args" to args())
    )
}
