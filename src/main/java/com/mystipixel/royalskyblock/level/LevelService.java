package com.mystipixel.royalskyblock.level;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.profile.Profile;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Computes island levels by tallying valuable blocks. A scan snapshots the island's chunks a few per
 * tick on the main thread, tallies the {@link ChunkSnapshot}s on a worker thread, then writes the model
 * and saves back on the main thread. A per-island cooldown and in-flight guard stop scans stacking up;
 * the leaderboard only reads stored levels.
 */
public final class LevelService {

    // how long a scan may take to gather its chunks before it is abandoned and the island released
    private static final long SCAN_TIMEOUT_SECONDS = 120;

    private final RoyalSkyblockPlugin plugin;
    private final LevelConfig config;

    // island id to epoch millis of its last completed scan (for the cooldown)
    private final Map<UUID, Long> lastScan = new ConcurrentHashMap<>();
    // island ids with a scan in flight; never two at once for the same island
    private final java.util.Set<UUID> scanning = ConcurrentHashMap.newKeySet();
    // island id to block counts from its last scan (level breakdown GUI). LRU-capped: evicted entries are
    // rebuilt by the next scan.
    private final Map<UUID, Map<Material, Long>> breakdowns = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Map<Material, Long>> eldest) {
                    return size() > MAX_CACHED_BREAKDOWNS;
                }
            });

    private static final int MAX_CACHED_BREAKDOWNS = 200;

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
     * Recalculate an island's level. Resolves to the new level, or the stored level if it can't scan now
     * (world not loaded, already scanning, or on cooldown).
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
                // A reload cancels the gather task and a chunk load may never finish (world unloaded mid-scan); the
                // timeout routes both through the failure path, which releases the island.
                .orTimeout(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenApplyAsync(snaps -> tally(snaps, cx, cz, r))  // heavy sum off the main thread
                .thenAccept(totals -> runMain(() -> {             // model write and persist back on main
                    double level = config.levelFor(totals.points);
                    island.setLevel(level);
                    breakdowns.put(island.id(), totals.counts);
                    lastScan.put(island.id(), nowMillis());
                    grantLevelUps(island, level);
                    plugin.writeAsync(() -> plugin.storage().saveIsland(island));
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

    // Pay rewards for every integer level newly crossed, then bump the reward marker so nothing is paid
    // twice. Main thread (console commands).
    private void grantLevelUps(Island island, double newLevel) {
        Profile profile = plugin.profiles().getProfile(island.profileId());
        // the profile's marker survives the island being deleted, so a fresh island doesn't pay again
        int from = Math.max(island.rewardLevel(), profile == null ? 0 : profile.rewardLevel());
        int to = (int) Math.floor(newLevel);
        if (to <= from) {
            return;
        }
        String owner = profile == null ? "" : ownerName(profile);
        for (int lvl = from + 1; lvl <= to; lvl++) {
            // chains run per online member (an effect targets a player); the commands below address the owner by
            // name, so they stay once per level
            if (!com.mystipixel.royalskyblock.libreforge.LevelRewardChains.isEmpty() && profile != null) {
                for (var member : profile.members()) {
                    Player online = Bukkit.getPlayer(member.uuid());
                    if (online != null) {
                        com.mystipixel.royalskyblock.libreforge.LevelRewardChains.run(online, island, lvl);
                    }
                }
            }
            List<String> commands = config.rewardsFor(lvl);
            if (commands == null) {
                continue;
            }
            for (String command : commands) {
                String parsed = command.replace("%owner%", owner).replace("%level%", String.valueOf(lvl));
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Level reward command failed ('" + parsed + "'): " + t.getMessage());
                }
            }
        }
        island.setRewardLevel(to);
        if (profile != null) {
            profile.setRewardLevel(to);
            plugin.writeAsync(() -> plugin.storage().saveProfile(profile));
        }
        notifyMembers(profile, island, to);
    }

    private void notifyMembers(Profile profile, Island island, int level) {
        if (profile == null) {
            return;
        }
        for (var member : profile.members()) {
            Player online = Bukkit.getPlayer(member.uuid());
            if (online != null) {
                plugin.messages().send(online, "level.up", "level", String.valueOf(level));
                // per online member: a libreforge trigger needs a player
                com.mystipixel.royalskyblock.libreforge.IslandTriggers.levelUp(online, island, level);
            }
        }
    }

    private String ownerName(Profile profile) {
        String name = Bukkit.getOfflinePlayer(profile.owner()).getName();
        return name != null ? name : profile.name();
    }

    /**
     * Recalc the islands players are standing on, up to {@code max-per-cycle}, skipping any on cooldown or
     * already scanning. Other islands keep their stored level.
     */
    public void autoRecalcActiveIslands() {
        int max = config.autoRecalcMaxPerCycle();
        Set<UUID> seen = new HashSet<>();
        int done = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (done >= max) {
                break;
            }
            UUID activeProfile = plugin.profiles().getActiveProfileId(player.getUniqueId());
            if (activeProfile == null) {
                continue;
            }
            Island island = plugin.islands().getIslandByProfile(activeProfile);
            if (island == null || !seen.add(island.id())) {
                continue;
            }
            if (Bukkit.getWorld(island.worldName()) == null
                    || cooldownRemaining(island) > 0 || isScanning(island)) {
                continue;
            }
            recalc(island);
            done++;
        }
    }

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
                    // gen=false: never generate new terrain just to weigh it
                    world.getChunkAtAsync(c[0], c[1], false).thenAccept(chunk -> {
                        try {
                            if (chunk != null) {
                                // includeMaxBlockY=true (needed for getHighestBlockYAt); no biome/temp data.
                                snaps.add(chunk.getChunkSnapshot(true, false, false));
                            }
                        } catch (Throwable ignored) {
                            // snapshot failed for this chunk, count it as empty
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

    // Only blocks inside the island's square count: snapshots are whole chunks, which extend past the
    // border.
    private ScanTotals tally(List<ChunkSnapshot> snapshots, int cx, int cz, int r) {
        long points = 0;
        Map<Material, Long> counts = new EnumMap<>(Material.class);
        int minY = config.minY();
        int maxY = config.maxY();
        for (ChunkSnapshot snapshot : snapshots) {
            int baseX = snapshot.getX() << 4;
            int baseZ = snapshot.getZ() << 4;
            for (int x = 0; x < 16; x++) {
                if (Math.abs(baseX + x - cx) > r) {
                    continue;
                }
                for (int z = 0; z < 16; z++) {
                    if (Math.abs(baseZ + z - cz) > r) {
                        continue;
                    }
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
        ConfigurationSection paste = plugin.conf().getConfigurationSection("island.paste");
        return paste != null ? paste.getInt(axis, 0) : 0;
    }

    private void runMain(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    // isolated so the scheduling stays testable
    private long nowMillis() {
        return System.currentTimeMillis();
    }

    private record ScanTotals(long points, Map<Material, Long> counts) {
    }
}
