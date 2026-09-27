package com.mystipixel.royalskyblock.listener;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.profile.Profile;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

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
        if (refuseTyped(event.getPlayer(), event.getMessage()) || refuse(plugin, event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
        }
    }

    /**
     * Refuses a command listed under {@code npc-only-commands} when the player types it. Only typed
     * commands reach this listener ({@link Player#performCommand} skips the event), so NPCs, menu buttons
     * and hotbar items still run them; hub NPCs go through {@code /is admin npc-open}, which keeps the
     * gamemode gate. {@code royalskyblock.npconly.<command>} lets a player type one anyway, which is the
     * hook for perks that unlock remote access.
     */
    private boolean refuseTyped(Player player, String commandLine) {
        ConfigurationSection section = plugin.conf().getConfigurationSection("npc-only-commands");
        if (section == null || !section.getBoolean("enabled", false)) {
            return false;
        }
        ConfigurationSection commands = section.getConfigurationSection("commands");
        String key = commands == null ? null : npcOnlyKey(commands, commandWord(commandLine));
        if (key == null || player.hasPermission("royalskyblock.npconly." + key)) {
            return false;
        }
        plugin.messages().send(player, "general.npc-only", "where", commands.getString(key + ".where", key));
        return true;
    }

    /**
     * The configured key {@code word} reaches, or null. Aliases and namespaced labels resolve through the
     * command map, so listing {@code bazaar} also covers {@code /bz} and {@code /royalbazaar:bazaar}.
     */
    private static String npcOnlyKey(ConfigurationSection commands, String word) {
        if (word.isEmpty()) {
            return null;
        }
        Set<String> names = new HashSet<>();
        names.add(word);
        names.add(word.substring(word.indexOf(':') + 1));
        Command command = Bukkit.getCommandMap().getCommand(word);
        if (command != null) {
            names.add(command.getName().toLowerCase(Locale.ROOT));
            command.getAliases().forEach(alias -> names.add(alias.toLowerCase(Locale.ROOT)));
        }
        for (String key : commands.getKeys(false)) {
            if (names.contains(key.toLowerCase(Locale.ROOT))) {
                return key;
            }
        }
        return null;
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
