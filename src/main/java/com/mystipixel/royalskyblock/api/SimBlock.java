package com.mystipixel.royalskyblock.api;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * One block handed to a {@link BlockSimulator}, read from an immutable snapshot rather than the live
 * world (simulation runs off the main thread). Coordinates are world coordinates; read neighbours
 * through {@link SimulationContext} and queue changes with {@link SimulationContext#set}.
 *
 * @param data the block's state when the island was scanned
 */
public record SimBlock(int x, int y, int z, BlockData data) {

    public Material type() {
        return data.getMaterial();
    }
}
