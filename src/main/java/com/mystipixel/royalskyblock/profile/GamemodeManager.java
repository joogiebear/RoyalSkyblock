package com.mystipixel.royalskyblock.profile;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads the {@code gamemodes/<key>.yml} rulesets, one per {@link Gamemode}: display name, description,
 * icon, and the commands blocked on that mode (how Ironman disables trading).
 */
public final class GamemodeManager {

    /** One gamemode's rules. */
    public record Ruleset(String displayName, String description, String icon, Set<String> blockedCommands) {
    }

    private final JavaPlugin plugin;
    private final Map<Gamemode, Ruleset> byMode = new HashMap<>();

    public GamemodeManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        byMode.clear();
        for (Gamemode mode : Gamemode.values()) {
            String path = "gamemodes/" + mode.key() + ".yml";
            File file = new File(plugin.getDataFolder(), path);
            if (!file.exists()) {
                plugin.saveResource(path, false);
            }
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            Set<String> blocked = new HashSet<>();
            for (String cmd : cfg.getStringList("blocked-commands")) {
                blocked.add(cmd.toLowerCase(Locale.ROOT));
            }
            byMode.put(mode, new Ruleset(
                    cfg.getString("display-name", mode.name()),
                    cfg.getString("description", ""),
                    cfg.getString("icon", "grass_block"),
                    blocked));
        }
    }

    public Ruleset ruleset(Gamemode mode) {
        return byMode.getOrDefault(mode, new Ruleset(mode.name(), "", "grass_block", Set.of()));
    }

    public String displayName(Gamemode mode) {
        return ruleset(mode).displayName();
    }

    /**
     * Whether {@code command} (the typed word, no slash) is blocked on {@code mode}. The word is also
     * checked without its namespace and resolved through the command map, so listing {@code ah} covers
     * {@code /royalauctions:ah} and every alias.
     */
    public boolean isBlocked(Gamemode mode, String command) {
        Set<String> blocked = ruleset(mode).blockedCommands();
        if (blocked.isEmpty()) {
            return false;
        }
        String word = command.toLowerCase(Locale.ROOT);
        int colon = word.indexOf(':');
        if (blocked.contains(word) || (colon >= 0 && blocked.contains(word.substring(colon + 1)))) {
            return true;
        }
        Command resolved = Bukkit.getCommandMap().getCommand(word);
        if (resolved == null) {
            return false;
        }
        if (blocked.contains(resolved.getName().toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (String alias : resolved.getAliases()) {
            if (blocked.contains(alias.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public List<Gamemode> modes() {
        return List.of(Gamemode.values());
    }
}
