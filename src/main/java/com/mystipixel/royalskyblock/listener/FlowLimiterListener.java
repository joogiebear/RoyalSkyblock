package com.mystipixel.royalskyblock.listener;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-crash liquid-flow limiter. Counts water/lava flow events per world per second and, once they
 * exceed a configured rate, cancels further flow until the next second — defusing lag machines where
 * someone floods an island with cascading liquid to crash the server.
 *
 * <p>Because every island is its own world, the per-world counter is effectively per-island: one
 * island flooding itself can't starve another's flow budget.
 */
public final class FlowLimiterListener implements Listener {

    private final RoyalSkyblockPlugin plugin;
    private final Map<UUID, long[]> counters = new ConcurrentHashMap<>();  // world -> [second, count]
    private final Map<UUID, Long> lastWarn = new ConcurrentHashMap<>();
    private final Map<UUID, Long> bypassUntil = new ConcurrentHashMap<>();  // world -> bypass-until millis

    public FlowLimiterListener(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    /** A bypass-permission holder pouring liquid opens a grace window so admin builds aren't throttled. */
    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (event.getPlayer().hasPermission("royalskyblock.bypass")) {
            long seconds = plugin.getConfig().getLong("flow-limiter.admin-bypass-seconds", 30);
            bypassUntil.put(event.getBlock().getWorld().getUID(), System.currentTimeMillis() + seconds * 1000L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (!plugin.getConfig().getBoolean("flow-limiter.enabled", true)) {
            return;
        }
        Material type = event.getBlock().getType();
        if (type != Material.WATER && type != Material.LAVA) {
            return; // only throttle liquids
        }
        World world = event.getBlock().getWorld();
        Long bypass = bypassUntil.get(world.getUID());
        if (bypass != null && bypass > System.currentTimeMillis()) {
            return; // an admin is actively pouring here
        }
        int max = Math.max(1, plugin.getConfig().getInt("flow-limiter.max-per-second", 800));
        long second = System.currentTimeMillis() / 1000L;

        long[] counter = counters.computeIfAbsent(world.getUID(), k -> new long[]{second, 0});
        if (counter[0] != second) {
            counter[0] = second;
            counter[1] = 0;
        }
        counter[1]++;

        if (counter[1] > max) {
            event.setCancelled(true);
            warn(world, max);
        }
    }

    /** Log at most once every 10s per world so admins notice throttling without console spam. */
    private void warn(World world, int max) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(world.getUID());
        if (last == null || now - last > 10_000L) {
            lastWarn.put(world.getUID(), now);
            plugin.getLogger().warning("Flow limiter throttling liquid in world '" + world.getName()
                    + "' (over " + max + "/s) — possible lag machine.");
        }
    }
}
