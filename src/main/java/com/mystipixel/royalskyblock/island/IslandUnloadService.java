package com.mystipixel.royalskyblock.island;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.HashMap;
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
        long now = System.currentTimeMillis();

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
            long since = emptySince.computeIfAbsent(name, k -> now);
            if (now - since < graceMillis) {
                continue;
            }
            unload(island, world, now);
        }
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
