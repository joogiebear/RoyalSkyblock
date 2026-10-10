package com.mystipixel.royalskyblock.listener;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Liquid-flow limiter against lag machines: counts water/lava flow events per world per second and
 * cancels further flow past the configured rate until the next second. Each island is its own world,
 * so the budget is effectively per island.
 */
public final class FlowLimiterListener implements Listener {

    private final RoyalSkyblockPlugin plugin;
    private final Map<UUID, long[]> counters = new ConcurrentHashMap<>();  // world -> [second, count]
    private final Map<UUID, Long> lastWarn = new ConcurrentHashMap<>();
    private final Map<UUID, Long> bypassUntil = new ConcurrentHashMap<>();  // world -> bypass-until millis

    public FlowLimiterListener(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    // a bypass holder pouring liquid opens a grace window so admin builds aren't throttled
    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (event.getPlayer().hasPermission("royalskyblock.bypass")) {
            long seconds = plugin.conf().getLong("flow-limiter.admin-bypass-seconds", 30);
            bypassUntil.put(event.getBlock().getWorld().getUID(), System.currentTimeMillis() + seconds * 1000L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (!plugin.conf().getBoolean("flow-limiter.enabled", true)) {
            return;
        }
        Material type = event.getBlock().getType();
        if (type != Material.WATER && type != Material.LAVA) {
            return;
        }
        World world = event.getBlock().getWorld();
        Long bypass = bypassUntil.get(world.getUID());
        if (bypass != null && bypass > System.currentTimeMillis()) {
            return; // an admin is actively pouring here
        }
        int max = Math.max(1, plugin.conf().getInt("flow-limiter.max-per-second", 800));
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

    // log at most once every 10s per world
    private void warn(World world, int max) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(world.getUID());
        if (last == null || now - last > 10_000L) {
            lastWarn.put(world.getUID(), now);
            plugin.getLogger().warning("Flow limiter throttling liquid in world '" + world.getName()
                    + "' (over " + max + "/s): possible lag machine.");
        }
    }

    // island worlds come and go all day, so drop a world's counters when it unloads
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID world = event.getWorld().getUID();
        counters.remove(world);
        lastWarn.remove(world);
        bypassUntil.remove(world);
    }
}
