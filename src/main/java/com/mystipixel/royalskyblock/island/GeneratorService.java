package com.mystipixel.royalskyblock.island;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.upgrade.UpgradeDef;
import com.mystipixel.royalskyblock.upgrade.UpgradeEffect;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The ore generator: what a cobblestone generator produces on a given island.
 *
 * <p>Tiers are unlocked through the upgrade track with the {@code generator} effect, so progression
 * reuses the existing costs, timers and menu rather than inventing a second system. Each tier is a
 * weighted table of materials, read from {@code generators.yml}.
 *
 * <p>Block formation is a hot event — a single player on a decent generator triggers it several times
 * a second, and a redstone farm far more — so the per-island tier is cached and the weights are
 * flattened into cumulative totals once at load, leaving one random number and a short scan per block.
 */
public final class GeneratorService {

    /** One tier's drop table, pre-summed so picking is a single random draw and a walk. */
    private record Tier(List<Material> materials, List<Integer> cumulative, int total) {

        Material pick() {
            if (materials.isEmpty()) {
                return Material.COBBLESTONE;
            }
            int roll = ThreadLocalRandom.current().nextInt(total);
            for (int i = 0; i < cumulative.size(); i++) {
                if (roll < cumulative.get(i)) {
                    return materials.get(i);
                }
            }
            return materials.get(materials.size() - 1);
        }
    }

    /** How long an island's resolved tier is reused before being looked up again. */
    private static final long TIER_CACHE_MILLIS = 5_000L;

    private record CachedTier(int tier, long expiresAt) {
    }

    private final RoyalSkyblockPlugin plugin;
    private final Map<Integer, Tier> tiers = new LinkedHashMap<>();
    private final Map<UUID, CachedTier> tierCache = new ConcurrentHashMap<>();
    private final Set<Material> replaces = new HashSet<>();

    private boolean enabled;
    private int defaultTier = 1;

    public GeneratorService(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public boolean enabled() {
        return enabled;
    }

    /** Whether a block that just formed is one the generator takes over. */
    public boolean handles(Material formed) {
        return enabled && replaces.contains(formed);
    }

    public void reload() {
        tiers.clear();
        replaces.clear();
        tierCache.clear();

        File file = new File(plugin.getDataFolder(), "generators.yml");
        if (!file.exists()) {
            plugin.saveResource("generators.yml", false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        this.enabled = cfg.getBoolean("enabled", true);
        this.defaultTier = Math.max(1, cfg.getInt("default-tier", 1));

        for (String raw : cfg.getStringList("replace")) {
            Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                plugin.getLogger().warning("[generators] replace: '" + raw + "' isn't a material — ignoring it.");
                continue;
            }
            replaces.add(material);
        }
        if (replaces.isEmpty()) {
            replaces.add(Material.COBBLESTONE);
        }

        ConfigurationSection section = cfg.getConfigurationSection("tiers");
        if (section == null) {
            plugin.getLogger().warning("[generators] no 'tiers:' section — the generator will produce"
                    + " ordinary cobblestone.");
            this.enabled = false;
            return;
        }
        for (String key : section.getKeys(false)) {
            int number;
            try {
                number = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("[generators] tier '" + key + "' isn't a number — skipping it.");
                continue;
            }
            ConfigurationSection weights = section.getConfigurationSection(key + ".weights");
            if (weights == null) {
                plugin.getLogger().warning("[generators] tier " + key + " has no weights — skipping it.");
                continue;
            }
            List<Material> materials = new ArrayList<>();
            List<Integer> cumulative = new ArrayList<>();
            int running = 0;
            for (String materialKey : weights.getKeys(false)) {
                Material material = Material.matchMaterial(materialKey.toUpperCase(Locale.ROOT));
                int weight = weights.getInt(materialKey, 0);
                if (material == null || !material.isBlock()) {
                    plugin.getLogger().warning("[generators] tier " + key + ": '" + materialKey
                            + "' isn't a placeable block — skipping it.");
                    continue;
                }
                if (weight <= 0) {
                    continue;
                }
                running += weight;
                materials.add(material);
                cumulative.add(running);
            }
            if (materials.isEmpty()) {
                plugin.getLogger().warning("[generators] tier " + key + " has no usable weights — skipping it.");
                continue;
            }
            tiers.put(number, new Tier(List.copyOf(materials), List.copyOf(cumulative), running));
        }
        if (tiers.isEmpty()) {
            plugin.getLogger().warning("[generators] no usable tiers were loaded — the generator is off.");
            this.enabled = false;
        }
    }

    /**
     * What this island's generator should produce for one formed block.
     *
     * <p>Falls back to the highest tier at or below the island's, so a table that only defines tiers
     * 1, 3 and 5 still works and an island on tier 4 gets tier 3's output rather than nothing.
     */
    public Material roll(Island island) {
        Tier tier = tierFor(tierOf(island));
        return tier == null ? Material.COBBLESTONE : tier.pick();
    }

    private Tier tierFor(int wanted) {
        Tier exact = tiers.get(wanted);
        if (exact != null) {
            return exact;
        }
        Tier best = null;
        int bestKey = Integer.MIN_VALUE;
        for (Map.Entry<Integer, Tier> entry : tiers.entrySet()) {
            if (entry.getKey() <= wanted && entry.getKey() > bestKey) {
                bestKey = entry.getKey();
                best = entry.getValue();
            }
        }
        return best;
    }

    /** The island's generator tier, from its upgrade track. Cached — this is asked per formed block. */
    private int tierOf(Island island) {
        CachedTier cached = tierCache.get(island.id());
        long now = System.currentTimeMillis();
        if (cached != null && now < cached.expiresAt()) {
            return cached.tier();
        }
        int tier = defaultTier;
        UpgradeDef def = plugin.upgrades().firstWithEffect(UpgradeEffect.GENERATOR);
        if (def != null) {
            int current = island.upgradeTier(def.key());
            if (current > 0) {
                tier = (int) def.valueAt(current);
            }
        }
        tierCache.put(island.id(), new CachedTier(tier, now + TIER_CACHE_MILLIS));
        return tier;
    }

    /** Drop a cached tier immediately, so a completed upgrade takes effect on the next block. */
    public void invalidate(UUID islandId) {
        tierCache.remove(islandId);
    }
}
