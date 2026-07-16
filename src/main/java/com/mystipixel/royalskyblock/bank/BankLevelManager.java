package com.mystipixel.royalskyblock.bank;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Loads bank levels + settings from {@code bank.yml} (ported from RoyalBank's engine). Each level has a
 * max balance, interest tranches, and an upgrade cost (money + optional items). Reloaded via
 * {@link #reload()}.
 */
public final class BankLevelManager {

    private final JavaPlugin plugin;
    private volatile Map<Integer, BankLevel> levels = Collections.emptyMap();
    private volatile FileConfiguration cfg = new YamlConfiguration();

    public BankLevelManager(JavaPlugin plugin) {
        this.plugin = plugin;
        saveDefault();
        reload();
    }

    private void saveDefault() {
        if (!new File(plugin.getDataFolder(), "bank.yml").exists()) {
            plugin.saveResource("bank.yml", false);
        }
    }

    public void reload() {
        this.cfg = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "bank.yml"));
        Map<Integer, BankLevel> newLevels = new LinkedHashMap<>();
        ConfigurationSection section = cfg.getConfigurationSection("levels");
        if (section == null) {
            plugin.getLogger().warning("bank.yml has no 'levels' section — bank disabled.");
            this.levels = Collections.emptyMap();
            return;
        }
        for (String key : section.getKeys(false)) {
            int levelNumber;
            try {
                levelNumber = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("bank.yml: skipping non-number level key '" + key + "'.");
                continue;
            }
            ConfigurationSection ls = section.getConfigurationSection(key);
            if (ls == null) {
                continue;
            }
            double fallbackPercent = ls.getDouble("daily-interest-percent", 0.0);
            double maxBalance = ls.getDouble("max-balance", 0.0);
            newLevels.put(levelNumber, new BankLevel(
                    levelNumber,
                    ls.getString("name", "Level " + levelNumber),
                    maxBalance,
                    fallbackPercent,
                    ls.getDouble("max-interest", cfg.getDouble("settings.max-daily-interest", 50000.0)),
                    ls.getDouble("upgrade-cost.money", 0.0),
                    loadRequirements(ls.getConfigurationSection("upgrade-cost")),
                    loadTranches(ls.getConfigurationSection("interest-tranches"), fallbackPercent, maxBalance)));
        }
        this.levels = Collections.unmodifiableMap(newLevels);
    }

    private List<InterestTranche> loadTranches(ConfigurationSection section, double fallbackPercent, double maxBalance) {
        if (section == null) {
            if (fallbackPercent <= 0.0) {
                return Collections.emptyList();
            }
            double upper = maxBalance > 0.0 ? maxBalance : Double.MAX_VALUE;
            return List.of(new InterestTranche(0.0, upper, fallbackPercent));
        }
        List<InterestTranche> tranches = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection ts = section.getConfigurationSection(key);
            if (ts == null) {
                continue;
            }
            double from = ts.getDouble("from", 0.0);
            double to = ts.getDouble("to", 0.0);
            double percent = ts.getDouble("percent", 0.0);
            if (to > from && percent > 0.0) {
                tranches.add(new InterestTranche(from, to, percent));
            }
        }
        return tranches.stream().sorted(Comparator.comparingDouble(InterestTranche::from)).toList();
    }

    private List<ItemRequirement> loadRequirements(ConfigurationSection upgradeSection) {
        if (upgradeSection == null || !upgradeSection.isList("items")) {
            return Collections.emptyList();
        }
        List<ItemRequirement> requirements = new ArrayList<>();
        for (Object raw : upgradeSection.getList("items", Collections.emptyList())) {
            ItemRequirement req = parseRequirement(raw);
            if (req != null) {
                requirements.add(req);
            }
        }
        return requirements;
    }

    private ItemRequirement parseRequirement(Object raw) {
        if (raw instanceof String s) {
            String[] p = s.trim().split(":");
            if (p.length != 3) {
                return null;
            }
            String namespace = p[0].trim().toLowerCase(Locale.ROOT);
            String id = p[1].trim();
            int amount = parseAmount(p[2].trim());
            if (id.isEmpty() || amount <= 0) {
                return null;
            }
            if (namespace.equals("minecraft") || namespace.equals("vanilla")) {
                Material material = Material.matchMaterial(id);
                return material == null || material.isAir() ? null
                        : new ItemRequirement(RequirementType.VANILLA, material, null, amount);
            }
            return new ItemRequirement(RequirementType.ECOITEMS, null, namespace + ":" + id.toLowerCase(Locale.ROOT), amount);
        }
        return null; // only the compact "<ns>:<id>:<amount>" form is supported here
    }

    private int parseAmount(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public FileConfiguration config() {
        return cfg;
    }

    public Optional<BankLevel> getLevel(int level) {
        return Optional.ofNullable(levels.get(level));
    }

    public Optional<BankLevel> getNextLevel(int currentLevel) {
        return getLevel(currentLevel + 1);
    }

    public int getStartingLevel() {
        int configured = cfg.getInt("settings.starting-level", 1);
        if (levels.containsKey(configured)) {
            return configured;
        }
        return levels.keySet().stream().min(Comparator.naturalOrder()).orElse(configured);
    }

    public boolean isEmpty() {
        return levels.isEmpty();
    }

    public BankLevel effectiveLevel(int level) {
        return getLevel(level)
                .or(() -> getLevel(getStartingLevel()))
                .or(() -> levels.values().stream().findFirst())
                .orElse(new BankLevel(1, "Basic", Double.MAX_VALUE, 0, 0, 0, List.of(), List.of()));
    }
}
