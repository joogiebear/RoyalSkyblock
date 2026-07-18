package com.mystipixel.royalskyblock.listener;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.util.Text;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Locale;

/**
 * Catches a player who falls off an island before the slow vanilla void-damage ticks kick in.
 *
 * <p>Vanilla void damage is a flat ~4 per half-second, so it drags — and the higher a player's max
 * health climbs (custom stats), the longer it takes. Skyblock servers don't rely on it: they catch
 * the fall below a Y line and act. When a player on an island drops below {@code island.void.below-y}
 * this does what {@code island.void.action} says — teleport them home (default), kill instantly
 * (still respecting keep_inventory), or nothing.
 *
 * <p>Everything is config-driven; the per-move cost is two config reads plus a cheap Y compare, and
 * the island lookup only runs on the rare tick a player is actually below the line.
 */
public final class VoidListener implements Listener {

    private final RoyalSkyblockPlugin plugin;

    public VoidListener(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("island.void.enabled", true)) {
            return;
        }
        Location to = event.getTo();
        if (to == null || to.getY() >= plugin.getConfig().getDouble("island.void.below-y", 0.0)) {
            return;                              // cheap early-out for the 99.9% of moves above the line
        }
        Island island = plugin.islands().getIslandByWorld(to.getWorld());
        if (island == null) {
            return;                              // only islands — the hub/other worlds are left alone
        }

        Player player = event.getPlayer();
        String action = plugin.getConfig().getString("island.void.action", "teleport").toLowerCase(Locale.ROOT);
        if (action.equals("none")) {
            return;
        }
        if (action.equals("kill")) {
            player.setHealth(0.0);
            return;
        }

        // teleport (default): pull them back to the island home. teleport() zeroes fall distance,
        // so they don't take the accumulated fall as damage on arrival.
        Location home = island.homeLocation();
        if (home == null || home.getWorld() == null) {
            player.setHealth(0.0);               // no home to catch to — a quick death beats slow ticks
            return;
        }
        player.teleport(home);

        double hearts = plugin.getConfig().getDouble("island.void.damage", 0.0);
        if (hearts > 0) {
            player.damage(hearts * 2.0);         // config is in hearts; damage() takes half-hearts
        }
        String message = plugin.getConfig().getString("island.void.message", "");
        if (message != null && !message.isBlank()) {
            player.sendMessage(Text.color(message));
        }
    }
}
