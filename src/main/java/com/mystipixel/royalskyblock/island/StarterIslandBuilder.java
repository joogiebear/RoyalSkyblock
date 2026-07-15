package com.mystipixel.royalskyblock.island;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;

/**
 * Builds a starter island directly in code, so Phase 1 needs no external schematic library. A later
 * phase can swap this for WorldEdit {@code .schem} loading behind the same call — the rest of the
 * lifecycle only cares that a starter island exists at the paste origin.
 *
 * <p>All block writes touch the world, so this must run on the server thread.
 */
public final class StarterIslandBuilder {

    private StarterIslandBuilder() {
    }

    /**
     * Paste a small grass platform with a tree and a starter chest, centred on {@code (x, z)} with its
     * surface at {@code y}. Returns the safe home location's block Y (the surface).
     */
    public static void paste(World world, int x, int y, int z) {
        int radius = 3; // 7x7 platform

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                world.getBlockAt(x + dx, y, z + dz).setType(Material.GRASS_BLOCK, false);
                world.getBlockAt(x + dx, y - 1, z + dz).setType(Material.DIRT, false);
                world.getBlockAt(x + dx, y - 2, z + dz).setType(Material.DIRT, false);
            }
        }

        // A little bedrock anchor under the centre.
        world.getBlockAt(x, y - 3, z).setType(Material.BEDROCK, false);

        plantTree(world, x + 2, y + 1, z + 2);
        placeStarterChest(world, x - 1, y + 1, z - 1);
    }

    private static void plantTree(World world, int x, int y, int z) {
        for (int i = 0; i < 4; i++) {
            world.getBlockAt(x, y + i, z).setType(Material.OAK_LOG, false);
        }
        int top = y + 4;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    Block block = world.getBlockAt(x + dx, top - 1 + dy, z + dz);
                    if (block.getType() == Material.AIR && !(dx == 0 && dz == 0 && dy == 0)) {
                        block.setType(Material.OAK_LEAVES, false);
                    }
                }
            }
        }
        world.getBlockAt(x, top + 1, z).setType(Material.OAK_LEAVES, false);
        world.getBlockAt(x + 1, top, z).setType(Material.OAK_LEAVES, false);
        world.getBlockAt(x - 1, top, z).setType(Material.OAK_LEAVES, false);
        world.getBlockAt(x, top, z + 1).setType(Material.OAK_LEAVES, false);
        world.getBlockAt(x, top, z - 1).setType(Material.OAK_LEAVES, false);
    }

    private static void placeStarterChest(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.CHEST, false);
        BlockState state = block.getState();
        if (state instanceof Chest chest) {
            chest.getBlockInventory().addItem(
                    new ItemStack(Material.ICE, 2),
                    new ItemStack(Material.LAVA_BUCKET, 1),
                    new ItemStack(Material.OAK_SAPLING, 2),
                    new ItemStack(Material.MELON_SLICE, 1),
                    new ItemStack(Material.PUMPKIN_SEEDS, 1),
                    new ItemStack(Material.SUGAR_CANE, 1),
                    new ItemStack(Material.BONE_MEAL, 4));
            chest.update();
        }
    }
}
