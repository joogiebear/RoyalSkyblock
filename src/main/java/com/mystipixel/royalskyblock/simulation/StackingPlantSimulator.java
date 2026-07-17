package com.mystipixel.royalskyblock.simulation;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.api.BlockSimulator;
import com.mystipixel.royalskyblock.api.SimBlock;
import com.mystipixel.royalskyblock.api.SimulationContext;
import org.bukkit.Material;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;

import java.util.Set;

/**
 * Sugar cane and cactus — the backbone of most skyblock farms, and the reason offline simulation
 * had to grow past ageing crops.
 *
 * <p>These don't ripen, they <em>stack</em>: the plant ages invisibly and, on reaching its final
 * age, places a copy of itself above and resets. So the question isn't "what age is this block" but
 * "how many blocks tall should this column be by now" — which is why they need a different
 * simulator from {@link AgeCropSimulator} rather than a bigger switch inside it.
 *
 * <p>Only the <b>top</b> block of a column is simulated; the rest are skipped, so a 3-tall cane
 * isn't grown three times. Growth stops at {@code max-height} (vanilla is 3), and refuses to place
 * into anything that isn't air — a farm built under a ceiling stays inside its ceiling, and no
 * player's build is overwritten by something that grew while they were logged off.
 */
public final class StackingPlantSimulator implements BlockSimulator {

    private static final Set<Material> PLANTS = Set.of(Material.SUGAR_CANE, Material.CACTUS);

    private final RoyalSkyblockPlugin plugin;

    public StackingPlantSimulator(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Set<Material> materials() {
        return PLANTS;
    }

    @Override
    public void simulate(SimBlock block, SimulationContext ctx) {
        Material type = block.type();
        if (!plugin.getConfig().getBoolean("simulation.stacking-plants.enabled", true)) {
            return;
        }
        // Only the top of a column grows. Without this, every block in a 3-tall cane would each try
        // to extend it and the plant would shoot up in one visit.
        if (ctx.typeAt(block.x(), block.y() + 1, block.z()) == type) {
            return;
        }
        int maxHeight = Math.max(1, plugin.getConfig().getInt("simulation.stacking-plants.max-height", 3));
        int height = heightBelow(block, ctx, type);
        int room = maxHeight - height;
        if (room <= 0) {
            return;                             // already at full height
        }

        double perBlock = plugin.getConfig().getDouble("simulation.stacking-plants.seconds-per-block", 1080);
        if (perBlock <= 0) {
            return;
        }
        // Same model as crops: vanilla stacks on random ticks, so each block is an independent draw
        // rather than elapsed/duration — otherwise every cane on the island jumps by the same amount.
        int grow = GrowthModel.stagesGrown(ctx.offlineSeconds(), perBlock, room, ctx.random()::nextDouble);
        if (grow <= 0) {
            return;
        }

        int placed = 0;
        for (int i = 1; i <= grow; i++) {
            int y = block.y() + i;
            // Never grow into the unknown or into someone's build: only ever fill air we can see.
            // Accept ANY air — an island floating in a void world reports VOID_AIR / CAVE_AIR above
            // the surface, not plain AIR, and checking == AIR would silently refuse to grow there.
            Material above = ctx.typeAt(block.x(), y, block.z());
            if (!ctx.inScan(block.x(), y, block.z()) || above == null || !above.isAir()) {
                break;
            }
            ctx.set(block.x(), y, block.z(), plugin.getServer().createBlockData(type));
            placed++;
        }
        if (placed == 0) {
            return;
        }
        // The old top is now mid-column: reset its age so it isn't perpetually "about to grow".
        if (block.data() instanceof Ageable age && age.getAge() != 0) {
            BlockData reset = age.clone();
            ((Ageable) reset).setAge(0);
            ctx.set(block.x(), block.y(), block.z(), reset);
        }
    }

    /** How many blocks of this plant are stacked at and below this one (this block counts as 1). */
    private int heightBelow(SimBlock block, SimulationContext ctx, Material type) {
        int height = 1;
        for (int y = block.y() - 1; ctx.typeAt(block.x(), y, block.z()) == type; y--) {
            height++;
            if (height > 16) {
                break;                          // paranoia: never loop the world height on odd data
            }
        }
        return height;
    }
}
