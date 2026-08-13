package com.mystipixel.royalskyblock.hooks;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI front end for {@code %royalskyblock_<key>%}, for TAB, scoreboards, chat, holograms
 * and anything else that reads PAPI.
 *
 * <p>Deliberately thin: every placeholder is resolved by {@link IslandPlaceholders}, which knows
 * nothing about PlaceholderAPI. This class extends a PAPI type and so cannot even load without it,
 * which is exactly why the logic lives elsewhere — eco registers the same placeholders from the same
 * resolver, so they work in eco configs on a server with no PlaceholderAPI at all.
 *
 * <p>See {@link IslandPlaceholders} for the full list of keys.
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
