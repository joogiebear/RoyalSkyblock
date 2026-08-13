package com.mystipixel.royalskyblock.libreforge

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin
import com.mystipixel.royalskyblock.hooks.IslandPlaceholders
import com.willfp.eco.core.placeholder.PlayerPlaceholder

/**
 * Registers RoyalSkyblock's placeholders with eco.
 *
 * They were PlaceholderAPI-only, which meant `%royalskyblock_island_level%` resolved in TAB and
 * scoreboards but *not inside eco configs* — an effect chain, a menu title or an item's lore could not
 * read a single thing about an island unless PlaceholderAPI happened to be installed. Every eco plugin
 * registers with eco instead, and eco bridges to PlaceholderAPI itself, so registering here means the
 * placeholders work in both places and no longer depend on a plugin we only soft-depend on.
 *
 * One [PlayerPlaceholder] per id, because eco matches on exact identifiers rather than handing over a
 * parameter string. Every one delegates to the same [IslandPlaceholders] the PAPI expansion uses, so
 * the two front ends can never disagree about what a placeholder means.
 *
 * `upgrade_<key>` is deliberately absent: it is a prefix rather than a fixed id, and there is no
 * registry-wide way to express that. PAPI consumers keep it; eco configs can use the
 * `has_island_upgrade` condition, which is the better tool for gating on an upgrade anyway.
 */
object EcoPlaceholders {

    @JvmStatic
    fun register(plugin: RoyalSkyblockPlugin, placeholders: IslandPlaceholders) {
        for (id in IslandPlaceholders.IDS) {
            PlayerPlaceholder(plugin, id) { player ->
                // An unknown id cannot happen here (we only register ours) but resolve is nullable,
                // and eco expects a string rather than a null.
                placeholders.resolve(player, id) ?: ""
            }.register()
        }
    }
}
