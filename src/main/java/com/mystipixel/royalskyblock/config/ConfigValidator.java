package com.mystipixel.royalskyblock.config;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.currency.Cost;
import com.mystipixel.royalskyblock.gui.GuiManager;
import com.mystipixel.royalskyblock.island.WorldEditSchematics;
import com.mystipixel.royalskyblock.upgrade.UpgradeDef;
import com.mystipixel.royalskyblock.upgrade.UpgradeEffect;
import com.mystipixel.royalskyblock.upgrade.UpgradeTier;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Checks the loaded config for common admin mistakes and logs an actionable warning for each. Runs on
 * {@code /is reload}, and once at boot on the first tick, after every plugin has enabled (so the
 * worlds and economies it checks for exist).
 */
public final class ConfigValidator {

    private final RoyalSkyblockPlugin plugin;

    public ConfigValidator(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    // The generator track sets a tier's price and generators.yml what it drops, joined only by the
    // track's value. An unknown tier silently falls back to the one below, so a mismatch charges for
    // tier 8 and drops tier 7.
    private void checkGeneratorTiers(List<String> warnings) {
        UpgradeDef def = plugin.upgrades().firstWithEffect(UpgradeEffect.GENERATOR);
        if (def == null) {
            return;                                     // no generator track configured; nothing to join
        }
        Set<Integer> defined = plugin.generators().definedTiers();
        if (defined.isEmpty()) {
            return;                                     // generators disabled entirely, a separate switch
        }
        List<String> missing = new ArrayList<>();
        for (int t = 1; t <= def.maxTier(); t++) {
            if (def.tier(t) == null) {
                continue;
            }
            int value = (int) def.valueAt(t);
            if (!defined.contains(value)) {
                missing.add(t + " (wants generator tier " + value + ")");
            }
        }
        if (!missing.isEmpty()) {
            warnings.add("upgrade '" + def.key() + "' has tier(s) " + String.join(", ", missing)
                    + " with no matching entry in generators.yml; buying them charges full price and"
                    + " silently leaves the generator on the closest lower tier. Add those tiers to"
                    + " generators.yml.");
        }
    }

    public void validate() {
        List<String> warnings = new ArrayList<>();
        FileConfiguration cfg = plugin.conf();

        String storage = cfg.getString("storage.type", "sqlite").toLowerCase(Locale.ROOT);
        if (!Set.of("sqlite", "mysql", "eco").contains(storage)) {
            warnings.add("storage.type '" + storage + "' is invalid; use 'sqlite', 'mysql', or 'eco'.");
        }
        String worldSrc = cfg.getString("world.slime-data-source", "file").toLowerCase(Locale.ROOT);
        if (!Set.of("file", "mysql", "mongo").contains(worldSrc)) {
            warnings.add("world.slime-data-source '" + worldSrc + "' is invalid; use 'file', 'mysql', or 'mongo'.");
        }

        String borderColor = cfg.getString("island.border.color", "blue").toLowerCase(Locale.ROOT);
        if (!Set.of("off", "blue", "red", "green").contains(borderColor)) {
            warnings.add("island.border.color '" + borderColor + "' is invalid; use off, blue, red, or green.");
        }

        if (cfg.getBoolean("island.world-rules.enforce-gamemode", true)) {
            String gm = cfg.getString("island.world-rules.gamemode", "survival");
            if (com.mystipixel.royalskyblock.world.IslandWorldRules.parseGameMode(gm) == null) {
                warnings.add("island.world-rules.gamemode '" + gm + "' is not a valid gamemode; "
                        + "use survival, creative, adventure, or spectator.");
            }
        }

        if (cfg.getBoolean("island.void.enabled", true)) {
            String voidAction = cfg.getString("island.void.action", "teleport").toLowerCase(Locale.ROOT);
            if (!Set.of("teleport", "kill", "none").contains(voidAction)) {
                warnings.add("island.void.action '" + voidAction + "' is invalid; use teleport, kill, or none.");
            }
        }

        String spawnWorld = cfg.getString("spawn.world", "world");
        if (Bukkit.getWorld(spawnWorld) == null) {
            warnings.add("spawn.world '" + spawnWorld + "' is not a loaded world; players who leave or delete an "
                    + "island have nowhere to go. Set spawn.world in config.yml to your hub world.");
        }

        if (plugin.bank().levels().isEmpty()) {
            warnings.add("bank.yml has no 'levels:'; the bank won't work. Add at least one level.");
        } else if (!plugin.economyReady()) {
            warnings.add("The bank and 'coins' upgrade costs need a Vault economy, but none is installed. "
                    + "Install Vault + an economy plugin, or they stay disabled.");
        }

        if (plugin.levels().config().values().isEmpty()) {
            warnings.add("levels.yml has no 'blocks:' values; every island level will be 0. Add block point values.");
        }

        // A track with neither a content: <key> slot nor a free mask slot is silently skipped in the menu.
        // Menus are keyed by basename: ask for GuiManager.UPGRADES, not "island/upgrades".
        var upgradesMenu = plugin.gui() == null ? null : plugin.gui().template(GuiManager.UPGRADES);
        if (upgradesMenu == null) {
            warnings.add("the upgrades menu template did not load, so its slots could not be checked "
                    + "(gui/island/upgrades.yml may be missing or unreadable).");
        } else {
            int autoSlots = upgradesMenu.contentSlots().size();
            var pinned = upgradesMenu.namedContentSlots();
            List<String> unpinned = new ArrayList<>();
            for (var def : plugin.upgrades().all()) {
                if (!pinned.containsKey(def.key().toLowerCase(Locale.ROOT))) {
                    unpinned.add(def.key());
                }
            }
            if (unpinned.size() > autoSlots) {
                warnings.add("gui/island/upgrades.yml has room for " + autoSlots + " unpinned upgrade(s) but "
                        + unpinned.size() + " track(s) have no 'content: <key>' slot (" + String.join(", ", unpinned)
                        + "); those won't appear in the menu at all. Add a slot with 'content: <key>',"
                        + " or free some mask 0-slots.");
            }
        }

        checkGeneratorTiers(warnings);

        // An unresolved starter schematic silently falls back to the built-in generator; say so at boot.
        String schematic = cfg.getString("island.starter.schematic", "");
        if (!schematic.isBlank() && plugin.schematics() instanceof WorldEditSchematics we
                && !we.exists(schematic)) {
            warnings.add("island.starter.schematic is '" + schematic + "' but no '" + schematic
                    + ".schem' or '" + schematic + ".schematic' exists in the schematics folder; every"
                    + " island is being built by the built-in generator instead. Fix the name, or save"
                    + " one with /is admin schematic save " + schematic + ".");
        }

        boolean papi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        Set<String> reported = new HashSet<>();
        for (UpgradeDef def : plugin.upgrades().all()) {
            for (int t = 1; t <= def.maxTier(); t++) {
                UpgradeTier tier = def.tier(t);
                if (tier == null) {
                    continue;
                }
                for (Cost cost : List.of(tier.cost(), tier.skipCost())) {
                    if (cost.isFree()) {
                        continue;
                    }
                    String currency = cost.currency();
                    String key = currency.toLowerCase(Locale.ROOT);
                    if (!plugin.currency().isDefined(currency)) {
                        if (reported.add("undef:" + key)) {
                            warnings.add("upgrade '" + def.key() + "' uses currency '" + currency
                                    + "', which isn't defined in config.yml under currencies:.");
                        }
                    } else if (plugin.currency().needsPlaceholderApi(currency) && !papi) {
                        if (reported.add("papi:" + key)) {
                            warnings.add("currency '" + currency + "' (used by upgrades) needs PlaceholderAPI for its "
                                    + "balance check, but PlaceholderAPI isn't installed.");
                        }
                    } else if (plugin.currency().isVault(currency) && !plugin.economyReady()) {
                        if (reported.add("vault:" + key)) {
                            warnings.add("upgrade currency '" + currency + "' is a Vault economy, but no economy is "
                                    + "installed; those upgrades can't be purchased. Install Vault + an economy.");
                        }
                    }
                }
            }
        }

        if (warnings.isEmpty()) {
            plugin.getLogger().info("Config check: no issues found.");
            return;
        }
        plugin.getLogger().warning("Config check found " + warnings.size() + " issue(s); RoyalSkyblock still runs:");
        for (String w : warnings) {
            plugin.getLogger().warning("  - " + w);
        }
        plugin.getLogger().warning("(Run /is admin status in-game for a live summary.)");
    }
}
