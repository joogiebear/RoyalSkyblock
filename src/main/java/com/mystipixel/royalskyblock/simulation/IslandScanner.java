package com.mystipixel.royalskyblock.simulation;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.api.BlockSimulator;
import com.mystipixel.royalskyblock.api.IslandCatchupEvent;
import com.mystipixel.royalskyblock.api.SimBlock;
import com.mystipixel.royalskyblock.api.SimulationContext;
import com.mystipixel.royalskyblock.island.Island;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Walks an island once on catch-up and hands its blocks to the registered {@link BlockSimulator}s:
 * snapshot chunks on the main thread, scan off it, write back on it.
 *
 * <p>Traps handled here: snapshots need {@code includeMaxblocky} or the first height lookup throws;
 * chunk sections are indexed from the world's minimum height, not y=0; and {@code getLoadedChunks()}
 * is empty at catch-up time. Writes are re-checked against the live world, since a player may have
 * harvested since the snapshot.
 */
public final class IslandScanner implements Listener {

    // how long a catch-up waits for the island's chunks before dropping the offline time
    private static final long CHUNK_LOAD_TIMEOUT_SECONDS = 120;


    private final RoyalSkyblockPlugin plugin;
    private final Map<Material, List<BlockSimulator>> byMaterial = new EnumMap<>(Material.class);
    private final List<BlockSimulator> all = new ArrayList<>();

