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
 * Sugar cane and cactus, which grow by stacking a copy of themselves on top rather than ripening.
 *
 * <p>Only the top block of a column is simulated. Growth stops at {@code max-height} (vanilla is 3) and
 * only places into air, so a farm under a ceiling stays under it and no build is overwritten.
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
        if (!plugin.conf().getBoolean("simulation.stacking-plants.enabled", true)) {
            return;
        }
        // only the top of a column grows; otherwise each block in a 3-tall cane would extend it
        if (ctx.typeAt(block.x(), block.y() + 1, block.z()) == type) {
            return;
        }
        int maxHeight = Math.max(1, plugin.conf().getInt("simulation.stacking-plants.max-height", 3));
        int height = heightBelow(block, ctx, type);
        int room = maxHeight - height;
        if (room <= 0) {
            return;                             // already at full height
        }

        double perBlock = plugin.conf().getDouble("simulation.stacking-plants.seconds-per-block", 1080);
        if (perBlock <= 0) {
            return;
        }
        // same model as crops: each block is an independent draw, so canes don't all jump by the same amount
        int grow = GrowthModel.stagesGrown(ctx.offlineSeconds(), perBlock, room, ctx.random()::nextDouble);
        if (grow <= 0) {
            return;
        }

        int placed = 0;
        for (int i = 1; i <= grow; i++) {
            int y = block.y() + i;
            // Only fill air we can see. Accept any air: an island in a void world reports VOID_AIR / CAVE_AIR
            // above the surface, not plain AIR.
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
        // the old top is now mid-column: reset its age so it isn't perpetually about to grow
        if (block.data() instanceof Ageable age && age.getAge() != 0) {
            BlockData reset = age.clone();
            ((Ageable) reset).setAge(0);
            ctx.set(block.x(), block.y(), block.z(), reset);
        }
    }

    // blocks of this plant stacked at and below this one (this block counts as 1)
    private int heightBelow(SimBlock block, SimulationContext ctx, Material type) {
        int height = 1;
        for (int y = block.y() - 1; ctx.typeAt(block.x(), y, block.z()) == type; y--) {
            height++;
            if (height > 16) {
                break;                          // never loop the world height on odd data
            }
        }
        return height;
    }
}
