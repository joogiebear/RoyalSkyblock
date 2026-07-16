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

    private final RoyalSkyblockPlugin plugin;
    private final List<Perk> perks = new ArrayList<>();
    private boolean enabled;
    private int refreshSeconds = 6;

    public PerkService(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
        if (!new File(plugin.getDataFolder(), "perks.yml").exists()) {
            plugin.saveResource("perks.yml", false);
        }
        reload();
    }

    public void reload() {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "perks.yml"));
        enabled = cfg.getBoolean("enabled", false);
        refreshSeconds = Math.max(2, cfg.getInt("effect-refresh-seconds", 6));
        perks.clear();
        ConfigurationSection section = cfg.getConfigurationSection("perks");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection p = section.getConfigurationSection(key);
                if (p == null) {
                    continue;
                }
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
        perks.sort(Comparator.comparingInt(Perk::requiredLevel));
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
