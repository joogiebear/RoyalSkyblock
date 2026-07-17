package com.mystipixel.royalskyblock.api;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * One block handed to a {@link BlockSimulator}, read from an immutable snapshot.
 *
 * <p>This is not a live {@link org.bukkit.block.Block}: simulation runs off the main thread, where
 * touching the world would be a race. Coordinates are world coordinates; read neighbours through
 * {@link SimulationContext} and queue changes with {@link SimulationContext#set}.
 *
 * @param x world X
 * @param y world Y
 * @param z world Z
 * @param data the block's state at the moment the island was scanned
 */
public record SimBlock(int x, int y, int z, BlockData data) {

    public Material type() {
        return data.getMaterial();
    }
}
