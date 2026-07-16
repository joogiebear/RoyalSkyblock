package com.mystipixel.royalskyblock.level;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Loads {@code levels.yml}: the block→points table and the scan tuning knobs. A block absent from the
 * table is worth zero (so the scanner skips it cheaply). Reloaded via {@link #reload()}.
 */
public final class LevelConfig {

    private final JavaPlugin plugin;
    private final Map<Material, Long> values = new EnumMap<>(Material.class);

    private long pointsPerLevel = 100;
    private int minY = 40;
    private int maxY = 200;
    private int chunksPerTick = 4;
    private long cooldownSeconds = 60;

    public LevelConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        saveDefault();
        reload();
    }

    private void saveDefault() {
        if (!new File(plugin.getDataFolder(), "levels.yml").exists()) {
            plugin.saveResource("levels.yml", false);
        }
    }

    public void reload() {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "levels.yml"));
        pointsPerLevel = Math.max(1, cfg.getLong("points-per-level", 100));
        minY = cfg.getInt("scan.min-y", 40);
        maxY = cfg.getInt("scan.max-y", 200);
        if (maxY < minY) {
            int tmp = minY;
            minY = maxY;
            maxY = tmp;
        }
        chunksPerTick = Math.max(1, cfg.getInt("scan.chunks-per-tick", 4));
        cooldownSeconds = Math.max(0, cfg.getLong("scan.recalc-cooldown-seconds", 60));

        values.clear();
        ConfigurationSection blocks = cfg.getConfigurationSection("blocks");
        if (blocks != null) {
            for (String key : blocks.getKeys(false)) {
                long points = blocks.getLong(key, 0);
                if (points <= 0) {
                    continue; // zero/negative = don't count it (keeps the scan lookup lean)
                }
                Material material = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
                if (material != null && material.isBlock()) {
                    values.put(material, points);
                } else {
                    plugin.getLogger().warning("levels.yml: '" + key + "' isn't a valid block — skipping.");
                }
            }
        }
    }

    /** Points for a block, or 0 if it doesn't count. */
    public long value(Material material) {
        return values.getOrDefault(material, 0L);
    }

    public Map<Material, Long> values() {
        return values;
    }

    /** Level for a raw point total. */
    public double levelFor(long points) {
        return (double) points / pointsPerLevel;
    }

    public long pointsPerLevel() {
        return pointsPerLevel;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public int chunksPerTick() {
        return chunksPerTick;
    }

    public long cooldownSeconds() {
        return cooldownSeconds;
    }
}
