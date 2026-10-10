package com.mystipixel.royalskyblock.api;

import org.bukkit.Material;
import org.jetbrains.annotations.ApiStatus;

import java.util.Set;

/**
 * Catches one kind of block up on the time its island spent unloaded.
 *
 * <p>When an unloaded island loads again, RoyalSkyblock scans it once and hands each block to the
 * simulators registered for its material. Removing a simulator turns that behaviour off: with
 * nothing registered for {@code SUGAR_CANE}, cane doesn't grow while the island is unloaded.
 *
 * <p>Example, bone meal that never runs out:
 * <pre>{@code
 * public final class MagicSoilSimulator implements BlockSimulator {
 *     public Set<Material> materials() { return Set.of(Material.WHEAT); }
 *
 *     public void simulate(SimBlock block, SimulationContext ctx) {
 *         if (!(block.data() instanceof Ageable age)) return;
 *         if (ctx.typeAt(block.x(), block.y() - 1, block.z()) != Material.SOUL_SAND) return;
 *         age.setAge(age.getMaximumAge());          // instant ripe, regardless of time away
 *         ctx.set(block.x(), block.y(), block.z(), age);
 *     }
 * }
 * }</pre>
 * Register with {@code RoyalSkyblockPlugin.get().simulators().register(new MagicSoilSimulator())}
 * in your {@code onEnable}.
 *
 * <p><b>Threading.</b> {@link #simulate} runs off the main thread against an immutable snapshot.
 * Read neighbours via {@link SimulationContext}, queue changes with {@link SimulationContext#set},
 * and never touch the live world or entities. An exception is logged against your simulator and
 * the block is skipped.
 *
 * <p>Experimental: this interface may still change. Pin your version if you build against it.
 */
@ApiStatus.Experimental
public interface BlockSimulator {

    /**
     * Materials this simulator wants to see. The scan dispatches on this, so a simulator costs
     * nothing for blocks it didn't ask for. Must be non-empty and constant.
     */
    Set<Material> materials();

    /**
     * Decide what this block should look like after {@link SimulationContext#offlineSeconds}.
     * Queue any changes via {@link SimulationContext#set}; do nothing to leave the block alone.
     */
    void simulate(SimBlock block, SimulationContext ctx);

    /** Name used in logs when this simulator misbehaves. Defaults to the class's simple name. */
    default String name() {
        return getClass().getSimpleName();
    }
}
