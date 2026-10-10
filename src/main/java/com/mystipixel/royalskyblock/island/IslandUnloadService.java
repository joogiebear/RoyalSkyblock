package com.mystipixel.royalskyblock.island;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unloads island worlds once nobody is left on them, so an island only costs memory and tick time
 * while someone is on it.
 *
 * <p>Each empty island waits {@code world.unload-grace-seconds} first, so a hub round-trip or quick
 * relog doesn't pay the save/load twice. The unload stamps {@link Island#unloadedAt()}, which
 * {@link com.mystipixel.royalskyblock.api.IslandCatchupEvent} reads on the next load.
 */
public final class IslandUnloadService {

    private final RoyalSkyblockPlugin plugin;

    // worldName to epoch millis it first became empty; present only while empty
    private final Map<String, Long> emptySince = new ConcurrentHashMap<>();
    // worlds with an unload in flight, so a slow save can't be started twice
    private final Map<String, Boolean> unloading = new ConcurrentHashMap<>();

    public IslandUnloadService(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    /** Main thread, on a timer. Only walks loaded islands. */
    public void tick() {
        long graceMillis = Math.max(0, plugin.conf().getLong("world.unload-grace-seconds", 60)) * 1000L;
        int budget = Math.max(1, plugin.conf().getInt("world.max-unloads-per-tick", 2));
        long now = System.currentTimeMillis();

        // Unload the longest-empty ones up to the budget: unloading halts chunk I/O on the main thread, and a
        // mass logout would otherwise try to unload every island in one tick.
        List<Ready> ready = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            String name = world.getName();
            Island island = plugin.islands().getIslandByWorld(world);
            if (island == null) {
                continue;                       // not an island (hub, world, ...)
            }
            if (!world.getPlayers().isEmpty()) {
                emptySince.remove(name);        // someone is here, reset the clock
                continue;
            }
            if (Boolean.TRUE.equals(unloading.get(name))) {
                continue;
            }
            Long since = emptySince.get(name);
            if (since == null) {
                // just emptied: save now rather than after the grace period, so a crash doesn't lose work since the
                // last autosave
                emptySince.put(name, now);
                saveNow(island, world);
                continue;
            }
            if (now - since < graceMillis) {
                continue;
            }
            ready.add(new Ready(island, world, since));
        }
        if (ready.isEmpty()) {
            return;
        }
        // longest-empty first, so a backlog drains fairly
        ready.sort(Comparator.comparingLong(Ready::emptySince));
        for (int i = 0; i < Math.min(budget, ready.size()); i++) {
            unload(ready.get(i).island(), ready.get(i).world(), now);
        }
        if (ready.size() > budget && plugin.conf().getBoolean("settings.debug", false)) {
            plugin.getLogger().info("Unload queue: " + ready.size() + " islands empty, unloading "
                    + budget + " this pass.");
        }
    }

    private record Ready(Island island, World world, long emptySince) {
    }

    // save without unloading; the island keeps ticking through its grace period, so this only limits
    // what a crash can cost
    private void saveNow(Island island, World world) {
        plugin.worlds().saveIsland(world.getName()).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().warning("Could not save island " + world.getName()
                        + " after it emptied: " + error.getMessage());
            } else if (plugin.conf().getBoolean("settings.debug", false)) {
                plugin.getLogger().info("Saved island " + world.getName() + " (now empty).");
            }
        });
    }

    private void unload(Island island, World world, long now) {
        String name = world.getName();
        unloading.put(name, true);
        emptySince.remove(name);

        // Stamp before unloading so a crash mid-save still leaves a stamp. Over-estimating downtime is
        // harmless (it's clamped); a missing stamp skips the catch-up.
        island.setUnloadedAt(now);
        plugin.writeAsync(() -> plugin.storage().saveIsland(island));

        // The unload future completes on whichever thread finished the save, so hop back to the main thread
        // before touching the island. The bookkeeping maps are concurrent for the same reason.
        plugin.worlds().unloadIsland(name, true).whenComplete((ignored, error) -> onMain(() -> {
            unloading.remove(name);
            if (error != null) {
                // leave it loaded and let the next tick retry rather than lose blocks to a failed save
                island.setUnloadedAt(0);
                plugin.getLogger().warning("Could not unload island " + name + ": " + error.getMessage()
                        + ". It stays loaded and will be retried.");
                return;
            }
            if (plugin.conf().getBoolean("settings.debug", false)) {
                plugin.getLogger().info("Unloaded empty island " + name + ".");
            }
        }));
    }

    // Run on the main thread, or inline if already there. Also inline during shutdown: Bukkit refuses to
    // schedule for a disabling plugin.
    private void onMain(Runnable action) {
        if (plugin.getServer().isPrimaryThread() || !plugin.isEnabled()) {
            action.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, action);
        }
    }

    /** Called when an island loads, so a stale "empty since" can't unload a world someone just entered. */
    public void forget(String worldName) {
        emptySince.remove(worldName);
    }
}
