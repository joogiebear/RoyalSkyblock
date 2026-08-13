package com.mystipixel.royalskyblock.libreforge

import com.willfp.eco.core.EcoPlugin
import com.willfp.eco.core.config.TransientConfig
import com.willfp.eco.core.config.interfaces.Config
import com.willfp.libreforge.Holder
import com.willfp.libreforge.ViolationContext
import com.willfp.libreforge.conditions.ConditionList
import com.willfp.libreforge.conditions.Conditions
import com.willfp.libreforge.effects.EffectList
import com.willfp.libreforge.effects.Effects
import org.bukkit.NamespacedKey
import org.bukkit.configuration.ConfigurationSection

/**
 * A libreforge effect holder built from a RoyalSkyblock config block — one perk, or one purchased
 * upgrade tier.
 *
 * Being a [Holder] is what lets RoyalSkyblock content use the whole eco element library: any of
 * libreforge's effects, with its conditions, filters and mutators, rather than the fixed set of
 * behaviours the plugin implements itself. Which holders are live for a given player is decided by
 * [registerRoyalHolderProviders]; libreforge takes it from there, applying permanent effects and
 * dispatching triggered ones.
 */
class RoyalHolder(
    override val id: NamespacedKey,
    override val effects: EffectList,
    override val conditions: ConditionList
) : Holder {
    /** True when the block compiled to nothing — no point handing libreforge an empty holder. */
    val isEmpty: Boolean
        get() = effects.isEmpty() && conditions.isEmpty()
}

/**
 * Compile the `effects:`/`conditions:` blocks of a config section into a holder, or null when the
 * section declares neither.
 *
 * Both keys are read with Bukkit's `getMapList`, which quietly ignores non-map entries. That is what
 * makes the legacy shorthand keep working: `effects: ["haste:0"]` yields no maps here and is handled
 * by the caller's own parser, while `effects: [{id: ..., args: {...}}]` compiles as a libreforge
 * chain. A list may mix both forms and each side picks up only what it understands.
 */
fun compileRoyalHolder(
    plugin: EcoPlugin,
    id: NamespacedKey,
    section: ConfigurationSection,
    context: ViolationContext
): RoyalHolder? {
    val effectConfigs = section.toConfigList("effects")
    val conditionConfigs = section.toConfigList("conditions")
    if (effectConfigs.isEmpty() && conditionConfigs.isEmpty()) {
        return null
    }
    val holder = RoyalHolder(
        id,
        Effects.compile(effectConfigs, context.with("effects")),
        Conditions.compile(conditionConfigs, context.with("conditions"))
    )
    return if (holder.isEmpty) null else holder
}

/**
 * Bridge Bukkit YAML to eco's config model. RoyalSkyblock still reads its own files with Bukkit
 * YAML — moving them onto eco's config system is a separate pass — so each map entry is wrapped in a
 * [TransientConfig] for libreforge to compile.
 */
private fun ConfigurationSection.toConfigList(path: String): List<Config> =
    getMapList(path).map { raw ->
        @Suppress("UNCHECKED_CAST")
        TransientConfig(raw.entries.associate { (k, v) -> k.toString() to v } as Map<String, Any>)
    }
