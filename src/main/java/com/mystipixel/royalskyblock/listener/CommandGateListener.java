package com.mystipixel.royalskyblock.listener;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.profile.Profile;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;

/**
 * Enforces gamemode command rules (e.g. Ironman blocking {@code /ah}, {@code /bazaar}) by cancelling
 * blocked commands for the player's active profile. Fully decoupled — the economy plugins need no
 * changes; RoyalSkyblock just intercepts the command.
 *
 * <p>Uses a dedicated {@code royalskyblock.gamemode.bypass} node (default false) — NOT the build-
 * protection bypass — so that gamemode rules apply to everyone (ops included) on their own profile
 * unless explicitly granted the bypass.
 */
public final class CommandGateListener implements Listener {

    private final RoyalSkyblockPlugin plugin;

    public CommandGateListener(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (refuse(plugin, event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
        }
    }

    /**
     * Whether {@code player}'s active gamemode blocks {@code commandLine}; if so, tells them why.
     * Public because {@link Player#performCommand} never fires {@link PlayerCommandPreprocessEvent},
     * so anything that runs a command on a player's behalf (menu buttons) must ask here itself.
     */
    public static boolean refuse(RoyalSkyblockPlugin plugin, Player player, String commandLine) {
        if (player.hasPermission("royalskyblock.gamemode.bypass")) {
            return false;
        }
        Profile profile = plugin.profiles().getActiveProfile(player);
        if (profile == null) {
            return false;
        }
        String word = commandWord(commandLine);
        if (word.isEmpty() || !plugin.gamemodes().isBlocked(profile.gamemode(), word)) {
            return false;
        }
        plugin.messages().send(player, "profile.blocked-command",
                "gamemode", profile.gamemode().name().toLowerCase(Locale.ROOT));
        return true;
    }

    /** Extract the bare command word: {@code "/ah sell 10"} → {@code "ah"}. */
    private static String commandWord(String message) {
        String msg = message.strip();
        msg = msg.startsWith("/") ? msg.substring(1) : msg;
        int space = msg.indexOf(' ');
        String word = space >= 0 ? msg.substring(0, space) : msg;
        return word.toLowerCase(Locale.ROOT);
    }
}
