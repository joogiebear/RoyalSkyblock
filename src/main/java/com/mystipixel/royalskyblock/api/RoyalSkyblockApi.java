package com.mystipixel.royalskyblock.api;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Static entry points for other plugins. Parameters and return values are JDK types only, so a plugin
 * with no build dependency on RoyalSkyblock can call them by reflection (see the README).
 */
public final class RoyalSkyblockApi {

    private RoyalSkyblockApi() {
    }

    /**
     * Save the player's active profile state (inventory, ender chest, XP, health, hunger, eco progression)
     * now instead of waiting for the next autosave or quit, for example after a completed trade. The state
     * is captured on the main thread and written on the storage thread, and never lands over a later save
     * of the same profile. Calls for the same player within one second are folded into a single save at the
     * end of that second, so the newest state is written and a caller cannot flood the database.
     *
     * <p>Safe from any thread: off the main thread the save is handed to the main thread.
     *
     * @return true if a save was started or queued; false if RoyalSkyblock is not enabled, or (on the main
     *         thread) the player is offline or has no loaded profile
     */
    public static boolean savePlayerNow(UUID player) {
        RoyalSkyblockPlugin plugin = RoyalSkyblockPlugin.enabledOrNull();
        if (plugin == null || player == null) {
            return false;
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> savePlayerNow(player));
            return true;
        }
        Player online = Bukkit.getPlayer(player);
        return online != null && plugin.profiles().requestSave(online);
    }
}
