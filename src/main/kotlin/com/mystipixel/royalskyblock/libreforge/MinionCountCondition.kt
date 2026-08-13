package com.mystipixel.royalskyblock.libreforge

import com.willfp.eco.core.config.interfaces.Config
import com.willfp.libreforge.Dispatcher
import com.willfp.libreforge.NoCompileData
import com.willfp.libreforge.ProvidedHolder
import com.willfp.libreforge.arguments
import com.willfp.libreforge.conditions.Condition
import com.willfp.libreforge.conditions.Conditions
import com.willfp.libreforge.get
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.lang.reflect.Method

/**
 * `minion_count_above` — passes when the player owns more than the configured number of minions.
 *
 * The counterpart to [MinionTriggers]: those let content react to a minion *doing* something, this
 * lets content react to how many you *run*. Being a libreforge condition it works anywhere conditions
 * do, so an island perk can scale with minion count, an EcoItem can require a minion empire, a quest
 * can gate on one.
 *
 * ```yaml
 * conditions:
 *   - id: minion_count_above
 *     args:
 *       count: 10
 * ```
 *
 * Resolved reflectively for the same reason as the triggers — EcoMinions is not a compile dependency.
 * If EcoMinions is missing or reshaped the condition is never registered, so a config referencing it
 * gets a clear unknown-id violation instead of silently passing or failing.
 */
object ConditionMinionCount : Condition<NoCompileData>("minion_count_above") {

    override val description = "Passes when the player owns more than the specified number of minions."

    override val categories = setOf("minions")

    override val arguments = arguments {
        require("count", "You must specify the minion count!")
    }

    /** `EcoMinionsApi.getOrNull()`, resolved once at registration. */
    private var apiGetOrNull: Method? = null

    /** `EcoMinionsApi.minions()`. */
    private var minions: Method? = null

    /** `MinionsManager.countOwnedBy(OfflinePlayer)`. */
    private var countOwnedBy: Method? = null

    /**
     * Bind to EcoMinions and register. Returns false when EcoMinions is absent or its API is not
     * shaped as expected.
     */
    fun register(): Boolean {
        val ecoMinions = Bukkit.getPluginManager().getPlugin("EcoMinions") ?: return false
        return try {
            val apiClass = Class.forName(
                "com.exanthiax.ecominions.api.EcoMinionsApi", false, ecoMinions.javaClass.classLoader
            )
            // getOrNull() rather than get(): get() throws when EcoMinions has not finished starting,
            // and a condition must never blow up mid-evaluation inside someone else's effect chain.
            apiGetOrNull = apiClass.getMethod("getOrNull")
            val minionsMethod = apiClass.getMethod("minions")
            minions = minionsMethod
            // Resolve off the declared MinionsManager interface, not the runtime implementation, which
            // may be non-public and reject the call.
            countOwnedBy = minionsMethod.returnType.getMethod("countOwnedBy", OfflinePlayer::class.java)

            Conditions.register(this)
            true
        } catch (notEcoMinions: Throwable) {
            false
        }
    }

    override fun isMet(
        dispatcher: Dispatcher<*>,
        config: Config,
        holder: ProvidedHolder,
        compileData: NoCompileData
    ): Boolean {
        val player = dispatcher.get<Player>() ?: return false
        val owned = countFor(player) ?: return false
        return owned > config.getInt("count")
    }

    /** The player's minion count, or null if EcoMinions cannot answer right now. */
    private fun countFor(player: Player): Int? = try {
        val api = apiGetOrNull?.invoke(null)
        if (api == null) {
            null
        } else {
            countOwnedBy?.invoke(minions?.invoke(api), player) as? Int
        }
    } catch (failed: Throwable) {
        null // EcoMinions changed shape — fail the condition rather than the whole chain
    }
}
