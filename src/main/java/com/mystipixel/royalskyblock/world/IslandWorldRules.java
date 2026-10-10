package com.mystipixel.royalskyblock.world;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Applies {@code island.world-rules} to island worlds: a gamemode (enforced when a player is on an
 * island) and gamerules (applied when the world loads). Island worlds aren't managed by Multiverse, so
 * nothing else sets these.
 *
 * <p>Gamerule keys resolve leniently: 26.2 ids ({@code keep_inventory}), classic names
 * ({@code keepInventory}) and a {@code minecraft:} prefix all work. Unknown names are skipped with a
 * warning.
 */
public final class IslandWorldRules {

    private final RoyalSkyblockPlugin plugin;

    public IslandWorldRules(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    /** Apply every gamerule listed under {@code island.world-rules.gamerules} to the given world. */
    public void applyGameRules(World world) {
        if (world == null) {
            return;
        }
        ConfigurationSection sec = plugin.conf().getConfigurationSection("island.world-rules.gamerules");
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            GameRule<?> rule = resolveRule(key);
            if (rule == null) {
                plugin.getLogger().warning("Unknown gamerule '" + key
                        + "' in island.world-rules.gamerules: skipped.");
                continue;
            }
            applyOne(world, rule, sec, key);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyOne(World world, GameRule<?> rule, ConfigurationSection sec, String key) {
        Class<?> type = rule.getType();
        try {
            if (type == Boolean.class) {
                world.setGameRule((GameRule<Boolean>) rule, sec.getBoolean(key));
            } else if (type == Integer.class) {
                world.setGameRule((GameRule<Integer>) rule, sec.getInt(key));
            } else {
                plugin.getLogger().warning("Gamerule '" + key + "' has unsupported type "
                        + type.getSimpleName() + ": skipped.");
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not set gamerule '" + key + "': " + e.getMessage());
        }
    }

    /**
     * Resolve a config key to a Bukkit {@link GameRule}, trying it as written, then its
     * snake_case&harr;camelCase variants. {@code null} if none match.
     */
    public static GameRule<?> resolveRule(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String k = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
        GameRule<?> rule = GameRule.getByName(k);
        if (rule == null) {
            rule = GameRule.getByName(snakeToCamel(k));
        }
        if (rule == null) {
            rule = GameRule.getByName(camelToSnake(k));
        }
        return rule;
    }

    /**
     * Put the player into the configured island gamemode if they are on an island, enforcement is on, and
     * they lack {@code royalskyblock.playmode.bypass}. A no-op everywhere else.
     */
    public void applyGameMode(Player player) {
        if (player == null
                || !plugin.conf().getBoolean("island.world-rules.enforce-gamemode", true)
                || player.hasPermission("royalskyblock.playmode.bypass")
                || plugin.islands().getIslandByWorld(player.getWorld()) == null) {
            return;
        }
        GameMode target = parseGameMode(plugin.conf().getString("island.world-rules.gamemode", "survival"));
        if (target != null && player.getGameMode() != target) {
            player.setGameMode(target);
        }
    }

    /** Parse a config gamemode string (case-insensitive) to a {@link GameMode}, or {@code null} if invalid. */
    public static GameMode parseGameMode(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return GameMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAGameMode) {
            return null;
        }
    }

    private static String snakeToCamel(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean upper = false;
        for (char c : s.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    private static String camelToSnake(String s) {
        StringBuilder out = new StringBuilder(s.length() + 4);
        for (char c : s.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