    public IslandScanner(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Add a simulator. Call from {@code onEnable}. There is no unregister: losing a simulator mid catch-up
     * would under-pay an island.
     */
    public void register(BlockSimulator simulator) {
        Set<Material> materials = simulator.materials();
        if (materials == null || materials.isEmpty()) {
            plugin.getLogger().warning("Simulator " + simulator.name() + " asked for no materials: ignored.");
            return;
        }
        all.add(simulator);
        for (Material m : materials) {
            byMaterial.computeIfAbsent(m, k -> new ArrayList<>()).add(simulator);
        }
    }

    public List<BlockSimulator> registered() {
        return List.copyOf(all);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCatchup(IslandCatchupEvent event) {
        if (!plugin.conf().getBoolean("simulation.enabled", true) || byMaterial.isEmpty()) {
            return;
        }
        World world = event.getWorld();
        Island island = event.getIsland();
        long offline = event.getOfflineSeconds();
        boolean debug = plugin.conf().getBoolean("settings.debug", false);

        int loY = Math.max(world.getMinHeight(), plugin.conf().getInt("simulation.scan-y-min", 60));
        int hiY = Math.min(world.getMaxHeight() - 1, plugin.conf().getInt("simulation.scan-y-max", 180));
        if (loY > hiY) {
            plugin.getLogger().warning("simulation.scan-y-min is above scan-y-max: nothing will ever be "
                    + "simulated. Check config.yml.");
            return;
        }

        // the island's own footprint, not getLoadedChunks(): this fires before the arriving player is
        // teleported in, so almost nothing is loaded yet
        int centreX = plugin.conf().getInt("island.paste.x", 0);
        int centreZ = plugin.conf().getInt("island.paste.z", 0);
        int radius = Math.max(16, island.radius());

        // Loaded asynchronously, then snapshotted, to avoid a lag spike when an island wakes up. gen=false:
        // an ungenerated chunk is empty void, so it is skipped rather than created.
        List<long[]> coords = new ArrayList<>();
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (int cx = (centreX - radius) >> 4; cx <= (centreX + radius) >> 4; cx++) {
            for (int cz = (centreZ - radius) >> 4; cz <= (centreZ + radius) >> 4; cz++) {
                coords.add(new long[]{cx, cz});
                loads.add(world.getChunkAtAsync(cx, cz, false));
            }
        }
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
                .orTimeout(CHUNK_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // back on the main thread either way: a timeout completes on a timer thread, and snapshots need the main thread
                .whenComplete((ignored, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().warning("Catch-up: chunks of " + world.getName() + " did not load ("
                                + error + "): " + offline + "s of offline progress is being dropped.");
                        return;
                    }
                    Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
                    for (int i = 0; i < loads.size(); i++) {
                        Chunk chunk = loads.get(i).join();
                        if (chunk != null) {
                            // includeMaxblocky must be true: the scan uses getHighestBlockYAt, which throws without the height map
                            snapshots.put(key((int) coords.get(i)[0], (int) coords.get(i)[1]),
                                    chunk.getChunkSnapshot(true, false, false));
                        }
                    }
                    scanSnapshots(world, offline, radius, loY, hiY, snapshots, debug);
                }));
    }

    // snapshots in hand: scan them off the main thread, then write the results back on it
    private void scanSnapshots(World world, long offline, int radius, int loY, int hiY,
                               Map<Long, ChunkSnapshot> snapshots, boolean debug) {
        if (snapshots.isEmpty()) {
            plugin.getLogger().warning("Catch-up: no chunks to scan for " + world.getName()
                    + ": " + offline + "s of offline time is being dropped.");
            return;
        }
        if (debug) {
            plugin.getLogger().info("Catch-up: scanning " + snapshots.size() + " chunks of "
                    + world.getName() + " (radius " + radius + ", y " + loY + ".." + hiY + ") for "
                    + offline + "s offline across " + all.size() + " simulator(s).");
        }

        Context ctx = new Context(world, offline, snapshots, loY, hiY);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            int seen;
            try {
                seen = scan(ctx);
            } catch (Throwable t) {
                // Name the island and the lost time: the stamp is already cleared, so the island never gets it back.
                plugin.getLogger().severe("Catch-up scan failed for " + world.getName() + "; "
                        + offline + "s of offline progress was dropped: " + t);
                t.printStackTrace();
                return;
            }
            int blocksSeen = seen;
            plugin.getServer().getScheduler().runTask(plugin, () -> apply(ctx, blocksSeen, debug));
        });
    }

    // returns how many blocks any simulator asked to see
    private int scan(Context ctx) {
        int seen = 0;
        for (ChunkSnapshot snap : ctx.snapshots.values()) {
            int baseX = snap.getX() << 4;
            int baseZ = snap.getZ() << 4;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int highest = Math.min(ctx.hiY, snap.getHighestBlockYAt(x, z));
                    for (int y = ctx.loY; y <= highest; y++) {
                        Material type = snap.getBlockType(x, y, z);
                        List<BlockSimulator> sims = byMaterial.get(type);
                        if (sims == null) {
                            continue;
                        }
                        seen++;
                        SimBlock block = new SimBlock(baseX + x, y, baseZ + z, snap.getBlockData(x, y, z));
                        for (BlockSimulator sim : sims) {
                            ctx.sawBlock(sim.name());
                            ctx.currentSim = sim.name();   // attributes set() calls to this simulator
                            try {
                                sim.simulate(block, ctx);
                            } catch (Throwable t) {
                                // one bad simulator must not cost the island everyone else's work
                                plugin.getLogger().warning("Simulator " + sim.name() + " failed on "
                                        + type + " at " + block.x() + "," + block.y() + "," + block.z()
                                        + ": " + t);
                            } finally {
                                ctx.currentSim = null;
                            }
                        }
                    }
                }
            }
        }
        return seen;
    }

    private void apply(Context ctx, int blocksSeen, boolean debug) {
        int changed = 0;
        for (Map.Entry<Pos, BlockData> e : ctx.queued.entrySet()) {
            Pos p = e.getKey();
            Block block = ctx.world.getBlockAt(p.x(), p.y(), p.z());
            // re-check against the snapshot: the player may already have harvested; never overwrite a changed block
            BlockData was = ctx.snapshotDataAt(p.x(), p.y(), p.z());
            if (was == null || !block.getBlockData().matches(was)) {
                continue;
            }
            block.setBlockData(e.getValue(), false);   // no physics: don't pop a whole farm off its soil
            changed++;
        }
        if (debug) {
            // Report the zero case too, per simulator: "cane seen but didn't grow" and "cane never seen" are
            // different problems.
            StringBuilder perSim = new StringBuilder();
            for (BlockSimulator sim : all) {
                int[] s = ctx.statsBySim.getOrDefault(sim.name(), new int[2]);
                perSim.append(perSim.length() == 0 ? "" : ", ")
                        .append(sim.name()).append("(saw ").append(s[0])
                        .append(", queued ").append(s[1]).append(')');
            }
            plugin.getLogger().info("Catch-up: changed " + changed + " of " + ctx.queued.size()
                    + " queued (" + blocksSeen + " blocks seen) in " + ctx.world.getName()
                    + " for " + ctx.offline + "s offline: " + perSim + ".");
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    // block coordinate key; a record rather than a packed long to avoid bit-twiddling mistakes
    private record Pos(int x, int y, int z) {
    }

    private final class Context implements SimulationContext {
        private final World world;
        private final long offline;
        private final Map<Long, ChunkSnapshot> snapshots;
        private final int loY;
        private final int hiY;
        private final Map<Pos, BlockData> queued = new HashMap<>();

        // per-simulator diagnostics; single-threaded (one async scan task), so plain maps
        private final Map<String, int[]> statsBySim = new HashMap<>();   // name -> {seen, queued}
        private String currentSim;

        void sawBlock(String sim) {
            statsBySim.computeIfAbsent(sim, k -> new int[2])[0]++;
        }

        Context(World world, long offline, Map<Long, ChunkSnapshot> snapshots, int loY, int hiY) {
            this.world = world;
            this.offline = offline;
            this.snapshots = snapshots;
            this.loY = loY;
            this.hiY = hiY;
        }

        BlockData snapshotDataAt(int x, int y, int z) {
            ChunkSnapshot snap = snapshots.get(key(x >> 4, z >> 4));
            if (snap == null || y < world.getMinHeight() || y >= world.getMaxHeight()) {
                return null;
            }
            return snap.getBlockData(x & 15, y, z & 15);
        }

        @Override
        public World world() {
            return world;
        }

        @Override
        public long offlineSeconds() {
            return offline;
        }

        @Override
        public BlockData dataAt(int x, int y, int z) {
            return snapshotDataAt(x, y, z);
        }

        @Override
        public Material typeAt(int x, int y, int z) {
            BlockData d = snapshotDataAt(x, y, z);
            return d == null ? null : d.getMaterial();
        }

        @Override
        public boolean inScan(int x, int y, int z) {
            return snapshots.containsKey(key(x >> 4, z >> 4))
                    && y >= world.getMinHeight() && y < world.getMaxHeight();
        }

        @Override
        public void set(int x, int y, int z, BlockData data) {
            synchronized (queued) {
                queued.put(new Pos(x, y, z), data);
            }
            if (currentSim != null) {
                statsBySim.computeIfAbsent(currentSim, k -> new int[2])[1]++;
            }
        }

        @Override
        public Random random() {
            return ThreadLocalRandom.current();
        }
    }
}
