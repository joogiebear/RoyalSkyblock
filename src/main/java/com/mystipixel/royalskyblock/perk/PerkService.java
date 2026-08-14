package com.mystipixel.royalskyblock.perk;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.profile.Profile;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Level-gated perks — an <b>opt-in</b> alternative/companion to upgrades. Disabled by default; when off,
 * every method is a cheap no-op (no effects, no commands, no menu). When on, a repeating tick applies
 * each perk's potion effects to players standing on their own island (level permitting) and runs a
 * perk's one-time unlock-commands the first time the island reaches its level.
 */
public final class PerkService {

    /** The perks shipped in the jar, written out on a fresh install, in unlock order. */
    private static final String[] DEFAULT_PERKS =
            {"haste", "regen", "prospector", "swift", "bountiful_veins", "homefield", "overseer",
             "scholar"};

    /** Shipped perks that need another plugin, and the plugin each needs. */
    private static final Map<String, String> PERK_REQUIREMENTS = Map.of("overseer", "EcoMinions");

    private final RoyalSkyblockPlugin plugin;
    private final List<Perk> perks = new ArrayList<>();
    private boolean enabled;
    private int refreshSeconds = 6;

    public PerkService(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
        if (!new File(plugin.getDataFolder(), "perks.yml").exists()) {
            plugin.saveResource("perks.yml", false);
        }
        // Fresh install ships the folder, not a monolith. Written only when there is neither a perks/
        // folder NOR a legacy perks.yml that already defines perks — otherwise the shipped defaults
        // would land beside an admin's own versions of the same ids and, being read first, silently
        // shadow their tuning.
        if (!new File(plugin.getDataFolder(), "perks").isDirectory() && !legacyDefinesPerks()) {
            for (String id : DEFAULT_PERKS) {
                plugin.saveResource("perks/" + id + ".yml", false);
                parkIfUnsupported(id);
            }
        }
        reload();
    }

    /**
     * Park a shipped perk as {@code _<id>.yml} when the plugin it needs isn't installed.
     *
     * <p>{@link #reload()} skips {@code _}-prefixed files, the same convention eco uses for its
     * {@code _example.yml} templates, so the perk still ships and is still documented — it just isn't
     * live on a server that cannot run it. The alternative is worse than log noise: libreforge drops a
     * condition it cannot resolve and keeps the rest, so an ungated {@code overseer} would hand the
     * minion bonus to everyone at the required level, no minions required.
     *
     * <p>Only ever runs on a fresh install, and only for perks with a declared requirement. The
     * required plugins are all in {@code softdepend}, so they have enabled by the time this asks.
     */
    private void parkIfUnsupported(String id) {
        String required = PERK_REQUIREMENTS.get(id);
        if (required == null || Bukkit.getPluginManager().isPluginEnabled(required)) {
            return;
        }
        File file = new File(plugin.getDataFolder(), "perks/" + id + ".yml");
        File parked = new File(plugin.getDataFolder(), "perks/_" + id + ".yml");
        if (file.isFile() && file.renameTo(parked)) {
            plugin.getLogger().info("Perk '" + id + "' needs " + required + ", which isn't installed — "
                    + "shipped as _" + id + ".yml (rename it to enable once " + required + " is in).");
        }
    }

    /**
     * Load every perk, from {@code perks/*.yml} and from a legacy {@code perks.yml}.
     *
     * <p>One file per perk is the layout every eco plugin uses, so a perk can be added by copying a
     * file — its name is the perk's id — with nothing to register anywhere. The toggle and refresh
     * interval stay in {@code perks.yml}, which is settings rather than content.
     *
     * <p><b>Both sources are read.</b> A server with a commented {@code perks.yml} keeps working
     * untouched; nothing is auto-split, because rewriting YAML through Bukkit strips every comment.
     * A folder file wins if both define the same id.
     */
    /** Whether a legacy perks.yml already carries perk definitions (rather than just the switches). */
    private boolean legacyDefinesPerks() {
        File file = new File(plugin.getDataFolder(), "perks.yml");
        if (!file.isFile()) {
            return false;
        }
        ConfigurationSection section =
                YamlConfiguration.loadConfiguration(file).getConfigurationSection("perks");
        return section != null && !section.getKeys(false).isEmpty();
    }

