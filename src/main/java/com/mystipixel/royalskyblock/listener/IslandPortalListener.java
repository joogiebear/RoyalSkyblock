package com.mystipixel.royalskyblock.listener;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerPortalEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The portal on an island's second isle sends players to spawn, not the nether. Handled on contact
 * rather than through {@link PlayerPortalEvent} alone, which needs the nether enabled and has vanilla's
 * four-second delay.
 */
public final class IslandPortalListener implements Listener {

    // long enough to stop the walk-in re-triggering, short enough to feel instant
    private static final long COOLDOWN_MILLIS = 2_000L;

    private final RoyalSkyblockPlugin plugin;
    private final Map<UUID, Long> recent = new HashMap<>();

    public IslandPortalListener(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onEnterPortal(EntityPortalEnterEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (plugin.islands().getIslandByWorld(player.getWorld()) == null) {
            return;                                  // not an island world, leave vanilla alone
        }
        long now = System.currentTimeMillis();
        Long last = recent.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MILLIS) {
            return;
        }
        recent.put(player.getUniqueId(), now);
        send(player);
    }

    // backstop: if the server would still send them to the nether, it doesn't
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortal(PlayerPortalEvent event) {
        if (plugin.islands().getIslandByWorld(event.getFrom().getWorld()) != null) {
            event.setCancelled(true);
        }
    }

    private void send(Player player) {
        // same resolution as /is spawn and island deletion
        Location spawn = plugin.islands().resolveSpawnLocation();
        if (spawn == null) {
            plugin.messages().send(player, "island.portal-no-spawn");
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.teleport(spawn);
            }
        });
    }

    // forget a player's cooldown when they leave, so the map stays bounded
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        recent.remove(event.getPlayer().getUniqueId());
    }
}
