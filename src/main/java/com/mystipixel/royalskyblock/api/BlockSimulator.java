package com.mystipixel.royalskyblock.api;

import org.bukkit.Material;
import org.jetbrains.annotations.ApiStatus;

import java.util.Set;

/**
 * Catches one kind of block up on time its island spent unloaded.
 *
 * <p>An island that nobody is standing on does not tick — no growth, no smelting, no minions. When
 * it loads again, RoyalSkyblock scans it <em>once</em> and hands each block to whichever simulators
 * asked for that material. Implement this to teach it about a block it doesn't know.
 *
 * <p>The scan is the expensive and error-prone part — snapshotting chunks, staying off the main
 * thread, and remembering that chunk sections are indexed from the world's minimum height and not
 * from y=0. It is done once, here, so that every simulator doesn't repeat it (and repeat its bugs).
 * A simulator only answers "given this block and this much missed time, what should it look like?"
 *
 * <p><b>Removing a simulator is how you turn a behaviour off.</b> Nothing registered for
 * {@code SUGAR_CANE} means cane simply doesn't grow while the island sleeps — no config flag, no
 * special case. That is the intended way to opt out, and the reason this is a registry rather than
 * a fixed list.
 *
 * <p>Example — bone meal that never runs out:
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
 * and never touch the live world or entities. Throwing is contained and logged against your
 * simulator rather than killing the whole catch-up — but the block you were handed is then skipped.
 *
 * <p>This interface is new and may still change shape while the built-in simulators shake it out.
 * If you build against it, pin your version.
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
     *
     * @param block the block, as the island was found
     * @param ctx   read the island, queue changes, get randomness
     */
    void simulate(SimBlock block, SimulationContext ctx);

    /** Name used in logs when this simulator misbehaves. Defaults to the class's simple name. */
    default String name() {
        return getClass().getSimpleName();
    }
}
