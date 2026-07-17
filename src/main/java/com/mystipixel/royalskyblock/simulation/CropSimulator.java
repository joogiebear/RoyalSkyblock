package com.mystipixel.royalskyblock.simulation;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.api.IslandCatchupEvent;
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
 * normal farm conditions, and rolls each stage independently. That keeps two crops planted together
 * from advancing in lockstep, which is what a fixed division would do and would look obviously fake.
 *
 * <p>Only {@link Ageable} crops are simulated — wheat, carrots, potatoes, beetroot, nether wart,
 * cocoa. Sugar cane, cactus and bamboo grow by <em>placing</em> new blocks rather than ageing, and
 * melon/pumpkin stems spawn fruit in a free neighbouring block; both need placement logic and
 * neighbour checks, so they are deliberately out of scope here and still only grow while someone is
 * on the island.
 *
 * <p>Scanning is done from {@link ChunkSnapshot}s off the main thread — an island can be 25k columns
 * and reading them inline would stall the server on every visit. Only the writes come back to the
 * main thread, batched.
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
        int minY = plugin.getConfig().getInt("simulation.crops.scan-y-min", 60);
        int maxY = plugin.getConfig().getInt("simulation.crops.scan-y-max", 180);
        World world = event.getWorld();
        long offline = event.getOfflineSeconds();

        // Snapshot on the main thread (required), then do the scanning off it.
        List<ChunkSnapshot> snapshots = new ArrayList<>();
        for (Chunk chunk : world.getLoadedChunks()) {
            snapshots.add(chunk.getChunkSnapshot(false, false, false));
        }
        if (snapshots.isEmpty()) {
            return;
        }

        int loY = Math.max(world.getMinHeight(), minY);
        int hiY = Math.min(world.getMaxHeight() - 1, maxY);
        if (loY > hiY) {
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Pending> pending = new ArrayList<>();
            for (ChunkSnapshot snap : snapshots) {
                scan(snap, loY, hiY, offline, secondsPerStage, pending);
            }
            if (pending.isEmpty()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> apply(world, pending, offline));
        });
    }

    private void scan(ChunkSnapshot snap, int loY, int hiY, long offline, double secondsPerStage,
                      List<Pending> out) {
        int baseX = snap.getX() << 4;
        int baseZ = snap.getZ() << 4;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                // Skip the empty column above the build surface cheaply rather than probing every y.
                int highest = Math.min(hiY, snap.getHighestBlockYAt(x, z));
                for (int y = loY; y <= highest; y++) {
                    if (snap.isSectionEmpty(y >> 4)) {
                        y = ((y >> 4) + 1) * 16 - 1;   // jump to the next section
                        continue;
                    }
                    Material type = snap.getBlockType(x, y, z);
                    if (!isCrop(type)) {
                        continue;
                    }
                    BlockData data = snap.getBlockData(x, y, z);
                    if (!(data instanceof Ageable age) || age.getAge() >= age.getMaximumAge()) {
                        continue;
                    }
                    int grown = GrowthModel.stagesGrown(offline, secondsPerStage,
                            age.getMaximumAge() - age.getAge(),
                            ThreadLocalRandom.current()::nextDouble);
                    if (grown > 0) {
                        out.add(new Pending(baseX + x, y, baseZ + z, age.getAge() + grown));
                    }
                }
            }
        }
    }

    private void apply(World world, List<Pending> pending, long offline) {
        int changed = 0;
        for (Pending p : pending) {
            Block block = world.getBlockAt(p.x(), p.y(), p.z());
            BlockData data = block.getBlockData();
            // Re-check: the snapshot is a moment old and a player may already have harvested.
            if (!(data instanceof Ageable age)) {
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
        if (changed > 0 && plugin.getConfig().getBoolean("settings.debug", false)) {
            plugin.getLogger().info("Catch-up: grew " + changed + " crops in " + world.getName()
                    + " for " + offline + "s offline.");
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
