package com.mystipixel.royalskyblock.profile;

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
 * Loads the {@code gamemodes/<key>.yml} rulesets — one per {@link Gamemode}. A ruleset currently
 * carries a display name, description, an icon, and the set of commands blocked on that mode (how
 * Ironman disables trading). Fully config-driven so admins can tune what each mode allows.
 */
public final class GamemodeManager {

    /** One gamemode's rules. Extend as more rule types are added. */
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

    /** Whether {@code command} (bare word, no slash) is blocked on {@code mode}. */
    public boolean isBlocked(Gamemode mode, String command) {
        return ruleset(mode).blockedCommands().contains(command.toLowerCase(Locale.ROOT));
    }

    public List<Gamemode> modes() {
        return List.of(Gamemode.values());
    }
}
