package com.mystipixel.royalskyblock.listener;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Loads a player's active-profile state when they join and saves it when they leave, so inventory,
 * ender chest, XP and eco progression stay per-profile. First-time players get a default Solo profile.
 */
public final class ProfileListener implements Listener {

    private final RoyalSkyblockPlugin plugin;

    public ProfileListener(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    // runs off the server thread while the player connects, so the database read costs the server nothing
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            plugin.profiles().preload(event.getUniqueId());
        }
    }

    // The connection closed before the join (login denied, client dropped), so the preload was never
    // used. Not PlayerLoginEvent: listening to it makes Paper disable the re-configuration API.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        plugin.profiles().discardPreload(event.getPlayerUniqueId());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.profiles().handleJoin(player);

        // Send players to spawn on join, except anyone who logged out on their own island. Deferred a tick so
        // it runs after the join teleport settles.
        if (plugin.conf().getBoolean("spawn.teleport-on-join", true)
                && plugin.islands().getIslandByWorld(player.getWorld()) == null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && plugin.islands().getIslandByWorld(player.getWorld()) == null) {
                    plugin.islands().sendToSpawn(player);
                }
            });
        }
    }

    // bedless/anchorless deaths respawn at the configured spawn; a bed or anchor is respected
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!plugin.conf().getBoolean("spawn.teleport-on-join", true)) {
            return; // spawn routing is handed off to another plugin
        }
        if (event.isBedSpawn() || event.isAnchorSpawn()) {
            return; // respect the player's own bed / anchor
        }
        Location spawn = plugin.islands().resolveSpawnLocation();
        if (spawn != null) {
            event.setRespawnLocation(spawn);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.profiles().handleQuit(event.getPlayer());
    }
}
