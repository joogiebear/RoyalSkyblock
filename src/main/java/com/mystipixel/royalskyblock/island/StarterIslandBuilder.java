package com.mystipixel.royalskyblock.island;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Generates a starter island in code (no schematic needed): a grass platform with a couple of oak
 * trees, a small contained pond, a hydrated crop farm (wheat/carrots/potatoes), sugar cane, and a
 * starter chest. Driven by the {@code island.starter} config for the platform blocks + chest contents.
 *
 * <p>All block writes touch the world, so this must run on the server thread.
 */
public final class StarterIslandBuilder {

    private StarterIslandBuilder() {
    }

    /** Paste the starter island centred on {@code (x, z)} with its surface at {@code y}. */
    public static void paste(World world, int x, int y, int z, @Nullable ConfigurationSection cfg, Logger logger) {
        int radius = Math.max(6, cfg != null ? cfg.getInt("platform-radius", 6) : 6);
        Material platform = material(cfg, "platform-block", Material.GRASS_BLOCK, logger);
        Material sub = material(cfg, "sub-block", Material.DIRT, logger);
        boolean trees = cfg == null || cfg.getBoolean("tree", true);
        boolean bedrock = cfg == null || cfg.getBoolean("bedrock-anchor", true);

        // ── platform ──
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                world.getBlockAt(x + dx, y, z + dz).setType(platform, false);
                world.getBlockAt(x + dx, y - 1, z + dz).setType(sub, false);
                world.getBlockAt(x + dx, y - 2, z + dz).setType(sub, false);
                world.getBlockAt(x + dx, y - 3, z + dz).setType(sub, false);
            }
        }
        if (bedrock) {
            world.getBlockAt(x, y - 4, z).setType(Material.BEDROCK, false);
        }

        // ── pond (3x3 contained water, +x/+z quadrant) ──
        for (int dx = 2; dx <= 4; dx++) {
            for (int dz = 2; dz <= 4; dz++) {
                world.getBlockAt(x + dx, y, z + dz).setType(Material.WATER, false);
            }
        }
        // sugar cane on the grass rim next to the pond
        placeSugarCane(world, x + 1, y + 1, z + 3, 2);

        // ── crop farm (+x/-z quadrant): a water source ringed by hydrated farmland + crops ──
        world.getBlockAt(x + 3, y, z - 3).setType(Material.WATER, false);
        Material[] crops = {Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS};
        int c = 0;
        for (int dx = 2; dx <= 4; dx++) {
            for (int dz = -4; dz <= -2; dz++) {
                if (dx == 3 && dz == -3) {
                    continue; // the water source
                }
                farmland(world, x + dx, y, z + dz);
                plantCrop(world, x + dx, y + 1, z + dz, crops[c++ % crops.length]);
            }
        }

        // ── one oak tree (-x/-z quadrant) ──
        if (trees) {
            plantTree(world, x - 3, y + 1, z - 3);
        }

        // ── a cozy starter hut (-x/+z quadrant) ──
        buildHut(world, x - 4, y, z + 4);

        // ── a little decoration ──
        world.getBlockAt(x + 1, y + 1, z - 1).setType(Material.POPPY, false);
        world.getBlockAt(x - 1, y + 1, z - 1).setType(Material.DANDELION, false);
        world.getBlockAt(x + radius, y + 1, z).setType(Material.TORCH, false);
        world.getBlockAt(x - radius, y + 1, z).setType(Material.TORCH, false);

        // ── starter chest (inside the hut, back-left corner) ──
        ConfigurationSection chestCfg = cfg != null ? cfg.getConfigurationSection("chest") : null;
        if (chestCfg == null || chestCfg.getBoolean("enabled", true)) {
            placeStarterChest(world, x - 5, y + 1, z + 5, chestCfg, logger);
        }
    }

    /**
     * A small cozy 5x5 oak hut centred at {@code (cx, cz)} on surface {@code y}: plank walls with log
     * corners, a door facing spawn, glass windows, a flat roof with eaves, and a bed + crafting table +
     * furnace + hanging lantern inside. The starter chest is placed separately (back-left corner).
     */
    private static void buildHut(World world, int cx, int y, int cz) {
        int r = 2;
        // floor + hollow the interior
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                world.getBlockAt(cx + dx, y, cz + dz).setType(Material.OAK_PLANKS, false);
                for (int h = 1; h <= 3; h++) {
                    world.getBlockAt(cx + dx, y + h, cz + dz).setType(Material.AIR, false);
                }
            }
        }
        // walls: logs at the corners, planks between (y+1..y+3)
        for (int h = 1; h <= 3; h++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) == r || Math.abs(dz) == r) {
                        boolean corner = Math.abs(dx) == r && Math.abs(dz) == r;
                        world.getBlockAt(cx + dx, y + h, cz + dz)
                                .setType(corner ? Material.OAK_LOG : Material.OAK_PLANKS, false);
                    }
                }
            }
        }
        // flat roof with a 1-block eave overhang
        for (int dx = -r - 1; dx <= r + 1; dx++) {
            for (int dz = -r - 1; dz <= r + 1; dz++) {
                world.getBlockAt(cx + dx, y + 4, cz + dz).setType(Material.OAK_PLANKS, false);
            }
        }
        // door on the front (-z) wall, facing out toward spawn
        setData(world, cx, y + 1, cz - r, "minecraft:oak_door[facing=north,half=lower,hinge=left]");
        setData(world, cx, y + 2, cz - r, "minecraft:oak_door[facing=north,half=upper,hinge=left]");
        // glass windows on the other three walls
        world.getBlockAt(cx - r, y + 2, cz).setType(Material.GLASS_PANE, false);
        world.getBlockAt(cx + r, y + 2, cz).setType(Material.GLASS_PANE, false);
        world.getBlockAt(cx, y + 2, cz + r).setType(Material.GLASS_PANE, false);
        // hanging lantern for light
        setData(world, cx, y + 3, cz, "minecraft:lantern[hanging=true]");
        // furniture (chest is placed separately at cx-1, cz+1)
        world.getBlockAt(cx + 1, y + 1, cz + 1).setType(Material.CRAFTING_TABLE, false);
        world.getBlockAt(cx + 1, y + 1, cz - 1).setType(Material.FURNACE, false);
        setData(world, cx - 1, y + 1, cz - 1, "minecraft:red_bed[facing=south,part=foot]");
        setData(world, cx - 1, y + 1, cz, "minecraft:red_bed[facing=south,part=head]");
    }

    /** Set a block from a blockdata string, skipping gracefully if that data is invalid on this version. */
    private static void setData(World world, int x, int y, int z, String data) {
        try {
            world.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(data), false);
        } catch (IllegalArgumentException badData) {
            // unknown block/state on this MC version — leave it as-is
        }
    }

    private static void farmland(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        BlockData data = Bukkit.createBlockData(Material.FARMLAND);
        if (data instanceof Farmland farmland) {
            farmland.setMoisture(farmland.getMaximumMoisture());
        }
        block.setBlockData(data, false);
    }

    private static void plantCrop(World world, int x, int y, int z, Material crop) {
        Block block = world.getBlockAt(x, y, z);
        BlockData data = Bukkit.createBlockData(crop);
        if (data instanceof Ageable ageable) {
            // Mostly grown so a new player can harvest + replant right away.
            ageable.setAge(Math.max(0, ageable.getMaximumAge() - 1));
        }
        block.setBlockData(data, false);
    }

    private static void placeSugarCane(World world, int x, int y, int z, int height) {
        for (int i = 0; i < height; i++) {
            world.getBlockAt(x, y + i, z).setType(Material.SUGAR_CANE, false);
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
        // Live (non-snapshot) state so inventory writes actually persist.
        if (!(block.getState(false) instanceof Chest chest)) {
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
