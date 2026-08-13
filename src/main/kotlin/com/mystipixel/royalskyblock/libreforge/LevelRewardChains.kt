package com.mystipixel.royalskyblock.libreforge

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin
import com.mystipixel.royalskyblock.island.Island
import com.willfp.eco.core.config.TransientConfig
import com.willfp.libreforge.ViolationContext
import com.willfp.libreforge.effects.Chain
import com.willfp.libreforge.effects.Effects
import com.willfp.libreforge.toDispatcher
import com.willfp.libreforge.triggers.TriggerData
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

/**
 * Island level-up rewards, as libreforge effect chains.
 *
 * `levels.yml` paid rewards by dispatching console command strings, which meant every reward went
 * through some other plugin's command and could not carry a condition, a chance or a delay. A level's
 * reward list now takes effect blocks beside those strings:
 *
 * ```yaml
 * rewards:
 *   10:
 *     - "eco give %owner% 1000"     # console command, unchanged
 *     - id: give_money              # any of the 275 eco effects
 *       args:
 *         amount: 500
 * ```
 *
 * Both forms live in one list and neither has to be converted: Bukkit's `getStringList` sees only the
 * strings and `getMapList` only the maps, so each side picks up what it understands. That is the same
 * arrangement perks use.
 *
 * ## Two things that differ from the command form, deliberately
 *
 * Commands run **once per level** — they are server-side and address the owner by name. Chains run
 * **once per level, per online member**, because an effect targets a player and an island belongs to
 * a profile. Three members online when an island crosses level 10 means three payouts, which is what
 * a reward should do.
 *
 * Chains dispatch under [IslandTriggers.LEVEL_UP], so a reward is indistinguishable from any other
 * content reacting to `island_level_up` — and the trigger's `value` carries the level reached.
 */
object LevelRewardChains {

    private val cache = ConcurrentHashMap<Int, Chain>()

    /** Drop every compiled chain. Called before levels.yml reloads. */
    @JvmStatic
    fun invalidate() {
        cache.clear()
    }

    /**
     * Compile one level's reward chain from the map entries in its reward list.
     *
     * Called while `levels.yml` is being read, so a broken reward is reported then — naming the level
     * — rather than when some island happens to reach it, possibly weeks later.
     */
    @JvmStatic
    fun compile(level: Int, raw: List<Map<*, *>>) {
        if (raw.isEmpty()) {
            return
        }
        val configs = raw.map { entry ->
            TransientConfig(entry.entries.associate { (k, v) -> k.toString() to v })
        }
        // compileChain returns null when nothing in the list compiled — libreforge has already
        // reported why, so there is nothing to cache and nothing more to say.
        val chain = Effects.compileChain(
            configs,
            ViolationContext(RoyalSkyblockPlugin.get(), "levels.yml reward for level $level")
        ) ?: return
        cache[level] = chain
    }

    /** Whether any level has a chain, so the caller can skip the member loop entirely. */
    @JvmStatic
    fun isEmpty(): Boolean = cache.isEmpty()

    /** Run a level's reward chain for one member. No-op when that level has none. */
    @JvmStatic
    fun run(player: Player, island: Island, level: Int) {
        val chain = cache[level] ?: return
        chain.trigger(
            player.toDispatcher(),
            TriggerData(
                player = player,
                location = player.location,
                text = island.worldName(),
                value = level.toDouble()
            ),
            IslandTriggers.LEVEL_UP
        )
    }
}
