package com.mystipixel.royalskyblock.listener;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.util.Text;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Catches a player who falls off an island before slow vanilla void damage kicks in. Below
 * {@code island.void.below-y} it does what {@code island.void.action} says: teleport home (default),
 * kill instantly (keep_inventory still applies), or nothing. The spawn world gets the same catch under
 * {@code spawn.void}, always a teleport to spawn.
 */
public final class VoidListener implements Listener {

    private final RoyalSkyblockPlugin plugin;

    // the move checks, read again whenever plugin.conf() hands back a new config (reload or a setting change)
    private @Nullable FileConfiguration readFrom;
    private String spawnWorld = "world";
    private boolean spawnVoid;
    private double spawnBelowY;
    private boolean islandVoid;
    private double islandBelowY;

    public VoidListener(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    private void readConfig() {
        FileConfiguration conf = plugin.conf();
        if (conf == readFrom) {
            return;
        }
        readFrom = conf;
        spawnWorld = conf.getString("spawn.world", "world");
        spawnVoid = conf.getBoolean("spawn.void.enabled", true);
        spawnBelowY = conf.getDouble("spawn.void.below-y", -70.0);
        islandVoid = conf.getBoolean("island.void.enabled", true);
        islandBelowY = conf.getDouble("island.void.below-y", 0.0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        readConfig();
        double y = to.getY();
        if (y >= islandBelowY && y >= spawnBelowY) {
            return;                              // cheap early-out for almost every move
        }
        if (to.getWorld().getName().equals(spawnWorld)) {
            onSpawnWorldMove(event.getPlayer(), to);
            return;
        }
        if (!islandVoid || y >= islandBelowY) {
            return;
        }
        Island island = plugin.islands().getIslandByWorld(to.getWorld());
        if (island == null) {
            return;                              // only islands and the spawn world
        }

        Player player = event.getPlayer();
        if (isExempt(player)) {
            return;
        }
        String action = plugin.conf().getString("island.void.action", "teleport").toLowerCase(Locale.ROOT);
        if (action.equals("none")) {
            return;
        }
        if (action.equals("kill")) {
            player.setHealth(0.0);
            return;
        }

        // teleport (default); teleport() zeroes fall distance, so no fall damage on arrival
        Location home = island.homeLocation();
        if (home == null || home.getWorld() == null) {
            player.setHealth(0.0);               // no home to catch to: a quick death beats slow ticks
            return;
        }
        player.teleport(home);

        double hearts = plugin.conf().getDouble("island.void.damage", 0.0);
        if (hearts > 0) {
            player.damage(hearts * 2.0);         // config is in hearts; damage() takes half-hearts
        }
        String message = plugin.conf().getString("island.void.message", "");
        if (message != null && !message.isBlank()) {
            player.sendMessage(Text.color(message));
        }
    }

    // the hub usually has no damage, so a player who walks off its edge falls forever
    private void onSpawnWorldMove(Player player, Location to) {
        if (!spawnVoid || to.getY() >= spawnBelowY || isExempt(player)) {
            return;
        }
        Location spawn = plugin.islands().resolveSpawnLocation();
        if (spawn == null || spawn.getWorld() == null) {
            return;
        }
        player.teleport(spawn);
        String message = plugin.conf().getString("spawn.void.message", "");
        if (message != null && !message.isBlank()) {
            player.sendMessage(Text.color(message));
        }
    }

    // admins below the world (creative flight, spectator, bypass) are not falling; action: kill would
    // kill them on the spot
    private static boolean isExempt(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR || player.hasPermission("royalskyblock.bypass");
    }
}
