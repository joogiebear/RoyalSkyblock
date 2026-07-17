package com.mystipixel.royalskyblock.island;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unloads island worlds once nobody is left on them.
 *
 * <p>This is the half of the ASP design that makes it worth using: an island only costs memory and
 * tick time while someone is standing on it. Without this, every island visited since the last
 * restart stays resident and ticking forever, so the server's cost scales with islands-ever-visited
 * rather than with players online.
 *
 * <p>Each empty island gets {@code world.unload-grace-seconds} before it goes, so hopping to the hub
 * and back — or a quick relog — doesn't pay the save/load cost twice. The unload stamps
 * {@link Island#unloadedAt()}, which is the clock {@link com.mystipixel.royalskyblock.api.IslandCatchupEvent}
 * reads on the next load to work out how much time the island is owed.
 */
public final class IslandUnloadService {

    private final RoyalSkyblockPlugin plugin;

    /** worldName -> epoch millis it first became empty. Present only while empty. */
    private final Map<String, Long> emptySince = new HashMap<>();
    /** Worlds with an unload in flight, so a slow save can't be started twice. */
    private final Map<String, Boolean> unloading = new HashMap<>();

    public IslandUnloadService(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    /** Main thread, on a timer. Cheap: it only walks the islands that are actually loaded. */
    public void tick() {
        long graceMillis = Math.max(0, plugin.getConfig().getLong("world.unload-grace-seconds", 60)) * 1000L;
        int budget = Math.max(1, plugin.getConfig().getInt("world.max-unloads-per-tick", 2));
        long now = System.currentTimeMillis();

        // Collect first, then unload the longest-empty ones up to the budget. Unloading a world halts
        // its chunk system and I/O on the main thread — individually milliseconds, but a server-wide
        // logout (restart warning, network hiccup) would otherwise empty every island at once and try
        // to halt them all in a single tick. The rest simply wait for the next pass a few seconds on.
        List<Ready> ready = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            String name = world.getName();
            Island island = plugin.islands().getIslandByWorld(world);
            if (island == null) {
                continue;                       // not an island (hub, world, ...)
            }
            if (!world.getPlayers().isEmpty()) {
                emptySince.remove(name);        // someone's home — reset the clock
                continue;
            }
            if (Boolean.TRUE.equals(unloading.get(name))) {
                continue;
            }
            Long since = emptySince.get(name);
            if (since == null) {
                // Just emptied. Persist now rather than waiting out the grace period: until the
                // unload saves it, everything since the last ~5-minute autosave exists only in
                // memory, and a crash in that window costs the player work they'd already done and
                // logged off believing was safe. Cheap — a slime world is one small blob.
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
        // Longest-empty first, so a backlog drains fairly instead of starving whoever left earliest.
        ready.sort(Comparator.comparingLong(Ready::emptySince));
        for (int i = 0; i < Math.min(budget, ready.size()); i++) {
            unload(ready.get(i).island(), ready.get(i).world(), now);
        }
        if (ready.size() > budget && plugin.getConfig().getBoolean("settings.debug", false)) {
            plugin.getLogger().info("Unload queue: " + ready.size() + " islands empty, unloading "
                    + budget + " this pass.");
        }
    }

    /** An island past its grace period, with when it emptied (for fair ordering). */
    private record Ready(Island island, World world, long emptySince) {
    }

    /**
     * Persist an island's blocks without unloading it — the island keeps ticking through its grace
     * period, so this isn't the last word, just a floor under how much a crash can cost.
     */
    private void saveNow(Island island, World world) {
        plugin.worlds().saveIsland(world.getName()).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().warning("Could not save island " + world.getName()
                        + " after it emptied: " + error.getMessage());
            } else if (plugin.getConfig().getBoolean("settings.debug", false)) {
                plugin.getLogger().info("Saved island " + world.getName() + " (now empty).");
            }
        });
    }

    private void unload(Island island, World world, long now) {
        String name = world.getName();
        unloading.put(name, true);
        emptySince.remove(name);

        // Stamp BEFORE unloading: if the server dies mid-save, the island still knows roughly when
        // it stopped ticking. An over-estimate of downtime is harmless (it's clamped on the way in);
        // a missing stamp would silently skip the island's catch-up entirely.
        island.setUnloadedAt(now);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.storage().saveIsland(island));

        plugin.worlds().unloadIsland(name, true).whenComplete((ignored, error) -> {
            unloading.remove(name);
            if (error != null) {
                // Leave it loaded and let the next tick retry — losing an island's blocks to a
                // failed save is far worse than paying to keep it in memory a little longer.
                island.setUnloadedAt(0);
                plugin.getLogger().warning("Could not unload island " + name + ": " + error.getMessage()
                        + " — it stays loaded and will be retried.");
                return;
            }
            if (plugin.getConfig().getBoolean("settings.debug", false)) {
                plugin.getLogger().info("Unloaded empty island " + name + ".");
            }
        });
    }

    /** Called when an island is loaded, so a stale "empty since" can't unload a world someone just entered. */
    public void forget(String worldName) {
        emptySince.remove(worldName);
    }
}
