package com.mystipixel.royalskyblock.api;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

/**
 * What a {@link BlockSimulator} is given while an island catches up: a read-only view of the island
 * as it was found, and a queue for the changes it wants made.
 *
 * <p><b>Threading.</b> Simulators run off the main thread against an immutable snapshot. Reads here
 * are safe; the live world is not. Never call Bukkit world/entity methods from
 * {@link BlockSimulator#simulate} — queue the change with {@link #set} and RoyalSkyblock applies it
 * on the main thread, batched, once every simulator has had its say.
 *
 * <p><b>Reads see the original island, not other simulators' queued work.</b> Two simulators are
 * therefore independent and order doesn't matter, which is what keeps them composable. The flip side
 * is that if two of them queue the same block, last write wins — so don't register two simulators
 * for the same material unless you mean it.
 */
public interface SimulationContext {

    /** The island world being caught up. Provided for identity/logging — do not mutate it. */
    World world();

    /**
     * Seconds the island spent unloaded, already clamped to {@code simulation.max-offline-hours}.
     * Use this to decide how much should have happened; it is never zero or negative.
     */
    long offlineSeconds();

    /** Block state at these world coordinates as the island was found, or null if outside the scan. */
    @Nullable BlockData dataAt(int x, int y, int z);

    /** Convenience for {@link #dataAt}'s material, or null if outside the scan. */
    @Nullable Material typeAt(int x, int y, int z);

    /**
     * Was this position inside the scanned region? A false here means "unknown", not "air" — a
     * simulator that needs to be sure a space is free must check this before trusting a null.
     */
    boolean inScan(int x, int y, int z);

    /**
     * Queue a block change for the main thread. RoyalSkyblock re-checks the target before writing,
     * so a player who harvested in the meantime isn't overwritten.
     */
    void set(int x, int y, int z, BlockData data);

    /**
     * Randomness for this catch-up. Growth is a probability in vanilla, not a schedule; use this so
     * a field planted together doesn't ripen in lockstep. Safe to call off-thread.
     */
    Random random();
}
