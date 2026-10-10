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
 * Island level-up rewards as libreforge effect chains, alongside the console command strings:
 *
 * ```yaml
 * rewards:
 *   10:
 *     - "eco give %owner% 1000"     # console command, unchanged
 *     - id: give_money              # any eco effect
 *       args:
 *         amount: 500
 * ```
 *
 * Commands run once per level; chains run once per level per online member, dispatched under
 * [IslandTriggers.LEVEL_UP] with the level reached as `value`.
 */
object LevelRewardChains {

    private val cache = ConcurrentHashMap<Int, Chain>()

    /** Drop every compiled chain. Called before levels.yml reloads. */
    @JvmStatic
    fun invalidate() {
        cache.clear()
    }

    /**
     * Compile one level's reward chain from the map entries in its reward list. Called while `levels.yml`
     * is read, so a broken reward is reported then, naming the level.
     */
    @JvmStatic
    fun compile(level: Int, raw: List<Map<*, *>>) {
        if (raw.isEmpty()) {
            return
        }
        val configs = raw.map { entry ->
            TransientConfig(entry.entries.associate { (k, v) -> k.toString() to v })
        }
        // null when nothing compiled; libreforge has already reported why
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
