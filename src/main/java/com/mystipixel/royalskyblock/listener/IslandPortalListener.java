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
 * The portal on an island's second isle sends players to spawn, not to the nether.
 *
 * <p>Handled on contact rather than through {@link PlayerPortalEvent} alone: that event only fires if
 * the server would actually move the player between dimensions, so it depends on the nether being
 * enabled and imposes vanilla's four-second delay. Reacting to entering the portal block instead makes
 * it immediate and independent of the server's dimension settings — the portal is a door home, and it
 * should behave like one.
 */
public final class IslandPortalListener implements Listener {

    /** Long enough to stop the walk-in from re-triggering, short enough to feel instant. */
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
            return;                                  // not an island world — leave vanilla alone
        }
        long now = System.currentTimeMillis();
        Long last = recent.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MILLIS) {
            return;
        }
        recent.put(player.getUniqueId(), now);
        send(player);
    }

    /** Belt and braces: if the server would still send them to the nether, it doesn't. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortal(PlayerPortalEvent event) {
        if (plugin.islands().getIslandByWorld(event.getFrom().getWorld()) != null) {
            event.setCancelled(true);
        }
    }

    private void send(Player player) {
        // Reuses the same resolution as /is spawn and island deletion, so the portal can't disagree
        // with the rest of the plugin about where home is.
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

    /** Forget a player's cooldown when they leave, so the map stays bounded. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        recent.remove(event.getPlayer().getUniqueId());
    }
}
