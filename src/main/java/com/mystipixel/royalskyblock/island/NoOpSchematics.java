package com.mystipixel.royalskyblock.island;

import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Fallback used when no WorldEdit/FAWE is installed: schematic pasting is unavailable, so island
 * creation falls back to the code-generated starter.
 */
public final class NoOpSchematics implements SchematicService {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public boolean tryPasteSchematic(World world, int x, int y, int z, String name) {
        return false;
    }

    @Override
    public String saveSelection(Player player, String name) {
        return "WorldEdit or FAWE isn't installed.";
    }
}
