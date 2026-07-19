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
 * Generates the starter island in code (no schematic needed).
 *
 * <p>A house at the centre that the player spawns inside, terrain and features arranged around it, and
 * a second island across a gap carrying a portal back to spawn — reachable only once the player bridges
 * to it, which is the first thing a skyblock island should ask of you.
 *
 * <p>Shaped rather than stamped: an organic outline, a surface that rolls, a stone outcrop, and an
 * underside that tapers to a point. Deterministic, so every island is identical and the result can be
 * reasoned about; features are placed against the generated heightmap rather than a fixed y.
 *
 * <p>All block writes touch the world, so this must run on the server thread.
 */
public final class StarterIslandBuilder {

    /** Marks a column that isn't part of the island. */
    private static final int NO_COLUMN = Integer.MIN_VALUE;

    /** Half-width of the house's floor plan, so the building is {@code HOUSE * 2 + 1} across. */
    private static final int HOUSE = 5;

    private StarterIslandBuilder() {
    }

    /** Paste the starter island centred on {@code (x, z)} with the house floor at {@code y}. */
    public static void paste(World world, int x, int y, int z, @Nullable ConfigurationSection cfg, Logger logger) {
        int radius = Math.max(10, cfg != null ? cfg.getInt("platform-radius", 17) : 17);
        Material platform = material(cfg, "platform-block", Material.GRASS_BLOCK, logger);
        Material sub = material(cfg, "sub-block", Material.DIRT, logger);
        boolean trees = cfg == null || cfg.getBoolean("tree", true);
        boolean bedrock = cfg == null || cfg.getBoolean("bedrock-anchor", true);

        int span = radius + 5;
        int[][] surface = terrain(world, x, y, z, span, radius, platform, sub);
        if (bedrock) {
            world.getBlockAt(x, y - 12, z).setType(Material.BEDROCK, false);
        }

        // -- the house, dead centre, floor exactly at y so island.home-offset (y+1) lands on it --
        for (int dx = -HOUSE - 1; dx <= HOUSE + 1; dx++) {
            for (int dz = -HOUSE - 1; dz <= HOUSE + 1; dz++) {
                level(world, surface, span, x, z, dx, dz, y, sub);
            }
        }
        buildHouse(world, x, y, z);
        ConfigurationSection chestCfg = cfg != null ? cfg.getConfigurationSection("chest") : null;
        if (chestCfg == null || chestCfg.getBoolean("enabled", true)) {
            placeStarterChest(world, x - 3, y + 1, z - 3, chestCfg, logger);
        }

        // -- features around the house --
        farm(world, surface, span, x, y, z, sub);
        pond(world, surface, span, x, z);
        if (trees) {
            grove(world, surface, span, x, z);
        }
        outcropDetail(world, surface, span, x, z);
        scatterDetail(world, x, z, span, surface, platform);

        // -- the second island, across a gap the player has to bridge --
        if (cfg == null || cfg.getBoolean("portal-island", true)) {
            portalIsland(world, x, y, z, cfg, platform, sub);
        }
    }

