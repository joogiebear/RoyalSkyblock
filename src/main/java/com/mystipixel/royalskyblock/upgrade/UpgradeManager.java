package com.mystipixel.royalskyblock.upgrade;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.currency.Cost;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.profile.Profile;
import com.mystipixel.royalskyblock.profile.ProfileMember;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads {@code upgrades.yml} and applies upgrade effects to islands. Per-island tiers live on the
 * {@link Island} model (persisted). Purchasing/timers are handled separately; this manager owns the
 * definitions, the current-tier lookups, and turning a tier into a concrete effect.
 */
public final class UpgradeManager {

    /** Outcome of a start/skip purchase attempt. */
    public enum PurchaseResult { STARTED, COMPLETED, MAXED, IN_PROGRESS, NOT_IN_PROGRESS, CANT_AFFORD }

    private final RoyalSkyblockPlugin plugin;
    private final Map<String, UpgradeDef> upgrades = new LinkedHashMap<>();
    private final Map<String, PendingUpgrade> pending = new ConcurrentHashMap<>(); // islandId:key -> pending

    public UpgradeManager(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Load in-progress upgrades from storage (call after storage connects). */
    public void loadPending() {
        pending.clear();
        for (PendingUpgrade p : plugin.storage().getAllPending()) {
            pending.put(pkey(p.islandId(), p.upgradeKey()), p);
        }
    }

    private static String pkey(UUID islandId, String upgradeKey) {
        return islandId + ":" + upgradeKey;
    }

    public @Nullable PendingUpgrade pendingFor(Island island, UpgradeDef def) {
        return pending.get(pkey(island.id(), def.key()));
    }

    // ── purchasing (pay cost + wait, or pay skip cost to finish now) ────────────────

    /** Start the next tier: charge the base cost and begin the timer (or finish instantly). */
    public PurchaseResult start(Player player, Island island, UpgradeDef def) {
        int current = island.upgradeTier(def.key());
        if (current >= def.maxTier()) {
            return PurchaseResult.MAXED;
        }
        if (pendingFor(island, def) != null) {
            return PurchaseResult.IN_PROGRESS;
        }
        UpgradeTier next = def.tier(current + 1);
        if (next == null) {
            return PurchaseResult.MAXED;
        }
        if (!plugin.currency().canAfford(player, next.cost()) || !plugin.currency().charge(player, next.cost())) {
            return PurchaseResult.CANT_AFFORD;
        }
        if (next.isInstant()) {
            setTier(island, def, current + 1);
            return PurchaseResult.COMPLETED;
        }
        PendingUpgrade pu = new PendingUpgrade(island.id(), def.key(), current + 1,
                System.currentTimeMillis() + next.timeSeconds() * 1000L);
        pending.put(pkey(island.id(), def.key()), pu);
        plugin.storage().savePending(pu);
        return PurchaseResult.STARTED;
    }

    /** Pay the skip cost to finish an in-progress upgrade immediately. */
    public PurchaseResult skip(Player player, Island island, UpgradeDef def) {
        PendingUpgrade pu = pendingFor(island, def);
        if (pu == null) {
            return PurchaseResult.NOT_IN_PROGRESS;
        }
        UpgradeTier target = def.tier(pu.targetTier());
        Cost skipCost = target != null ? target.skipCost() : new Cost("", 0);
        if (!plugin.currency().canAfford(player, skipCost) || !plugin.currency().charge(player, skipCost)) {
            return PurchaseResult.CANT_AFFORD;
        }
        completePending(island, def, pu);
        return PurchaseResult.COMPLETED;
    }

    private void completePending(Island island, UpgradeDef def, PendingUpgrade pu) {
        pending.remove(pkey(island.id(), def.key()));
        plugin.storage().deletePending(island.id(), def.key());
        setTier(island, def, pu.targetTier());
        notifyMembers(island, def, pu.targetTier());
    }

    /** Called on a repeating task: finish any upgrades whose timer has elapsed. Main thread. */
    public void tick() {
        if (pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (PendingUpgrade pu : new ArrayList<>(pending.values())) {
            if (!pu.isDone(now)) {
                continue;
            }
            UpgradeDef def = get(pu.upgradeKey());
            Island island = plugin.islands().getIsland(pu.islandId());
            if (def == null || island == null) {
                pending.remove(pkey(pu.islandId(), pu.upgradeKey()));
                plugin.storage().deletePending(pu.islandId(), pu.upgradeKey());
                continue;
            }
            completePending(island, def, pu);
        }
    }

    private void notifyMembers(Island island, UpgradeDef def, int tier) {
        Profile profile = plugin.profiles().getProfile(island.profileId());
        if (profile == null) {
            return;
        }
        for (ProfileMember m : profile.members()) {
            Player online = Bukkit.getPlayer(m.uuid());
            if (online != null) {
                plugin.messages().send(online, "upgrade.completed", "upgrade", def.displayName(), "tier", String.valueOf(tier));
            }
        }
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
