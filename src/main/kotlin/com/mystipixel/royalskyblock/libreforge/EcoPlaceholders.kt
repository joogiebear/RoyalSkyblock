package com.mystipixel.royalskyblock.libreforge

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin
import com.mystipixel.royalskyblock.hooks.IslandPlaceholders
import com.willfp.eco.core.placeholder.PlayerPlaceholder

/**
 * Registers RoyalSkyblock's placeholders with eco, so they work in eco configs (effect chains, menu
 * titles, lore) and, through eco's bridge, PlaceholderAPI. One [PlayerPlaceholder] per id, each
 * delegating to [IslandPlaceholders].
 *
 * `upgrade_<key>` is a prefix, not a fixed id, so it isn't registered here; eco configs can use the
 * `has_island_upgrade` condition instead.
 */
object EcoPlaceholders {

    @JvmStatic
    fun register(plugin: RoyalSkyblockPlugin, placeholders: IslandPlaceholders) {
        for (id in IslandPlaceholders.IDS) {
            PlayerPlaceholder(plugin, id) { player ->
                // resolve is nullable but eco expects a string
                placeholders.resolve(player, id) ?: ""
            }.register()
        }
    }
}