    /** Build the main island's terrain and return its heightmap. */
    private static int[][] terrain(World world, int x, int y, int z, int span, int radius,
                                   Material platform, Material sub) {
        int[][] surface = new int[span * 2 + 1][span * 2 + 1];
        for (int[] row : surface) {
            java.util.Arrays.fill(row, NO_COLUMN);
        }
        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                double edge = outline(dx, dz, radius);
                if (dist > edge) {
                    continue;
                }
                double falloff = 1.0 - (dist / edge);
                // Flat under the house, rolling further out — a hill through the middle of a building
                // would look like the house had been dropped on it.
                double roll = Math.max(0, dist - HOUSE - 2) * 0.16;
                int top = y + (int) Math.round(Math.min(roll, 2.2) + knoll(dx, dz));
                int depth = 3 + (int) Math.round(7.0 * falloff * falloff);

                surface[dx + span][dz + span] = top;
                boolean rocky = isOutcrop(dx, dz);
                for (int dy = 0; dy < depth; dy++) {
                    Material material;
                    if (dy == 0) {
                        material = rocky ? Material.STONE : platform;
                    } else if (dy <= 2) {
                        material = rocky ? Material.STONE : sub;
                    } else {
                        material = Material.STONE;
                    }
                    world.getBlockAt(x + dx, top - dy, z + dz).setType(material, false);
                }
            }
        }
        return surface;
    }

    /**
     * A second island across a gap, carrying a portal home.
     *
     * <p>Deliberately out of jump range: bridging to it is the first small goal a new island gives you,
     * and the portal is the reward for doing it.
     */
    private static void portalIsland(World world, int x, int y, int z,
                                     @Nullable ConfigurationSection cfg, Material platform, Material sub) {
        int distance = cfg != null ? cfg.getInt("portal-island-distance", 34) : 34;
        int radius = cfg != null ? cfg.getInt("portal-island-radius", 7) : 7;
        int cx = x + distance;
        int cz = z;
        int cy = y - 1;                                  // sits slightly lower, so it reads as separate

        for (int dx = -radius - 3; dx <= radius + 3; dx++) {
            for (int dz = -radius - 3; dz <= radius + 3; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                double edge = outline(dx, dz, radius);
                if (dist > edge) {
                    continue;
                }
                double falloff = 1.0 - (dist / edge);
                int depth = 2 + (int) Math.round(5.0 * falloff * falloff);
                for (int dy = 0; dy < depth; dy++) {
                    Material material = dy == 0 ? platform : (dy <= 2 ? sub : Material.STONE);
                    world.getBlockAt(cx + dx, cy - dy, cz + dz).setType(material, false);
                }
            }
        }

        // stone apron so the portal has something deliberate to stand on
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.getBlockAt(cx + dx, cy, cz + dz).setType(Material.STONE_BRICKS, false);
            }
        }
        buildPortal(world, cx, cy + 1, cz);
    }

    /**
     * A 4x5 obsidian frame with portal blocks in it.
     *
     * <p>Placed directly rather than lit, because a void world has no guarantee of flint and steel, and
     * the portal is meant to be there from the first second. Where it sends you is handled by the
     * listener — walking in never reaches the nether.
     */
    private static void buildPortal(World world, int x, int y, int z) {
        for (int dz = -2; dz <= 2; dz++) {
            world.getBlockAt(x, y - 1, z + dz).setType(Material.OBSIDIAN, false);   // sill
            world.getBlockAt(x, y + 4, z + dz).setType(Material.OBSIDIAN, false);   // lintel
        }
        for (int dy = 0; dy <= 3; dy++) {
            world.getBlockAt(x, y + dy, z - 2).setType(Material.OBSIDIAN, false);
            world.getBlockAt(x, y + dy, z + 2).setType(Material.OBSIDIAN, false);
        }
        for (int dy = 0; dy <= 3; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                setData(world, x, y + dy, z + dz, "minecraft:nether_portal[axis=z]");
            }
        }
    }

    /**
     * The house: 11x11, plank walls with log posts, a pitched roof, and a door facing the portal island
     * so the way onward is obvious from the doorstep.
     *
     * <p>The centre column is left clear — that is where the player spawns.
     */
    private static void buildHouse(World world, int cx, int y, int cz) {
        int r = HOUSE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                world.getBlockAt(cx + dx, y, cz + dz).setType(Material.OAK_PLANKS, false);
                for (int h = 1; h <= 5; h++) {
                    world.getBlockAt(cx + dx, y + h, cz + dz).setType(Material.AIR, false);
                }
            }
        }
        for (int h = 1; h <= 4; h++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    boolean post = (Math.abs(dx) == r && Math.abs(dz) == r)
                            || (Math.abs(dx) == r && dz % 3 == 0)
                            || (Math.abs(dz) == r && dx % 3 == 0);
                    world.getBlockAt(cx + dx, y + h, cz + dz)
                            .setType(post ? Material.OAK_LOG : Material.OAK_PLANKS, false);
                }
            }
        }
        // windows
        for (int d = -2; d <= 2; d += 2) {
            world.getBlockAt(cx - r, y + 2, cz + d).setType(Material.GLASS_PANE, false);
            world.getBlockAt(cx + r, y + 2, cz + d).setType(Material.GLASS_PANE, false);
            world.getBlockAt(cx + d, y + 2, cz - r).setType(Material.GLASS_PANE, false);
            world.getBlockAt(cx + d, y + 2, cz + r).setType(Material.GLASS_PANE, false);
        }
        // door on the +x wall, facing the portal island
        world.getBlockAt(cx + r, y + 1, cz).setType(Material.AIR, false);
        world.getBlockAt(cx + r, y + 2, cz).setType(Material.AIR, false);
        setData(world, cx + r, y + 1, cz, "minecraft:oak_door[facing=east,half=lower,hinge=left]");
        setData(world, cx + r, y + 2, cz, "minecraft:oak_door[facing=east,half=upper,hinge=left]");

        // pitched roof: each ring steps in and up, capped with a ridge
        for (int step = 0; step <= r; step++) {
            int level = y + 5 + step;
            int reach = r + 1 - step;
            if (reach < 0) {
                break;
            }
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    if (Math.abs(dx) != reach && Math.abs(dz) != reach) {
                        continue;
                    }
                    world.getBlockAt(cx + dx, level, cz + dz).setType(Material.OAK_STAIRS, false);
                }
            }
        }
        world.getBlockAt(cx, y + 5 + r, cz).setType(Material.OAK_PLANKS, false);

        // furniture, all clear of the centre column the player spawns in
        setData(world, cx + 3, y + 1, cz + 3, "minecraft:red_bed[facing=south,part=foot]");
        setData(world, cx + 3, y + 1, cz + 4, "minecraft:red_bed[facing=south,part=head]");
        world.getBlockAt(cx - 3, y + 1, cz + 3).setType(Material.CRAFTING_TABLE, false);
        world.getBlockAt(cx - 4, y + 1, cz + 3).setType(Material.FURNACE, false);
        setData(world, cx, y + 4, cz, "minecraft:lantern[hanging=true]");
        for (int d = -3; d <= 3; d += 6) {
            setData(world, cx + d, y + 4, cz + d, "minecraft:lantern[hanging=true]");
        }
    }

    /** Hydrated crop beds on the -x/-z shoulder. */
    private static void farm(World world, int[][] surface, int span, int x, int y, int z, Material sub) {
        int fx = -10;
        int fz = -4;
        int base = surfaceAt(surface, span, fx, fz);
        if (base == NO_COLUMN) {
            return;
        }
        for (int dx = fx - 2; dx <= fx + 2; dx++) {
            for (int dz = fz - 2; dz <= fz + 2; dz++) {
                level(world, surface, span, x, z, dx, dz, base, sub);
            }
        }
        world.getBlockAt(x + fx, base, z + fz).setType(Material.WATER, false);
        Material[] crops = {Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS};
        int c = 0;
        for (int dx = fx - 2; dx <= fx + 2; dx++) {
            for (int dz = fz - 2; dz <= fz + 2; dz++) {
                if (dx == fx && dz == fz) {
                    continue;
                }
                if (surfaceAt(surface, span, dx, dz) == NO_COLUMN) {
                    continue;
                }
                farmland(world, x + dx, base, z + dz);
                plantCrop(world, x + dx, base + 1, z + dz, crops[c++ % crops.length]);
            }
        }
    }

    /** A pond carved into the +x/+z shoulder, with cane on its rim. */
    private static void pond(World world, int[][] surface, int span, int x, int z) {
        int px = 8;
        int pz = 8;
        for (int dx = px - 3; dx <= px + 3; dx++) {
            for (int dz = pz - 3; dz <= pz + 3; dz++) {
                int top = surfaceAt(surface, span, dx, dz);
                if (top == NO_COLUMN) {
                    continue;
                }
                double d = Math.sqrt(sq(dx - px) + sq(dz - pz));
                if (d > 2.6) {
                    continue;                                   // leave a lip so the water is contained
                }
                world.getBlockAt(x + dx, top, z + dz).setType(Material.AIR, false);
                world.getBlockAt(x + dx, top - 1, z + dz).setType(Material.WATER, false);
            }
        }
        int caneY = surfaceAt(surface, span, px - 3, pz);
        if (caneY != NO_COLUMN) {
            placeSugarCane(world, x + px - 3, caneY + 1, z + pz, 2);
        }
    }

    /** A few oaks on the knoll side, rather than one lonely tree. */
    private static void grove(World world, int[][] surface, int span, int x, int z) {
        int[][] spots = {{-8, 7}, {-12, 3}, {-6, 11}};
        for (int[] spot : spots) {
            int top = surfaceAt(surface, span, spot[0], spot[1]);
            if (top != NO_COLUMN) {
                plantTree(world, x + spot[0], top + 1, z + spot[1]);
            }
        }
    }

    /** Boulders on the stone shoulder, so it reads as rock rather than a bare patch. */
    private static void outcropDetail(World world, int[][] surface, int span, int x, int z) {
        int[][] spots = {{9, -8}, {12, -5}, {7, -11}};
        for (int[] spot : spots) {
            boulder(world, x + spot[0], surfaceAt(surface, span, spot[0], spot[1]), z + spot[1]);
        }
    }

    /**
     * The island's outline at this bearing. Two out-of-phase waves give a lumpy edge; a circle and a
     * square both read as artificial.
     */
    private static double outline(int dx, int dz, int radius) {
        double angle = Math.atan2(dz, dx);
        return radius + 2.4 * Math.sin(3 * angle + 0.7) + 1.5 * Math.cos(5 * angle + 2.1);
    }

    /** A raised knoll on the -x/+z side, falling off smoothly so it blends into the surface. */
    private static double knoll(int dx, int dz) {
        return 3.6 * Math.exp(-(sq(dx + 9.0) + sq(dz - 7.0)) / 60.0);
    }

    /** The stone shoulder on the +x/-z side. */
    private static boolean isOutcrop(int dx, int dz) {
        return sq(dx - 10.0) + sq(dz + 8.0) < 34.0;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static int surfaceAt(int[][] surface, int span, int dx, int dz) {
        int ix = dx + span;
        int iz = dz + span;
        if (ix < 0 || iz < 0 || ix >= surface.length || iz >= surface.length) {
            return NO_COLUMN;
        }
        return surface[ix][iz];
    }

    /**
     * Flatten one column to {@code y}: clear what is above, fill if it is hollow.
     *
     * <p>Skips columns outside the island. Levelling a pad that overhangs the edge would otherwise fill
     * empty sky with dirt and leave square tabs jutting into the void.
     */
    private static void level(World world, int[][] surface, int span,
                              int originX, int originZ, int dx, int dz, int y, Material fill) {
        if (surfaceAt(surface, span, dx, dz) == NO_COLUMN) {
            return;
        }
        int x = originX + dx;
        int z = originZ + dz;
        for (int dy = 1; dy <= 6; dy++) {
            world.getBlockAt(x, y + dy, z).setType(Material.AIR, false);
        }
        for (int dy = 0; dy <= 2; dy++) {
            Block block = world.getBlockAt(x, y - dy, z);
            if (block.getType().isAir()) {
                block.setType(fill, false);
            }
        }
    }

    /** A small rock cluster for the outcrop. */
    private static void boulder(World world, int x, int surfaceY, int z) {
        if (surfaceY == NO_COLUMN) {
            return;
        }
        int y = surfaceY + 1;
        world.getBlockAt(x, y, z).setType(Material.COBBLESTONE, false);
        world.getBlockAt(x + 1, y, z).setType(Material.ANDESITE, false);
        world.getBlockAt(x, y, z + 1).setType(Material.STONE, false);
        world.getBlockAt(x, y + 1, z).setType(Material.COBBLESTONE, false);
    }

    /**
     * Grass tufts and flowers on exposed grass. Placement is a hash of the coordinates rather than a
     * random, so every island is identical and the result is reproducible.
     */
    private static void scatterDetail(World world, int x, int z, int span, int[][] surface, Material platform) {
        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                int top = surfaceAt(surface, span, dx, dz);
                if (top == NO_COLUMN) {
                    continue;
                }
                Block ground = world.getBlockAt(x + dx, top, z + dz);
                Block above = world.getBlockAt(x + dx, top + 1, z + dz);
                if (ground.getType() != platform || !above.getType().isAir()) {
                    continue;
                }
                int roll = Math.abs((dx * 73_856_093) ^ (dz * 19_349_663)) % 100;
                if (roll < 14) {
                    above.setType(Material.SHORT_GRASS, false);
                } else if (roll < 17) {
                    above.setType(Material.POPPY, false);
                } else if (roll < 20) {
                    above.setType(Material.DANDELION, false);
                } else if (roll < 22) {
                    above.setType(Material.AZURE_BLUET, false);
                } else if (roll < 23) {
                    above.setType(Material.OXEYE_DAISY, false);
                }
            }
        }
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
