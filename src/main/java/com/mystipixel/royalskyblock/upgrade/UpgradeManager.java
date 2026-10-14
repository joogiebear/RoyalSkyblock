package com.mystipixel.royalskyblock.upgrade;

import com.mystipixel.royalcommon.storage.DbExecutor;
import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.currency.Cost;
import com.mystipixel.royalskyblock.data.Storage;
import com.mystipixel.royalskyblock.data.StorageException;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads upgrade tracks and applies upgrade effects to islands. Per-island tiers live on the
 * {@link Island} model; this manager owns the definitions, purchases and timers, and turning a tier into
 * a concrete effect.
 */
public final class UpgradeManager {

    /** Outcome of a start/skip purchase attempt. */
    public enum PurchaseResult {
        STARTED, COMPLETED, MAXED, IN_PROGRESS, NOT_IN_PROGRESS, CANT_AFFORD, TIER_CHANGED, FAILED
    }

    // An instant tier is reserved like a timed one while the purchase is charged. Its row must not look
    // finished to a hosting server's tick in that window; if this server dies there, it completes after this.
    private static final long INSTANT_HOLD_MS = 5 * 60_000L;
    // how often an open upgrade menu rereads the timers of its island
    private static final long PENDING_REFRESH_MS = 5_000L;

    private final RoyalSkyblockPlugin plugin;
    private final Map<String, UpgradeDef> upgrades = new LinkedHashMap<>();
    // islandId:key to pending: the countdowns menus draw, not the source of truth (that is the database)
    private final Map<String, PendingUpgrade> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingFetchedAt = new ConcurrentHashMap<>();
    // set and cleared on the main thread only
    private volatile boolean ticking;

