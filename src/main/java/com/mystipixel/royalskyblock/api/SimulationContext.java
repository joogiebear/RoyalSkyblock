package com.mystipixel.royalskyblock.api;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

/**
 * What a {@link BlockSimulator} gets during catch-up: a read-only view of the island as it was found,
 * and a queue for changes.
 *
 * <p><b>Threading.</b> Simulators run off the main thread. Never call Bukkit world/entity methods
 * from {@link BlockSimulator#simulate}; queue the change with {@link #set} and RoyalSkyblock applies
 * it on the main thread once every simulator has run.
 *
 * <p>Reads see the original island, not other simulators' queued changes, so order doesn't matter.
 * If two simulators queue the same block, the last write wins.
 */
public interface SimulationContext {

    /** The island world being caught up. For identity/logging only: do not mutate it. */
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
     * Whether this position was inside the scanned region. False means unknown, not air: check this
     * before trusting a null from {@link #dataAt}.
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
