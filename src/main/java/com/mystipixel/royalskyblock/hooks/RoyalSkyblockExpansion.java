package com.mystipixel.royalskyblock.hooks;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI front end for {@code %royalskyblock_<key>%}. Every placeholder is resolved by
 * {@link IslandPlaceholders}, which has no PAPI dependency so eco can register the same placeholders.
 * See {@link IslandPlaceholders} for the full list of keys.
 */
public final class RoyalSkyblockExpansion extends PlaceholderExpansion {

    private final RoyalSkyblockPlugin plugin;
    private final IslandPlaceholders placeholders;

    public RoyalSkyblockExpansion(RoyalSkyblockPlugin plugin, IslandPlaceholders placeholders) {
        this.plugin = plugin;
        this.placeholders = placeholders;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "royalskyblock";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Mystipixel";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // survive PlaceholderAPI reloads; we manage our own lifecycle
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        return placeholders.resolve(player, params);
    }
}
