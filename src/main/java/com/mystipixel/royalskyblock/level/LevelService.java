package com.mystipixel.royalskyblock.level;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.island.Island;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Computes island levels by tallying valuable blocks — built to stay off the hot path.
 *
 * <p><b>Performance model.</b> A scan (1) figures out the island's chunk square from its centre +
 * radius, (2) snapshots those chunks a few per tick on the main thread (so there's no single big
 * spike), then (3) tallies block values on a worker thread using the thread-safe {@link ChunkSnapshot}
 * copies. Only the final model write + DB save hop back to the main thread. Per-island cooldown and a
 * de-dupe guard stop scans from stacking up. The leaderboard reads stored levels and never scans live.
 */
public final class LevelService {

    private final RoyalSkyblockPlugin plugin;
    private final LevelConfig config;

    /** island id → epoch millis of its last completed scan (for the cooldown). */
    private final Map<UUID, Long> lastScan = new ConcurrentHashMap<>();
    /** island ids with a scan in flight — never run two at once for the same island. */
    private final java.util.Set<UUID> scanning = ConcurrentHashMap.newKeySet();
    /** island id → the block counts from its last scan (for the level breakdown GUI). */
    private final Map<UUID, Map<Material, Long>> breakdowns = new ConcurrentHashMap<>();

    public LevelService(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.config = new LevelConfig(plugin);
    }

    public void reload() {
        config.reload();
    }

    public LevelConfig config() {
        return config;
    }

    /** Seconds left on this island's recalc cooldown, or 0 if it can be scanned now. */
    public long cooldownRemaining(Island island) {
        Long last = lastScan.get(island.id());
        if (last == null) {
            return 0;
        }
        long elapsed = (nowMillis() - last) / 1000L;
        return Math.max(0, config.cooldownSeconds() - elapsed);
    }

    public boolean isScanning(Island island) {
        return scanning.contains(island.id());
    }

    /** The block counts from the island's last scan (empty if it hasn't been scanned this session). */
    public Map<Material, Long> breakdown(Island island) {
        return breakdowns.getOrDefault(island.id(), Map.of());
    }

    /**
     * Recalculate an island's level. Resolves to the new level, or the current stored level if it can't
     * scan right now (world not loaded, already scanning, or on cooldown) — callers can always show
     * {@code island.level()} meanwhile.
     */
    public CompletableFuture<Double> recalc(Island island) {
        if (scanning.contains(island.id()) || cooldownRemaining(island) > 0) {
            return CompletableFuture.completedFuture(island.level());
        }
        World world = Bukkit.getWorld(island.worldName());
        if (world == null) {
            return CompletableFuture.completedFuture(island.level());
        }
        scanning.add(island.id());

        int cx = pasteAxis("x");
        int cz = pasteAxis("z");
        int r = Math.max(1, island.radius());
        int minCX = (cx - r) >> 4, maxCX = (cx + r) >> 4;
        int minCZ = (cz - r) >> 4, maxCZ = (cz + r) >> 4;

        CompletableFuture<Double> result = new CompletableFuture<>();
        gatherSnapshots(world, minCX, maxCX, minCZ, maxCZ)
                .thenApplyAsync(this::tally)                       // heavy sum off the main thread
                .thenAccept(totals -> runMain(() -> {             // model write + persist back on main
                    double level = config.levelFor(totals.points);
                    island.setLevel(level);
                    breakdowns.put(island.id(), totals.counts);
                    lastScan.put(island.id(), nowMillis());
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.storage().saveIsland(island));
                    scanning.remove(island.id());
                    result.complete(level);
                }))
                .exceptionally(error -> {
                    runMain(() -> {
                        scanning.remove(island.id());
                        plugin.getLogger().warning("Island level scan failed for " + island.id() + ": " + error.getMessage());
                        result.complete(island.level());
                    });
                    return null;
                });
        return result;
    }

    // ── gathering: snapshot chunks a few per tick on the main thread ─────────────────

    private CompletableFuture<List<ChunkSnapshot>> gatherSnapshots(World world, int minCX, int maxCX, int minCZ, int maxCZ) {
        List<int[]> coords = new ArrayList<>();
        for (int x = minCX; x <= maxCX; x++) {
            for (int z = minCZ; z <= maxCZ; z++) {
                coords.add(new int[]{x, z});
            }
        }
        CompletableFuture<List<ChunkSnapshot>> done = new CompletableFuture<>();
        List<ChunkSnapshot> snaps = Collections.synchronizedList(new ArrayList<>());
        if (coords.isEmpty()) {
            done.complete(snaps);
            return done;
        }
        AtomicInteger index = new AtomicInteger(0);
        AtomicInteger remaining = new AtomicInteger(coords.size());
        int perTick = config.chunksPerTick();

        new BukkitRunnable() {
            @Override
            public void run() {
                for (int n = 0; n < perTick && index.get() < coords.size(); n++) {
                    int[] c = coords.get(index.getAndIncrement());
                    // gen=false: never generate new terrain just to weigh it.
                    world.getChunkAtAsync(c[0], c[1], false).thenAccept(chunk -> {
                        try {
                            if (chunk != null) {
                                // includeMaxBlockY=true (needed for getHighestBlockYAt); no biome/temp data.
                                snaps.add(chunk.getChunkSnapshot(true, false, false));
                            }
                        } catch (Throwable ignored) {
                            // snapshot failed for this chunk — count it as empty
                        } finally {
                            if (remaining.decrementAndGet() == 0) {
                                done.complete(snaps);
                            }
                        }
                    }).exceptionally(ex -> {
                        if (remaining.decrementAndGet() == 0) {
                            done.complete(snaps);
                        }
                        return null;
                    });
                }
                if (index.get() >= coords.size()) {
                    cancel(); // all loads launched; completions finish the future
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return done;
    }

    // ── tallying: pure block-type reads off the main thread ─────────────────────────

    private ScanTotals tally(List<ChunkSnapshot> snapshots) {
        long points = 0;
        Map<Material, Long> counts = new EnumMap<>(Material.class);
        int minY = config.minY();
        int maxY = config.maxY();
        for (ChunkSnapshot snapshot : snapshots) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int top = Math.min(maxY, snapshot.getHighestBlockYAt(x, z));
                    for (int y = minY; y <= top; y++) {
                        Material material = snapshot.getBlockType(x, y, z);
                        long value = config.value(material);
                        if (value != 0) {
                            points += value;
                            counts.merge(material, 1L, Long::sum);
                        }
                    }
                }
            }
        }
        return new ScanTotals(points, counts);
    }

    private int pasteAxis(String axis) {
        ConfigurationSection paste = plugin.getConfig().getConfigurationSection("island.paste");
        return paste != null ? paste.getInt(axis, 0) : 0;
    }

    private void runMain(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /** {@code System.currentTimeMillis()} isolated so the scheduling stays testable. */
    private long nowMillis() {
        return System.currentTimeMillis();
    }

    /** Raw scan output: total points and per-block counts. */
    private record ScanTotals(long points, Map<Material, Long> counts) {
    }
}
