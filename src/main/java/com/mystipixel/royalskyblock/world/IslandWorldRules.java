package com.mystipixel.royalskyblock.world;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Applies the configured world rules to island worlds: a Minecraft gamemode (enforced when a player
 * is on an island) and an arbitrary set of gamerules (applied when the island world loads).
 *
 * <p><b>Why this lives in RoyalSkyblock.</b> Island worlds are created on demand and are <em>not</em>
 * managed by Multiverse, so nothing else on the server can set their gamerules or reliably put players
 * into the right gamemode when they arrive. RoyalSkyblock owns the island lifecycle, so it does both.
 *
 * <p><b>Nothing is hardcoded.</b> The gamemode and every gamerule come straight from
 * {@code island.world-rules} in config.yml. Gamerule keys are resolved leniently — the Minecraft 26.2
 * ids ({@code keep_inventory}), the classic Bukkit names ({@code keepInventory}) and an optional
 * {@code minecraft:} prefix all work — so admins can copy names from {@code /mv gamerule list} or any
 * older guide and they still resolve. Unknown names are skipped with a warning, never a crash.
 */
public final class IslandWorldRules {

    private final RoyalSkyblockPlugin plugin;

    public IslandWorldRules(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    // ── gamerules ────────────────────────────────────────────────────────────────

    /** Apply every gamerule listed under {@code island.world-rules.gamerules} to the given world. */
    public void applyGameRules(World world) {
        if (world == null) {
            return;
        }
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("island.world-rules.gamerules");
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            GameRule<?> rule = resolveRule(key);
            if (rule == null) {
                plugin.getLogger().warning("Unknown gamerule '" + key
                        + "' in island.world-rules.gamerules — skipped.");
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
                        + type.getSimpleName() + " — skipped.");
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not set gamerule '" + key + "': " + e.getMessage());
        }
    }

    /**
     * Resolve a config key to a Bukkit {@link GameRule}, trying the key as written, then its
     * snake_case&harr;camelCase variants, so both 26.2 ids and classic names work. {@code null} if none match.
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

    // ── gamemode ─────────────────────────────────────────────────────────────────

    /**
     * Put the player into the configured island gamemode if they are on an island, enforcement is on,
     * and they lack {@code royalskyblock.playmode.bypass}. A no-op everywhere else, so the hub and any
     * other world are left entirely alone.
     */
    public void applyGameMode(Player player) {
        if (player == null
                || !plugin.getConfig().getBoolean("island.world-rules.enforce-gamemode", true)
                || player.hasPermission("royalskyblock.playmode.bypass")
                || plugin.islands().getIslandByWorld(player.getWorld()) == null) {
            return;
        }
        GameMode target = parseGameMode(plugin.getConfig().getString("island.world-rules.gamemode", "survival"));
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

    // ── name helpers ─────────────────────────────────────────────────────────────

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
