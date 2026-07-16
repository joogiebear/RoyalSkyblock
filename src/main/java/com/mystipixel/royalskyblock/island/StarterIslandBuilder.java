package com.mystipixel.royalskyblock.island;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Builds a starter island directly in code, driven by the {@code island.starter} config section, so
 * admins can tune the platform, tree, and starter chest without a schematic library. A later phase can
 * swap this for WorldEdit {@code .schem} loading behind the same call.
 *
 * <p>All block writes touch the world, so this must run on the server thread.
 */
public final class StarterIslandBuilder {

    private StarterIslandBuilder() {
    }

    /**
     * Paste the starter island centred on {@code (x, z)} with its surface at {@code y}, using the
     * given {@code island.starter} config (may be {@code null} → sensible defaults).
     */
    public static void paste(World world, int x, int y, int z, @Nullable ConfigurationSection cfg, Logger logger) {
        int radius = cfg != null ? Math.max(0, cfg.getInt("platform-radius", 3)) : 3;
        Material platform = material(cfg, "platform-block", Material.GRASS_BLOCK, logger);
        Material sub = material(cfg, "sub-block", Material.DIRT, logger);
        boolean tree = cfg == null || cfg.getBoolean("tree", true);
        boolean bedrock = cfg == null || cfg.getBoolean("bedrock-anchor", true);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                world.getBlockAt(x + dx, y, z + dz).setType(platform, false);
                world.getBlockAt(x + dx, y - 1, z + dz).setType(sub, false);
                world.getBlockAt(x + dx, y - 2, z + dz).setType(sub, false);
            }
        }
        if (bedrock) {
            world.getBlockAt(x, y - 3, z).setType(Material.BEDROCK, false);
        }
        if (tree) {
            plantTree(world, x + 2, y + 1, z + 2);
        }

        ConfigurationSection chestCfg = cfg != null ? cfg.getConfigurationSection("chest") : null;
        boolean chestEnabled = chestCfg == null || chestCfg.getBoolean("enabled", true);
        if (chestEnabled) {
            placeStarterChest(world, x - 1, y + 1, z - 1, chestCfg, logger);
        }
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

    private static void placeStarterChest(World world, int x, int y, int z,
                                          @Nullable ConfigurationSection chestCfg, Logger logger) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.CHEST, false);
        // getState(false) = a LIVE (non-snapshot) state: inventory writes go straight to the real
        // tile entity. A plain getState() snapshot does not persist container contents via update().
        BlockState state = block.getState(false);
        if (!(state instanceof Chest chest)) {
            return;
        }

        List<String> lines = chestCfg != null ? chestCfg.getStringList("items") : List.of();
        if (lines.isEmpty()) {
            lines = List.of("ICE:2", "LAVA_BUCKET:1", "OAK_SAPLING:2", "MELON_SLICE:1",
                    "PUMPKIN_SEEDS:1", "SUGAR_CANE:1", "BONE_MEAL:4");
        }
        for (String line : lines) {
            ItemStack item = parseItem(line, logger);
            if (item != null) {
                chest.getBlockInventory().addItem(item);
            }
        }
        chest.update(true, false);
    }

    /** Parse a {@code MATERIAL:AMOUNT} line into an ItemStack, or {@code null} (logged) if invalid. */
    private static @Nullable ItemStack parseItem(String line, Logger logger) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split(":");
        Material material = Material.matchMaterial(parts[0].trim().toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem()) {
            logger.warning("Starter chest: '" + parts[0] + "' is not a valid item — skipping.");
            return null;
        }
        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Math.max(1, Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException ignored) {
                logger.warning("Starter chest: bad amount in '" + line + "' — defaulting to 1.");
            }
        }
        return new ItemStack(material, amount);
    }

    private static Material material(@Nullable ConfigurationSection cfg, String key, Material fallback, Logger logger) {
        if (cfg == null) {
            return fallback;
        }
        String raw = cfg.getString(key);
        if (raw == null) {
            return fallback;
        }
        Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        if (material == null || !material.isBlock()) {
            logger.warning("Starter island: '" + raw + "' is not a valid block for " + key + " — using " + fallback + ".");
            return fallback;
        }
        return material;
    }
}
