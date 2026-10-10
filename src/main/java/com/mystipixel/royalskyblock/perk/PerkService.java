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
 * Level-gated perks, an opt-in companion to upgrades. Disabled by default, in which case every method
 * is a cheap no-op. When on, a repeating tick applies each perk's potion effects to players on their
 * own island (level permitting) and runs a perk's unlock-commands once when the island reaches its level.
 */
public final class PerkService {

    // the perks shipped in the jar, written on a fresh install, in unlock order
    private static final String[] DEFAULT_PERKS =
            {"haste", "regen", "prospector", "swift", "bountiful_veins", "homefield", "overseer",
             "scholar"};

    // shipped perks that need another plugin, and the plugin each needs
    private static final Map<String, String> PERK_REQUIREMENTS = Map.of("overseer", "EcoMinions");

    private final RoyalSkyblockPlugin plugin;
    private final List<Perk> perks = new ArrayList<>();
    private boolean enabled;
    private int refreshSeconds = 6;

    public PerkService(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
        // Settings live in config.yml and perks in perks/; a legacy perks.yml is still read. Ship the folder
        // only when there is neither a perks/ folder nor a legacy perks.yml defining perks, or the defaults
        // would shadow an admin's own versions of the same ids.
        if (!new File(plugin.getDataFolder(), "perks").isDirectory() && !legacyDefinesPerks()) {
            for (String id : DEFAULT_PERKS) {
                plugin.saveResource("perks/" + id + ".yml", false);
                parkIfUnsupported(id);
            }
        }
        reload();
    }

    // Park a shipped perk as _<id>.yml when its required plugin isn't installed; reload() skips _ files.
    // Otherwise libreforge drops the unresolvable condition and an ungated perk applies to everyone.
    private void parkIfUnsupported(String id) {
        String required = PERK_REQUIREMENTS.get(id);
        if (required == null || Bukkit.getPluginManager().isPluginEnabled(required)) {
            return;
        }
        File file = new File(plugin.getDataFolder(), "perks/" + id + ".yml");
        File parked = new File(plugin.getDataFolder(), "perks/_" + id + ".yml");
        if (file.isFile() && file.renameTo(parked)) {
            plugin.getLogger().info("Perk '" + id + "' needs " + required + ", which isn't installed; "
                    + "shipped as _" + id + ".yml (rename it to enable once " + required + " is in).");
        }
    }

    // whether a legacy perks.yml carries perk definitions rather than just the switches
    private boolean legacyDefinesPerks() {
        File file = new File(plugin.getDataFolder(), "perks.yml");
        if (!file.isFile()) {
            return false;
        }
        ConfigurationSection section =
                YamlConfiguration.loadConfiguration(file).getConfigurationSection("perks");
        return section != null && !section.getKeys(false).isEmpty();
    }

    // Read enabled and effect-refresh-seconds from config.yml, or from a legacy perks.yml that still
    // declares them (which wins, so an upgrade doesn't silently turn perks off).
    private void readSwitches(FileConfiguration legacy) {
        boolean legacyDeclares = legacy.isSet("enabled") || legacy.isSet("effect-refresh-seconds");
        if (legacyDeclares) {
            enabled = legacy.getBoolean("enabled", false);
            refreshSeconds = Math.max(2, legacy.getInt("effect-refresh-seconds", 6));
            if (warnedAboutLegacySwitches) {
                return;
            }
            warnedAboutLegacySwitches = true;
            plugin.getLogger().info("Reading perk settings from perks.yml. They now belong in "
                    + "config.yml (under perks:); copy them across and delete perks.yml, which is only "
                    + "still read so this move cannot turn your perks off silently.");
            return;
        }
        enabled = plugin.conf().getBoolean("perks.enabled", false);
        refreshSeconds = Math.max(2, plugin.conf().getInt("perks.effect-refresh-seconds", 6));
    }

    // logged once per boot, not per reload
    private boolean warnedAboutLegacySwitches;

    /**
     * Load every perk from {@code perks/*.yml} (file name is the id) and from a legacy
     * {@code perks.yml}, which is never auto-split. A folder file wins if both define the same id.
     */
    public void reload() {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "perks.yml"));
        readSwitches(cfg);
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

    // p is the file itself for a folder perk, or a section of the legacy file
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

    /** Re-run the unlock commands of every perk the island has unlocked, for its current owner. */
    public void replayUnlockCommands(Island island) {
        Profile profile = plugin.profiles().getProfile(island.profileId());
        String owner = profile == null ? "" : ownerName(profile);
        for (Perk perk : perks) {
            if (perk.requiredLevel() > island.perkLevel()) {
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
    }

    // run unlock-commands for perks newly crossed since the island's last recorded perk level
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
        plugin.writeAsync(() -> plugin.storage().saveIsland(island));
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
                // no amplifier, default 0
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
            // registry lookup unavailable, fall through
        }
        try {
            return PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
