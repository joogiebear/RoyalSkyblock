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
 * A libreforge effect holder built from a RoyalSkyblock config block: one perk, or one upgrade tier.
 * Which holders apply to a player is decided by [registerRoyalHolderProviders].
 */
class RoyalHolder(
    override val id: NamespacedKey,
    override val effects: EffectList,
    override val conditions: ConditionList
) : Holder {
    /** True when the block compiled to nothing. */
    val isEmpty: Boolean
        get() = effects.isEmpty() && conditions.isEmpty()
}

/**
 * Compile the `effects:`/`conditions:` blocks of a config section into a holder, or null when the
 * section declares neither. Read with `getMapList`, which ignores non-map entries, so the legacy
 * shorthand (`effects: ["haste:0"]`) is left to the caller's own parser and a list may mix both forms.
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

// Bukkit YAML to eco's config model: each map entry is wrapped in a TransientConfig for libreforge
private fun ConfigurationSection.toConfigList(path: String): List<Config> =
    getMapList(path).map { raw ->
        @Suppress("UNCHECKED_CAST")
        TransientConfig(raw.entries.associate { (k, v) -> k.toString() to v } as Map<String, Any>)
    }