    public void reload() {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "perks.yml"));
        enabled = cfg.getBoolean("enabled", false);
        refreshSeconds = Math.max(2, cfg.getInt("effect-refresh-seconds", 6));
        perks.clear();

        File dir = new File(plugin.getDataFolder(), "perks");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml") && !name.startsWith("_"));
        if (files != null) {
            for (File f : files) {
                String id = f.getName().substring(0, f.getName().length() - 4);
                loadPerk(id, YamlConfiguration.loadConfiguration(f));
            }
        }

        ConfigurationSection section = cfg.getConfigurationSection("perks");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection p = section.getConfigurationSection(key);
                if (p == null || perks.stream().anyMatch(existing -> existing.id().equals(key))) {
                    continue; // a folder file already defined this id
                }
                loadPerk(key, p);
            }
        }
        perks.sort(Comparator.comparingInt(Perk::requiredLevel));
    }

    /** Read one perk. {@code p} is the file itself for a folder perk, or a section of the legacy file. */
    private void loadPerk(String key, ConfigurationSection p) {
        {
            {
                Material icon = Material.matchMaterial(p.getString("icon", "nether_star").toUpperCase(Locale.ROOT));
                if (icon == null || !icon.isItem()) {
                    icon = Material.NETHER_STAR;
                }
                List<PerkEffect> effects = new ArrayList<>();
                for (String raw : p.getStringList("effects")) {
                    PerkEffect effect = parseEffect(raw);
                    if (effect != null) {
                        effects.add(effect);
                    } else {
                        plugin.getLogger().warning("perks.yml: perk '" + key + "' has an unknown effect '" + raw + "'.");
                    }
                }
                perks.add(new Perk(key, p.getString("name", key), icon, p.getInt("required-level", 1),
                        p.getStringList("description"), effects, p.getStringList("unlock-commands")));
            }
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public int perkCount() {
        return perks.size();
    }

    public int refreshSeconds() {
        return refreshSeconds;
    }

    public List<Perk> perks() {
        return perks;
    }

    /** Repeating tick: apply on-island potion effects and process one-time unlock-commands. No-op when off. */
    public void tick() {
        if (!enabled || perks.isEmpty()) {
            return;
        }
        int duration = (refreshSeconds + 2) * 20;
        Set<UUID> unlocksChecked = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID activeProfile = plugin.profiles().getActiveProfileId(player.getUniqueId());
            Island island = activeProfile == null ? null : plugin.islands().getIslandByProfile(activeProfile);
            if (island == null || !player.getWorld().getName().equals(island.worldName())) {
                continue; // only while standing on your own island
            }
            int level = (int) island.level();
            for (Perk perk : perks) {
                if (perk.requiredLevel() > level) {
                    continue;
                }
                for (PerkEffect effect : perk.effects()) {
                    player.addPotionEffect(new PotionEffect(effect.type(), duration, effect.amplifier(), true, false, true));
                }
            }
            if (unlocksChecked.add(island.id())) {
                checkUnlocks(island, level);
            }
        }
    }

    /** Run unlock-commands for perks newly crossed since the island's last recorded perk level. */
    private void checkUnlocks(Island island, int level) {
        int from = island.perkLevel();
        if (level <= from) {
            return;
        }
        Profile profile = plugin.profiles().getProfile(island.profileId());
        String owner = profile == null ? "" : ownerName(profile);
        for (Perk perk : perks) {
            if (perk.unlockCommands().isEmpty() || perk.requiredLevel() <= from || perk.requiredLevel() > level) {
                continue;
            }
            for (String command : perk.unlockCommands()) {
                String parsed = command.replace("%owner%", owner)
                        .replace("%level%", String.valueOf(perk.requiredLevel()));
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Perk unlock command failed ('" + parsed + "'): " + t.getMessage());
                }
            }
        }
        island.setPerkLevel(level);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.storage().saveIsland(island));
    }

    private String ownerName(Profile profile) {
        String name = Bukkit.getOfflinePlayer(profile.owner()).getName();
        return name != null ? name : profile.name();
    }

    private PerkEffect parseEffect(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(":");
        String name = parts[0].toLowerCase(Locale.ROOT).replace("minecraft:", "").trim();
        int amplifier = 0;
        if (parts.length > 1) {
            try {
                amplifier = Math.max(0, Integer.parseInt(parts[parts.length - 1].trim()));
            } catch (NumberFormatException ignored) {
                // no amplifier — default 0
            }
        }
        PotionEffectType type = resolveEffect(name);
        return type == null ? null : new PerkEffect(type, amplifier);
    }

    private PotionEffectType resolveEffect(String name) {
        try {
            PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(name.replace(' ', '_')));
            if (type != null) {
                return type;
            }
        } catch (Throwable ignored) {
            // registry lookup unavailable — fall through
        }
        try {
            return PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
