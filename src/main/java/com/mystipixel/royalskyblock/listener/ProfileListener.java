package com.mystipixel.royalskyblock.listener;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
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

    /**
     * Read the connecting player's profile data while they are still logging in. This event runs off
     * the server thread, so the database work costs the server nothing; by the time {@link #onJoin}
     * fires the state is in memory and applying it is instant.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            plugin.profiles().preload(event.getUniqueId());
        }
    }

    /** Another plugin denied the login after we preloaded — drop the data so it can't go stale. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            plugin.profiles().discardPreload(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.profiles().handleJoin(player);

        // Route players to the configured spawn (hub) on join — but leave anyone who logged out on
        // their own island where they are. Deferred a tick so it runs after the join teleport settles.
        if (plugin.getConfig().getBoolean("spawn.teleport-on-join", true)
                && plugin.islands().getIslandByWorld(player.getWorld()) == null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && plugin.islands().getIslandByWorld(player.getWorld()) == null) {
                    plugin.islands().sendToSpawn(player);
                }
            });
        }
    }

    /**
     * Send bedless/anchorless deaths back to the configured spawn (hub) instead of Minecraft's default
     * respawn point. A player who set a bed or respawn anchor keeps it — those are respected.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!plugin.getConfig().getBoolean("spawn.teleport-on-join", true)) {
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
