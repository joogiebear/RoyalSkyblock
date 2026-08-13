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

    /** Whether anything is cooking anywhere — a cheap guard so idle ticks do no work. */
    public boolean hasAnyPending() {
        return !pending.isEmpty();
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
            // Fired on purchase, not on completion: a timed upgrade finishes on a task tick with
            // nobody necessarily online, and a libreforge trigger needs a player to dispatch to.
            com.mystipixel.royalskyblock.libreforge.IslandTriggers.upgradePurchased(
                    player, def.key(), current + 1);
            return PurchaseResult.COMPLETED;
        }
        PendingUpgrade pu = new PendingUpgrade(island.id(), def.key(), current + 1,
                System.currentTimeMillis() + next.timeSeconds() * 1000L);
        pending.put(pkey(island.id(), def.key()), pu);
        plugin.storage().savePending(pu);
        com.mystipixel.royalskyblock.libreforge.IslandTriggers.upgradePurchased(
                player, def.key(), current + 1);
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

    /**
     * Load every upgrade track, from {@code upgrades/*.yml} and from a legacy {@code upgrades.yml}.
     *
     * <p>One file per track is the layout every eco plugin uses — enchants, talismans, jobs — so
     * someone arriving from EcoItems can drop in {@code upgrades/mythic.yml} and have it load without
     * being told anything. The track's id is the file's name.
     *
     * <p><b>Both sources are read, deliberately.</b> A server that already has a commented
     * {@code upgrades.yml} keeps working untouched: nothing is auto-split, because rewriting YAML
     * through Bukkit would strip every comment in it. New tracks go in the folder, old ones stay put,
     * and an admin can move them across whenever they feel like it. A folder file wins if both define
     * the same id.
     */
    public void reload() {
        upgrades.clear();

        // Legacy monolith first, so folder files take precedence over the same id.
        File legacy = new File(plugin.getDataFolder(), "upgrades.yml");
        if (legacy.isFile()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(legacy);
            for (String key : cfg.getKeys(false)) {
                loadTrack(key, cfg.getConfigurationSection(key));
            }
        }

        File dir = new File(plugin.getDataFolder(), "upgrades");
        if (!dir.isDirectory() && !legacy.isFile()) {
            // Fresh install: ship the folder, not the monolith.
            for (String name : DEFAULT_TRACKS) {
                plugin.saveResource("upgrades/" + name + ".yml", false);
            }
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml") && !name.startsWith("_"));
        if (files != null) {
            for (File f : files) {
                String id = f.getName().substring(0, f.getName().length() - 4);
                loadTrack(id, YamlConfiguration.loadConfiguration(f));
            }
        }
    }

    /** The tracks shipped in the jar, written out on a fresh install. */
    private static final String[] DEFAULT_TRACKS =
            {"size", "guest-limit", "coop-slots", "generator", "minions", "sanctuary"};

    /**
     * Read one track. {@code sec} is the file itself for a folder track, or the named section of the
     * legacy monolith — both are {@link ConfigurationSection}, so one reader serves both layouts.
     */
    private void loadTrack(String key, ConfigurationSection sec) {
        {
            if (sec == null) {
                return;
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
                            parseCost(t, "cost", plugin.getLogger()), parseCost(t, "skip-cost", plugin.getLogger()), parseTime(t.getString("time", "0"))));
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
        // The new tier may carry a libreforge effect chain, and libreforge caches a player's holders —
        // without this the buff would not appear until they next crossed a world boundary.
        refreshHoldersOnIsland(island);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> plugin.storage().saveIsland(island));
    }

    /** Re-provide libreforge holders for everyone currently standing on the island. */
    private void refreshHoldersOnIsland(Island island) {
        org.bukkit.World world = plugin.getServer().getWorld(island.worldName());
        if (world == null) {
            return;                              // island not loaded — holders resolve on next join
        }
        for (org.bukkit.entity.Player player : world.getPlayers()) {
            com.mystipixel.royalskyblock.libreforge.RoyalHolders.refresh(player);
        }
    }

    /** Max concurrent visitors allowed on the island (base + guest-slots upgrade). */
    public int guestLimit(Island island) {
        int base = plugin.conf().getInt("island.base-guest-limit", 3);
        UpgradeDef def = firstWithEffect(UpgradeEffect.GUEST_SLOTS);
        int bonus = def == null ? 0 : (int) def.valueAt(island.upgradeTier(def.key()));
        return base + bonus;
    }

    /** Max coop members allowed on the island's profile (base + coop-slots upgrade). */
    public int coopMemberCap(Island island) {
        int base = plugin.conf().getInt("coop.max-members", 4);
        UpgradeDef def = firstWithEffect(UpgradeEffect.COOP_SLOTS);
        int bonus = def == null ? 0 : (int) def.valueAt(island.upgradeTier(def.key()));
        return base + bonus;
    }

    /** The first upgrade with this effect, or null when no track declares it. */
    public @Nullable UpgradeDef firstWithEffect(UpgradeEffect effect) {
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
        // Size changed — refresh the per-player borders for everyone on the island (see BorderService).
        world.getWorldBorder().setSize(59_999_968.0); // Bukkit's max world-border size
        plugin.borders().applyToWorld(world);
    }

    // ── parsing ────────────────────────────────────────────────────────────────────

    /**
     * Read a tier's cost, in either of two shapes.
     *
     * <p>The compact one — {@code cost: 5000 coins} — keeps a tier down to a handful of lines. Written
     * out as a block, a five-tier track is fifty lines of mostly punctuation, which is hard to scan and
     * tedious to extend. The block form still works and is the right choice when a value needs a
     * comment of its own:
     *
     * <pre>
     *   cost: 5000 coins        cost:
     *                             currency: coins
     *                             amount: 5000
     * </pre>
     *
     * <p>The amount comes first because that is the part being tuned; the currency is usually the same
     * across a whole file. {@code 0} on its own means free.
     */
    // Static and taking a logger rather than reading plugin state, so both config shapes can be
    // tested without standing up a plugin instance.
    static Cost parseCost(ConfigurationSection tier, String key, java.util.logging.Logger log) {
        ConfigurationSection c = tier.getConfigurationSection(key);
        if (c != null) {
            return new Cost(c.getString("currency", "coins"), c.getDouble("amount", 0));
        }
        String compact = tier.getString(key);
        if (compact == null || compact.isBlank()) {
            return new Cost("", 0);
        }
        String[] parts = compact.trim().split("\\s+", 2);
        double amount;
        try {
            amount = Double.parseDouble(parts[0]);
        } catch (NumberFormatException notANumber) {
            log.warning("upgrades.yml: '" + key + ": " + compact
                    + "' is not a cost — expected e.g. '5000 coins'. Treating as free.");
            return new Cost("", 0);
        }
        return new Cost(parts.length > 1 ? parts[1].trim() : "coins", amount);
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