    public UpgradeManager(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    private static String pkey(UUID islandId, String upgradeKey) {
        return islandId + ":" + upgradeKey;
    }

    public @Nullable PendingUpgrade pendingFor(Island island, UpgradeDef def) {
        return pending.get(pkey(island.id(), def.key()));
    }

    /** Whether any countdown is known here: a cheap guard so idle menu ticks do no work. */
    public boolean hasAnyPending() {
        return !pending.isEmpty();
    }

    /** Rereads an island's timers when the copy here is older than a few seconds. Main thread. */
    public void refreshPendingIfStale(UUID islandId) {
        Long fetched = pendingFetchedAt.get(islandId);
        if (fetched == null || System.currentTimeMillis() - fetched >= PENDING_REFRESH_MS) {
            refreshPending(islandId);
        }
    }

    /** Rereads an island's timers, e.g. after it loaded here or a purchase on another server. Main thread. */
    public CompletableFuture<Void> refreshPending(UUID islandId) {
        pendingFetchedAt.put(islandId, System.currentTimeMillis());
        return onDb("read upgrade timers", () -> plugin.storage().getPending(islandId)).thenAccept(rows -> {
            pending.values().removeIf(p -> p.islandId().equals(islandId));
            for (PendingUpgrade p : rows) {
                pending.put(pkey(p.islandId(), p.upgradeKey()), p);
            }
        });
    }

    /** Called when an island world loads here: its timers now finish on this server. */
    public void onIslandLoaded(Island island) {
        refreshPending(island.id());
    }

    // runs work on the database thread and hands the result back on the main thread; inline at shutdown
    private <T> CompletableFuture<T> onDb(String name, Callable<T> work) {
        DbExecutor db = plugin.db();
        if (db == null) {
            try {
                return CompletableFuture.completedFuture(work.call());
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        // handle, not thenApply: a failed future would skip a thenApplyAsync and run its dependents here, on
        // the database thread
        return db.submit(name, work).handleAsync((result, error) -> {
            if (error != null) {
                throw error instanceof CompletionException completion ? completion : new CompletionException(error);
            }
            return result;
        }, db.mainThread());
    }

    private static CompletableFuture<PurchaseResult> done(PurchaseResult result) {
        return CompletableFuture.completedFuture(result);
    }

    /**
     * Start the next tier. The tier is reserved in the database first (so a purchase on another server
     * can't sell it again), then the base cost is charged; an instant tier is applied at once. The future
     * completes on the main thread.
     */
    public CompletableFuture<PurchaseResult> start(Player player, Island island, UpgradeDef def) {
        int current = island.upgradeTier(def.key());
        UpgradeTier next = current >= def.maxTier() ? null : def.tier(current + 1);
        if (next == null) {
            return done(PurchaseResult.MAXED);
        }
        if (pendingFor(island, def) != null) {
            return done(PurchaseResult.IN_PROGRESS);
        }
        if (!plugin.currency().canAfford(player, next.cost())) {
            return done(PurchaseResult.CANT_AFFORD);
        }
        long now = System.currentTimeMillis();
        PendingUpgrade reservation = new PendingUpgrade(island.id(), def.key(), current + 1,
                now + (next.isInstant() ? INSTANT_HOLD_MS : next.timeSeconds() * 1000L));
        return onDb("reserve upgrade", () -> plugin.storage().reservePending(reservation, current))
                .thenCompose(reserved -> switch (reserved) {
                    case RESERVED -> chargeReserved(player, island, def, next, reservation);
                    // answered once the countdown is known here, so the message shows the time left
                    case IN_PROGRESS -> refreshPending(island.id())
                            .handle((ignored, error) -> PurchaseResult.IN_PROGRESS);
                    case TIER_CHANGED -> done(PurchaseResult.TIER_CHANGED);
                    case FAILED -> done(PurchaseResult.FAILED);
                });
    }

    // main thread, with the tier reserved: charge, then start the timer or finish an instant tier
    private CompletableFuture<PurchaseResult> chargeReserved(Player player, Island island, UpgradeDef def,
                                                             UpgradeTier next, PendingUpgrade reservation) {
        if (!player.isOnline() || !plugin.currency().canAfford(player, next.cost())
                || !plugin.currency().charge(player, next.cost())) {
            plugin.writeAsync(() -> plugin.storage().deletePending(island.id(), def.key()));
            return done(PurchaseResult.CANT_AFFORD);
        }
        if (!next.isInstant()) {
            pending.put(pkey(island.id(), def.key()), reservation);
            // fired on purchase, not completion: a timed upgrade can finish with nobody online, and a trigger
            // needs a player
            com.mystipixel.royalskyblock.libreforge.IslandTriggers.upgradePurchased(
                    player, def.key(), reservation.targetTier());
            return done(PurchaseResult.STARTED);
        }
        Integer radius = radiusFor(def, reservation.targetTier());
        return onDb("finish upgrade", () -> plugin.storage().completePending(reservation, radius))
                .thenApply(completed -> {
                    if (!completed) {
                        plugin.currency().refund(player, next.cost());
                        return PurchaseResult.FAILED;
                    }
                    applyTier(island, def, reservation.targetTier());
                    com.mystipixel.royalskyblock.libreforge.IslandTriggers.upgradePurchased(
                            player, def.key(), reservation.targetTier());
                    return PurchaseResult.COMPLETED;
                });
    }

    /** Pay the skip cost to finish an in-progress upgrade now. The future completes on the main thread. */
    public CompletableFuture<PurchaseResult> skip(Player player, Island island, UpgradeDef def) {
        UUID islandId = island.id();
        return onDb("read upgrade timer", () -> plugin.storage().getPending(islandId)).thenCompose(rows -> {
            PendingUpgrade pu = rows.stream().filter(p -> p.upgradeKey().equals(def.key())).findFirst().orElse(null);
            if (pu == null) {
                pending.remove(pkey(islandId, def.key()));
                return done(PurchaseResult.NOT_IN_PROGRESS);
            }
            UpgradeTier target = def.tier(pu.targetTier());
            Cost skipCost = target != null ? target.skipCost() : new Cost("", 0);
            if (!player.isOnline() || !plugin.currency().canAfford(player, skipCost)
                    || !plugin.currency().charge(player, skipCost)) {
                return done(PurchaseResult.CANT_AFFORD);
            }
            Integer radius = radiusFor(def, pu.targetTier());
            return onDb("finish upgrade", () -> plugin.storage().completePending(pu, radius)).thenApply(completed -> {
                if (!completed) {
                    // the timer ran out and the hosting server finished it meanwhile
                    plugin.currency().refund(player, skipCost);
                    refreshPending(islandId);
                    return PurchaseResult.NOT_IN_PROGRESS;
                }
                finished(pu);
                return PurchaseResult.COMPLETED;
            });
        });
    }

    // main thread: an upgrade finished in the database; apply it to the cached island and tell its members
    private void finished(PendingUpgrade pu) {
        pending.remove(pkey(pu.islandId(), pu.upgradeKey()));
        UpgradeDef def = get(pu.upgradeKey());
        Island island;
        try {
            island = plugin.islands().getIsland(pu.islandId());
        } catch (StorageException e) {
            // the tier is saved; the version poll brings it into the cache once the store answers
            plugin.getLogger().warning("Finished upgrade " + pu.upgradeKey() + " on " + pu.islandId()
                    + " could not be applied here yet: " + e.getMessage());
            return;
        }
        if (def == null || island == null) {
            return;
        }
        applyTier(island, def, pu.targetTier());
        notifyMembers(island, def, pu.targetTier());
    }

    /**
     * Called on a repeating task: finishes the elapsed upgrades of the islands loaded here, on the database
     * thread. An island loaded nowhere keeps its row until a server loads it. Main thread.
     */
    public void tick() {
        long now = System.currentTimeMillis();
        // countdowns well past their end were finished somewhere (or will be when their island loads)
        pending.values().removeIf(p -> p.completeAt() < now - 10_000L);
        Set<UUID> hosted = plugin.islands().hostedIslandIds();
        if (ticking || hosted.isEmpty()) {
            return;
        }
        ticking = true;
        Map<String, UpgradeDef> defs = Map.copyOf(upgrades);
        Storage storage = plugin.storage();
        onDb("finish upgrades", () -> claimDue(storage, now, hosted, defs)).whenComplete((completed, error) -> {
            ticking = false;
            if (error != null) {
                plugin.getLogger().warning("Could not finish elapsed upgrades: " + error.getMessage());
                return;
            }
            completed.forEach(this::finished);
        });
    }

    /**
     * Database thread: finishes every elapsed upgrade of the given islands. Only the server that removes a
     * row applies it, so an upgrade finishes (and notifies) once even when two servers race.
     *
     * @return the upgrades this call finished
     */
    static List<PendingUpgrade> claimDue(Storage storage, long now, Collection<UUID> hosted,
                                        Map<String, UpgradeDef> defs) {
        List<PendingUpgrade> completed = new ArrayList<>();
        for (PendingUpgrade p : storage.duePending(now, hosted)) {
            UpgradeDef def = defs.get(p.upgradeKey());
            if (def == null) {
                // the track was removed from the config: drop the timer, there is no tier to apply
                storage.deletePending(p.islandId(), p.upgradeKey());
                continue;
            }
            if (storage.completePending(p, radiusFor(def, p.targetTier()))) {
                completed.add(p);
            }
        }
        return completed;
    }

    static @Nullable Integer radiusFor(UpgradeDef def, int tier) {
        if (def.effect() != UpgradeEffect.RADIUS) {
            return null;
        }
        double value = def.valueAt(tier);
        return value > 0 ? (int) value : null;
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
     * Load every upgrade track from {@code upgrades/*.yml} (file name is the id) and from a legacy
     * {@code upgrades.yml}, which is never auto-split. A folder file wins if both define the same id.
     */
    public void reload() {
        upgrades.clear();

        // legacy monolith first, so folder files take precedence over the same id
        File legacy = new File(plugin.getDataFolder(), "upgrades.yml");
        if (legacy.isFile()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(legacy);
            for (String key : cfg.getKeys(false)) {
                loadTrack(key, cfg.getConfigurationSection(key));
            }
        }

        File dir = new File(plugin.getDataFolder(), "upgrades");
        if (!dir.isDirectory() && !legacy.isFile()) {
            // fresh install: ship the folder, not the monolith
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

    // the tracks shipped in the jar, written on a fresh install
    private static final String[] DEFAULT_TRACKS =
            {"size", "guest-limit", "coop-slots", "generator", "minions", "sanctuary"};

    // sec is the file itself for a folder track, or the named section of the legacy monolith
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
                            parseCost(t, "cost", plugin.getLogger()), parseCost(t, "skip-cost", plugin.getLogger()),
                            parseTime(t.getString("time", "0")), List.copyOf(t.getStringList("unlock-commands"))));
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

    /** Set an island's tier for an upgrade (an admin command), persist it, and apply the effect. */
    public void setTier(Island island, UpgradeDef def, int tier) {
        applyTier(island, def, tier);
        Integer radius = radiusFor(def, tier);
        plugin.islands().writeIsland(island,
                () -> plugin.storage().updateIslandUpgradeTier(island.id(), def.key(), tier, radius));
    }

    // a tier already saved: set it on the cached island and run its effects here
    private void applyTier(Island island, UpgradeDef def, int tier) {
        island.setUpgradeTier(def.key(), Math.max(tier, island.upgradeTier(def.key())));
        Integer radius = radiusFor(def, tier);
        if (radius != null) {
            island.setRadius(radius);
            reapplyBorder(island);
        }
        // the new tier may carry a libreforge effect chain, and libreforge caches a player's holders
        refreshHoldersOnIsland(island);
        runUnlockCommands(island, def, tier);
    }

    // The owner's real player name, or "" if unresolved. Never the profile name: that's a label the
    // player chose, and "lp user %owner% ..." would target a non-existent account.
    private String ownerName(Profile profile) {
        String name = Bukkit.getOfflinePlayer(profile.owner()).getName();
        return name == null ? "" : name;
    }

    /**
     * Re-run the unlock commands of every tier the island has reached, for its current owner, after an
     * ownership transfer (grants went to the old owner by name). Nothing here revokes the old owner's.
     */
    public void replayUnlockCommands(Island island) {
        for (UpgradeDef def : all()) {
            for (int tier = 1; tier <= island.upgradeTier(def.key()); tier++) {
                runUnlockCommands(island, def, tier);
            }
        }
    }

    // Run a tier's unlock-commands: the hook for an effect that belongs to another plugin. Called from
    // setTier so every path that applies a tier runs them (purchase, timer, admin), so they must be idempotent.
    private void runUnlockCommands(Island island, UpgradeDef def, int tier) {
        UpgradeTier reached = def.tier(tier);
        if (reached == null || reached.unlockCommands().isEmpty()) {
            return;
        }
        Profile profile = plugin.profiles().getProfile(island.profileId());
        String owner = profile == null ? "" : ownerName(profile);
        if (owner.isEmpty()) {
            plugin.getLogger().warning("Upgrade '" + def.key() + "' reached tier " + tier + " but its "
                    + "unlock-commands were skipped: the island's owner could not be resolved.");
            return;
        }
        for (String command : reached.unlockCommands()) {
            String parsed = command.replace("%owner%", owner)
                    .replace("%tier%", String.valueOf(tier))
                    .replace("%value%", String.valueOf((int) def.valueAt(tier)))
                    // the island's world, so a per-player grant can be scoped to it and doesn't leak across the owner's
                    // other profiles
                    .replace("%world%", island.worldName())
                    .replace("%island%", island.id().toString());
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
            } catch (Throwable t) {
                plugin.getLogger().warning("Upgrade unlock command failed ('" + parsed + "'): " + t.getMessage());
            }
        }
    }

    // re-provide libreforge holders for everyone standing on the island
    private void refreshHoldersOnIsland(Island island) {
        org.bukkit.World world = plugin.getServer().getWorld(island.worldName());
        if (world == null) {
            return;                              // island not loaded: holders resolve on next join
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

    /** Re-apply the per-player borders on a loaded island, e.g. after its radius changed. */
    public void reapplyBorder(Island island) {
        World world = plugin.getServer().getWorld(island.worldName());
        if (world == null) {
            return;
        }
        // size changed: refresh the per-player borders for everyone on the island (see BorderService)
        world.getWorldBorder().setSize(59_999_968.0); // Bukkit's max world-border size
        plugin.borders().applyToWorld(world);
    }

    // A tier's cost, compact or as a block; 0 alone means free:
    //   cost: 5000 coins        cost:
    //                             currency: coins
    //                             amount: 5000
    // static and taking a logger so both config shapes can be tested without a plugin instance
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
                    + "' is not a cost: expected e.g. '5000 coins'. Treating as free.");
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
