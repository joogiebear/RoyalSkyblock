package com.mystipixel.royalskyblock.simulation;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.api.IslandCatchupEvent;
import com.mystipixel.royalskyblock.island.Island;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Grows an island's crops forward to cover the time it spent unloaded.
 *
 * <p>Vanilla grows a crop on random ticks, so a block's real growth rate is a probability, not a
 * schedule. Rather than model light levels and farmland hydration per block, this uses one tunable
 * ({@code simulation.crops.seconds-per-stage}) as the expected time for one growth stage under
 * normal farm conditions, and rolls each stage independently (see {@link GrowthModel}). That keeps
 * a field planted at once from ripening in lockstep, which a fixed division would do.
 *
 * <p>Only {@link Ageable} crops are simulated — wheat, carrots, potatoes, beetroot, nether wart,
 * cocoa, berries. Sugar cane, cactus and bamboo grow by <em>placing</em> new blocks rather than
 * ageing, and melon/pumpkin stems spawn fruit in a free neighbouring block; both need placement and
 * neighbour logic, so they are out of scope and still only grow while someone is on the island.
 *
 * <p>Chunks are gathered by walking the island's own bounds, NOT {@code world.getLoadedChunks()}:
 * the catch-up event fires the instant the world loads, before the arriving player has been
 * teleported in, so at that moment almost nothing is loaded and a loaded-chunks scan silently finds
 * an empty island. Slime worlds live in memory, so pulling the island's chunks is cheap. Scanning
 * runs off-thread from snapshots; only the batched writes touch the main thread.
 */
public final class CropSimulator implements Listener {

    private final RoyalSkyblockPlugin plugin;

    public CropSimulator(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    /** One crop block to age up: coords plus the new age to write. */
    private record Pending(int x, int y, int z, int newAge) {
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCatchup(IslandCatchupEvent event) {
        if (!plugin.getConfig().getBoolean("simulation.crops.enabled", true)) {
            return;
        }
        double secondsPerStage = plugin.getConfig().getDouble("simulation.crops.seconds-per-stage", 130);
        if (secondsPerStage <= 0) {
            return;
        }
        World world = event.getWorld();
        Island island = event.getIsland();
        long offline = event.getOfflineSeconds();
        boolean debug = plugin.getConfig().getBoolean("settings.debug", false);

        int loY = Math.max(world.getMinHeight(), plugin.getConfig().getInt("simulation.crops.scan-y-min", 60));
        int hiY = Math.min(world.getMaxHeight() - 1, plugin.getConfig().getInt("simulation.crops.scan-y-max", 180));
        if (loY > hiY) {
            plugin.getLogger().warning("simulation.crops scan-y-min is above scan-y-max — no crops will "
                    + "ever be simulated. Check config.yml.");
            return;
        }

        // The island's own footprint, not whatever happens to be loaded right now.
        int centreX = plugin.getConfig().getInt("island.paste.x", 0);
        int centreZ = plugin.getConfig().getInt("island.paste.z", 0);
        int radius = Math.max(16, island.radius());
        int minCx = (centreX - radius) >> 4;
        int maxCx = (centreX + radius) >> 4;
        int minCz = (centreZ - radius) >> 4;
        int maxCz = (centreZ + radius) >> 4;

        List<ChunkSnapshot> snapshots = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                // getChunkAt loads it if needed. For a slime world that's a memory read, not disk.
                Chunk chunk = world.getChunkAt(cx, cz);
                snapshots.add(chunk.getChunkSnapshot(false, false, false));
            }
        }
        if (snapshots.isEmpty()) {
            plugin.getLogger().warning("Catch-up: no chunks to scan for " + world.getName()
                    + " — offline time is being dropped. This shouldn't happen.");
            return;
        }
        if (debug) {
            plugin.getLogger().info("Catch-up: scanning " + snapshots.size() + " chunks of "
                    + world.getName() + " (radius " + radius + ", y " + loY + ".." + hiY + ") for "
                    + offline + "s offline.");
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Pending> pending = new ArrayList<>();
            int found = 0;
            for (ChunkSnapshot snap : snapshots) {
                found += scan(snap, loY, hiY, offline, secondsPerStage, pending);
            }
            int cropsFound = found;
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> apply(world, pending, offline, cropsFound, debug));
        });
    }

    /** @return how many growable crops were seen (not how many grew) — the number that explains a no-op */
    private int scan(ChunkSnapshot snap, int loY, int hiY, long offline, double secondsPerStage,
                     List<Pending> out) {
        int baseX = snap.getX() << 4;
        int baseZ = snap.getZ() << 4;
        int found = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int highest = Math.min(hiY, snap.getHighestBlockYAt(x, z));
                for (int y = loY; y <= highest; y++) {
                    if (snap.isSectionEmpty(y >> 4)) {
                        y = ((y >> 4) + 1) * 16 - 1;   // jump to the next section
                        continue;
                    }
                    if (!isCrop(snap.getBlockType(x, y, z))) {
                        continue;
                    }
                    BlockData data = snap.getBlockData(x, y, z);
                    if (!(data instanceof Ageable age) || age.getAge() >= age.getMaximumAge()) {
                        continue;
                    }
                    found++;
                    int grown = GrowthModel.stagesGrown(offline, secondsPerStage,
                            age.getMaximumAge() - age.getAge(),
                            ThreadLocalRandom.current()::nextDouble);
                    if (grown > 0) {
                        out.add(new Pending(baseX + x, y, baseZ + z, age.getAge() + grown));
                    }
                }
            }
        }
        return found;
    }

    private void apply(World world, List<Pending> pending, long offline, int cropsFound, boolean debug) {
        int changed = 0;
        for (Pending p : pending) {
            Block block = world.getBlockAt(p.x(), p.y(), p.z());
            // Re-check: the snapshot is a moment old and a player may already have harvested.
            if (!(block.getBlockData() instanceof Ageable age)) {
                continue;
            }
            int target = Math.min(p.newAge(), age.getMaximumAge());
            if (target <= age.getAge()) {
                continue;
            }
            age.setAge(target);
            block.setBlockData(age, false);   // no physics: don't pop crops off farmland en masse
            changed++;
        }
        if (debug) {
            // Always report, including the zero case: a silent no-op is indistinguishable from a
            // broken scan, and that is exactly how the first version of this hid a real bug.
            plugin.getLogger().info("Catch-up: grew " + changed + " of " + cropsFound
                    + " growable crops in " + world.getName() + " for " + offline + "s offline.");
        }
    }

    private static boolean isCrop(Material type) {
        return switch (type) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA, SWEET_BERRY_BUSH,
                 TORCHFLOWER_CROP, PITCHER_CROP -> true;
            default -> false;
        };
    }
}
