package com.mystipixel.royalskyblock.hooks;

import org.bukkit.OfflinePlayer;

/**
 * Supplies a player's combat level, used to pick which mob tier to spawn. Backed by a skills plugin
 * (EcoSkills today), with a fallback when none is present.
 */
@FunctionalInterface
public interface CombatLevelSource {

    /** The player's combat level, or a fallback when it can't be read. */
    int levelOf(OfflinePlayer player);
}
