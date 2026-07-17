package com.mystipixel.royalskyblock.simulation;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.api.BlockSimulator;
import com.mystipixel.royalskyblock.api.SimBlock;
import com.mystipixel.royalskyblock.api.SimulationContext;
import org.bukkit.Material;
import org.bukkit.block.data.Ageable;

import java.util.Set;

/**
 * Crops that grow by ageing in place: wheat, carrots, potatoes, beetroot, nether wart, cocoa,
 * sweet berries, torchflower and pitcher crop.
 *
 * <p>These are the easy case — read {@code age}, write a bigger {@code age}. Anything that grows by
 * <em>placing</em> a block (cane, cactus, melon/pumpkin stems) needs its own simulator, because the
 * question there is "where does the new block go" rather than "what age is this one".
 */
public final class AgeCropSimulator implements BlockSimulator {

    private static final Set<Material> CROPS = Set.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.NETHER_WART, Material.COCOA, Material.SWEET_BERRY_BUSH,
            Material.TORCHFLOWER_CROP, Material.PITCHER_CROP);

    private final RoyalSkyblockPlugin plugin;

    public AgeCropSimulator(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Set<Material> materials() {
        return CROPS;
    }

    @Override
    public void simulate(SimBlock block, SimulationContext ctx) {
        if (!(block.data() instanceof Ageable age) || age.getAge() >= age.getMaximumAge()) {
            return;                             // already ripe
        }
        double perStage = plugin.getConfig().getDouble("simulation.crops.seconds-per-stage", 130);
        if (perStage <= 0) {
            return;                             // treated as "disabled", not "instant"
        }
        int grown = GrowthModel.stagesGrown(ctx.offlineSeconds(), perStage,
                age.getMaximumAge() - age.getAge(), ctx.random()::nextDouble);
        if (grown <= 0) {
            return;
        }
        Ageable next = (Ageable) age.clone();
        next.setAge(Math.min(age.getAge() + grown, age.getMaximumAge()));
        ctx.set(block.x(), block.y(), block.z(), next);
    }
}
