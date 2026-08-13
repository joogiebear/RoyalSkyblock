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
 * Lets a menu button run a libreforge effect chain, the way an EcoMenus button does.
 *
 * A slot's `left-click:`/`right-click:` list is already written as `id:` + `args:` — the exact shape
 * libreforge compiles — so anything in it that is not one of the six built-in menu actions is handed
 * to [Effects] instead of being rejected. The built-ins stay: they do things libreforge has no
 * vocabulary for, like opening another RoyalSkyblock menu.
 *
 * ```yaml
 * left-click:
 *   - id: open_menu          # built-in, unchanged
 *     args:
 *       menu: upgrades
 *   - id: give_money         # any of the 275 eco effects
 *     args:
 *       amount: 100
 *       chance: 50
 * ```
 *
 * A button can therefore carry conditions, chances, cooldowns and delays without RoyalSkyblock
 * implementing any of them — which is the difference between a bespoke menu system and one an eco
 * user already knows how to configure.
 *
 * Chains are compiled when menus load, not when a button is first clicked. That is deliberate: it
 * keeps config parsing out of inventory events, and it means a broken chain is reported at startup
 * with the file and slot named — the way every other eco plugin reports its violations — instead of
 * the first time some player happens to press that button.
 */
object MenuChains {

    /**
     * The trigger chains run under. A chain needs one to dispatch, and registering it rather than
     * inventing a private handle means `menu_click` is also usable by other content — a Talisman can
     * react to a player using a menu.
     */
    private val TRIGGER = RoyalTrigger(
        "menu_click",
        "Fires when a player clicks a RoyalSkyblock menu button.",
        "skyblock"
    )

    /**
     * Actions the menu engine implements itself. These are not libreforge effects and must not be
     * handed to it — `open_menu` in particular has no eco equivalent.
     */
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
     * Compile every chain in a menu up front, so violations are reported at load rather than on the
     * first click. Safe to call repeatedly; each slot is compiled once.
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

    /**
     * Run the libreforge half of a slot's click, if it has one. The caller has already run the
     * built-ins; this handles everything else.
     */
    @JvmStatic
    fun run(menuId: String, slot: MenuSlot, rightClick: Boolean, player: Player) {
        val chain = compileFor(menuId, slot, rightClick) ?: return
        chain.trigger(
            player.toDispatcher(),
            TriggerData(player = player, location = player.location),
            TRIGGER
        )
    }

    /** Compile (and cache) one slot's chain for one click type, or null when it has no eco effects. */
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

    /** A MenuEffect is already id + args; wrap it as the config libreforge expects. */
    private fun MenuEffect.toConfig(): Config = TransientConfig(
        mapOf<String, Any>("id" to id(), "args" to args())
    )
}
