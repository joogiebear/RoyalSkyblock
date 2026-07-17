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
import java.util.concurrent.ThreadLocalRandom;

/**
 * The one place that walks an island and hands its blocks to {@link BlockSimulator}s.
 *
 * <p>Every simulator needs the same three awkward things: snapshot chunks on the main thread, scan
 * them off it, then write back on the main thread. Each of those has a trap, and this feature found
 * all of them the hard way — a snapshot without {@code includeMaxblocky} throws on the first height
 * lookup; chunk sections are indexed from the world's <em>minimum</em> height, so {@code y >> 4} is
 * wrong in any world with a negative floor; and {@code getLoadedChunks()} is empty at catch-up time
 * because the event fires before the arriving player is teleported in. Doing this once, here, means
 * a simulator can't rediscover them.
 *
 * <p>Simulators read a frozen snapshot and queue changes, so they're independent of each other and
 * of ordering. Writes are re-checked against the live world before applying — the snapshot is a
 * moment old, and a player may already have harvested.
 */
public final class IslandScanner implements Listener {

    private final RoyalSkyblockPlugin plugin;
    private final Map<Material, List<BlockSimulator>> byMaterial = new EnumMap<>(Material.class);
    private final List<BlockSimulator> all = new ArrayList<>();

    public IslandScanner(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Add a simulator. Call from {@code onEnable}; there is no unregister, because a catch-up that
     * lost a simulator halfway would silently under-pay an island.
     */
    public void register(BlockSimulator simulator) {
        Set<Material> materials = simulator.materials();
        if (materials == null || materials.isEmpty()) {
            plugin.getLogger().warning("Simulator " + simulator.name() + " asked for no materials — ignored.");
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

    // ------------------------------------------------------------------ catch-up

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCatchup(IslandCatchupEvent event) {
        if (!plugin.getConfig().getBoolean("simulation.enabled", true) || byMaterial.isEmpty()) {
            return;
        }
        World world = event.getWorld();
        Island island = event.getIsland();
        long offline = event.getOfflineSeconds();
        boolean debug = plugin.getConfig().getBoolean("settings.debug", false);

        int loY = Math.max(world.getMinHeight(), plugin.getConfig().getInt("simulation.scan-y-min", 60));
        int hiY = Math.min(world.getMaxHeight() - 1, plugin.getConfig().getInt("simulation.scan-y-max", 180));
        if (loY > hiY) {
            plugin.getLogger().warning("simulation.scan-y-min is above scan-y-max — nothing will ever be "
                    + "simulated. Check config.yml.");
            return;
        }

        // The island's own footprint. NOT getLoadedChunks(): this fires the instant the world loads,
        // before the arriving player is teleported in, so almost nothing is loaded yet.
        int centreX = plugin.getConfig().getInt("island.paste.x", 0);
        int centreZ = plugin.getConfig().getInt("island.paste.z", 0);
        int radius = Math.max(16, island.radius());

        Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
        for (int cx = (centreX - radius) >> 4; cx <= (centreX + radius) >> 4; cx++) {
            for (int cz = (centreZ - radius) >> 4; cz <= (centreZ + radius) >> 4; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz);
                // includeMaxblocky MUST be true — the scan uses getHighestBlockYAt, and a snapshot
                // without the height map throws rather than degrading.
                snapshots.put(key(cx, cz), chunk.getChunkSnapshot(true, false, false));
            }
        }
        if (snapshots.isEmpty()) {
            plugin.getLogger().warning("Catch-up: no chunks to scan for " + world.getName()
                    + " — " + offline + "s of offline time is being dropped.");
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
                // The scheduler would otherwise log "generated an exception while executing task N",
                // naming no island and giving no hint that time was lost — and the catch-up has
                // already cleared the stamp, so the island never gets that time back. Say so.
                plugin.getLogger().severe("Catch-up scan failed for " + world.getName() + " — "
                        + offline + "s of offline progress was dropped: " + t);
                t.printStackTrace();
                return;
            }
            int blocksSeen = seen;
            plugin.getServer().getScheduler().runTask(plugin, () -> apply(ctx, blocksSeen, debug));
        });
    }

    /** @return how many blocks any simulator asked to see (the number that explains a no-op) */
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
                            try {
                                sim.simulate(block, ctx);
                            } catch (Throwable t) {
                                // One bad simulator must not cost the island everything else's work.
                                plugin.getLogger().warning("Simulator " + sim.name() + " failed on "
                                        + type + " at " + block.x() + "," + block.y() + "," + block.z()
                                        + ": " + t);
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
            // Re-check against what we scanned: the snapshot is a moment old and the player may
            // already have harvested. Never overwrite a block that changed under us.
            BlockData was = ctx.snapshotDataAt(p.x(), p.y(), p.z());
            if (was == null || !block.getBlockData().matches(was)) {
                continue;
            }
            block.setBlockData(e.getValue(), false);   // no physics: don't pop a whole farm off its soil
            changed++;
        }
        if (debug) {
            // Report the zero case too: silence is indistinguishable from a broken scan, which is
            // exactly how this feature hid three separate bugs.
            plugin.getLogger().info("Catch-up: changed " + changed + " of " + ctx.queued.size()
                    + " queued (" + blocksSeen + " blocks seen) in " + ctx.world.getName()
                    + " for " + ctx.offline + "s offline.");
        }
    }

    // ------------------------------------------------------------------ context

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /**
     * Block coordinate key. A record rather than a packed long: the queue is at most a few thousand
     * entries, so the packing would buy nothing measurable, and hand-rolled bit twiddling is how you
     * get an off-by-one that silently writes to the wrong block.
     */
    private record Pos(int x, int y, int z) {
    }

    /** Read-only island view + change queue, handed to every simulator. */
    private final class Context implements SimulationContext {
        private final World world;
        private final long offline;
        private final Map<Long, ChunkSnapshot> snapshots;
        private final int loY;
        private final int hiY;
        private final Map<Pos, BlockData> queued = new HashMap<>();

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
        }

        @Override
        public Random random() {
            return ThreadLocalRandom.current();
        }
    }
}
