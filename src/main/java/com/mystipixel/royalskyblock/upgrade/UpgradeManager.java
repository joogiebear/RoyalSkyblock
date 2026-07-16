package com.mystipixel.royalskyblock.upgrade;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.currency.Cost;
import com.mystipixel.royalskyblock.island.Island;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads {@code upgrades.yml} and applies upgrade effects to islands. Per-island tiers live on the
 * {@link Island} model (persisted). Purchasing/timers are handled separately; this manager owns the
 * definitions, the current-tier lookups, and turning a tier into a concrete effect.
 */
public final class UpgradeManager {

    private final RoyalSkyblockPlugin plugin;
    private final Map<String, UpgradeDef> upgrades = new LinkedHashMap<>();

    public UpgradeManager(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        upgrades.clear();
        File file = new File(plugin.getDataFolder(), "upgrades.yml");
        if (!file.exists()) {
            plugin.saveResource("upgrades.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        for (String key : cfg.getKeys(false)) {
            ConfigurationSection sec = cfg.getConfigurationSection(key);
            if (sec == null) {
                continue;
            }
            UpgradeEffect effect = UpgradeEffect.fromString(sec.getString("effect"), UpgradeEffect.RADIUS);
            List<UpgradeTier> tiers = new ArrayList<>();
            ConfigurationSection tiersSec = sec.getConfigurationSection("tiers");
            if (tiersSec != null) {
                int n = 1;
                while (tiersSec.isConfigurationSection(String.valueOf(n)) || tiersSec.contains(String.valueOf(n))) {
                    ConfigurationSection t = tiersSec.getConfigurationSection(String.valueOf(n));
                    if (t == null) {
                        break;
                    }
                    tiers.add(new UpgradeTier(n, t.getDouble("value"),
                            parseCost(t, "cost"), parseCost(t, "skip-cost"), parseTime(t.getString("time", "0"))));
                    n++;
                }
            }
            upgrades.put(key.toLowerCase(Locale.ROOT),
                    new UpgradeDef(key.toLowerCase(Locale.ROOT), sec.getString("display-name", key),
                            sec.getString("icon", "grass_block"), sec.getString("description", ""), effect, tiers));
        }
    }

    public @Nullable UpgradeDef get(String key) {
        return key == null ? null : upgrades.get(key.toLowerCase(Locale.ROOT));
    }

    public Collection<UpgradeDef> all() {
        return upgrades.values();
    }

    // ── effects ────────────────────────────────────────────────────────────────────

    /** Set an island's tier for an upgrade, persist it, and apply the effect. */
    public void setTier(Island island, UpgradeDef def, int tier) {
        island.setUpgradeTier(def.key(), tier);
        if (def.effect() == UpgradeEffect.RADIUS) {
            double value = def.valueAt(tier);
            if (value > 0) {
                island.setRadius((int) value);
                reapplyBorder(island);
            }
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> plugin.storage().saveIsland(island));
    }

    /** Max concurrent visitors allowed on the island (base + guest-slots upgrade). */
    public int guestLimit(Island island) {
        int base = plugin.getConfig().getInt("island.base-guest-limit", 3);
        UpgradeDef def = firstWithEffect(UpgradeEffect.GUEST_SLOTS);
        int bonus = def == null ? 0 : (int) def.valueAt(island.upgradeTier(def.key()));
        return base + bonus;
    }

    /** Max coop members allowed on the island's profile (base + coop-slots upgrade). */
    public int coopMemberCap(Island island) {
        int base = plugin.getConfig().getInt("coop.max-members", 4);
        UpgradeDef def = firstWithEffect(UpgradeEffect.COOP_SLOTS);
        int bonus = def == null ? 0 : (int) def.valueAt(island.upgradeTier(def.key()));
        return base + bonus;
    }

    private @Nullable UpgradeDef firstWithEffect(UpgradeEffect effect) {
        for (UpgradeDef def : upgrades.values()) {
            if (def.effect() == effect) {
                return def;
            }
        }
        return null;
    }

    private void reapplyBorder(Island island) {
        World world = plugin.getServer().getWorld(island.worldName());
        if (world == null) {
            return;
        }
        ConfigurationSection paste = plugin.getConfig().getConfigurationSection("island.paste");
        double cx = (paste != null ? paste.getInt("x", 0) : 0) + 0.5;
        double cz = (paste != null ? paste.getInt("z", 0) : 0) + 0.5;
        org.bukkit.WorldBorder border = world.getWorldBorder();
        border.setCenter(cx, cz);
        border.setSize(Math.max(1.0, island.radius() * 2.0));
    }

    // ── parsing ────────────────────────────────────────────────────────────────────

    private Cost parseCost(ConfigurationSection tier, String key) {
        ConfigurationSection c = tier.getConfigurationSection(key);
        if (c == null) {
            return new Cost("", 0);
        }
        return new Cost(c.getString("currency", "coins"), c.getDouble("amount", 0));
    }

    /** Parse {@code 2d} / {@code 4h} / {@code 30m} / {@code 45s} / {@code 0} into seconds. */
    static long parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("0") || s.equals("instant")) {
            return 0;
        }
        try {
            char unit = s.charAt(s.length() - 1);
            if (Character.isDigit(unit)) {
                return Long.parseLong(s);
            }
            long n = Long.parseLong(s.substring(0, s.length() - 1));
            return switch (unit) {
                case 'd' -> n * 86_400;
                case 'h' -> n * 3_600;
                case 'm' -> n * 60;
                default -> n;
            };
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
