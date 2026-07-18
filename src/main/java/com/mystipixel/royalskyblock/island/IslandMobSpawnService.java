package com.mystipixel.royalskyblock.island;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.hooks.CombatLevelSource;
import com.mystipixel.royalskyblock.hooks.IslandMobProvider;
import com.mystipixel.royalskyblock.profile.Profile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Spawns RoyalSkyblock's night mobs on islands by delegating to an {@link IslandMobProvider}.
 *
 * <p><b>Player-driven, not island-driven.</b> Each pass iterates <em>online players</em> — never a scan
 * of all islands — so the cost is bounded by player count and it only ever touches loaded islands where
 * someone actually is (so it never keeps an island loaded or spawns on an empty one). For each eligible
 * player it makes a few attempts to find a dark, safe, on-island spot near them and spawns
 * {@code <family>_<level>}, where level comes from the reference player's Combat skill (capped).
 *
 * <p>Per-island count is the number of our PDC-tagged mobs alive in that (single-island) world — cheap,
 * because island worlds are tiny — so no counter can drift. Vanilla monster spawning is expected to be
 * off on islands (the {@code spawn_monsters} gamerule in island world-rules), leaving these the only mobs.
 */
public final class IslandMobSpawnService {

    private final RoyalSkyblockPlugin plugin;
    private final IslandMobProvider provider;
    private final CombatLevelSource combat;
    private final NamespacedKey mobKey;
    private final NamespacedKey islandKey;

    private int taskId = -1;

    // cached settings (refreshed on reload)
    private boolean enabled;
    private int scanIntervalTicks;
    private boolean onlyNight;
    private long firstNightGraceMillis;
    private boolean requireLowLight;
    private int maxLight;
    private int spawnRadiusMin;
    private int spawnRadiusMax;
    private int maxMobsPerIsland;
    private int maxSpawnsPerScan;
    private int spawnAttempts;
    private int combatLevelCap;
    private boolean ownerCombatLevel;
    private boolean requireMemberPresent;
    private String mobIdFormat;
    private int centerX;
    private int centerZ;
    private boolean debug;
    private final List<Family> families = new ArrayList<>();
    private int totalWeight;

    private record Family(String name, int weight) {
    }

    public IslandMobSpawnService(RoyalSkyblockPlugin plugin, IslandMobProvider provider, CombatLevelSource combat) {
        this.plugin = plugin;
        this.provider = provider;
        this.combat = combat;
        this.mobKey = new NamespacedKey(plugin, "island_mob");
        this.islandKey = new NamespacedKey(plugin, "island_id");
        reloadSettings();
    }

    public void start() {
        stop();
        if (!enabled) {
            return;
        }
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick,
                scanIntervalTicks, Math.max(20, scanIntervalTicks)).getTaskId();
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    public void reloadSettings() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("island-mobs");
        if (c == null) {
            c = plugin.getConfig().createSection("island-mobs");
        }
        enabled = c.getBoolean("enabled", false);
        scanIntervalTicks = Math.max(20, c.getInt("scan-interval-ticks", 200));
        onlyNight = c.getBoolean("only-night", true);
        firstNightGraceMillis = Math.max(0, c.getLong("first-night-grace-ticks", 24000)) * 50L;
        requireLowLight = c.getBoolean("require-low-light", true);
        maxLight = c.getInt("max-light-level", 7);
        spawnRadiusMin = Math.max(1, c.getInt("spawn-radius-min", 12));
        spawnRadiusMax = Math.max(spawnRadiusMin, c.getInt("spawn-radius-max", 28));
        maxMobsPerIsland = Math.max(0, c.getInt("max-mobs-per-island", 12));
        maxSpawnsPerScan = Math.max(1, c.getInt("max-spawns-per-scan-per-island", 3));
        spawnAttempts = Math.max(1, c.getInt("spawn-attempts", 6));
        combatLevelCap = Math.max(1, c.getInt("combat-level-cap", 15));
        ownerCombatLevel = c.getBoolean("owner-combat-level", false);
        requireMemberPresent = c.getBoolean("require-member-present", true);
        mobIdFormat = c.getString("mob-id-format", "private_island_%family%_%level%");
        debug = c.getBoolean("debug", false);

        ConfigurationSection paste = plugin.getConfig().getConfigurationSection("island.paste");
        centerX = paste != null ? paste.getInt("x", 0) : 0;
        centerZ = paste != null ? paste.getInt("z", 0) : 0;

        families.clear();
        totalWeight = 0;
        ConfigurationSection fam = c.getConfigurationSection("families");
        if (fam != null) {
            for (String key : fam.getKeys(false)) {
                ConfigurationSection f = fam.getConfigurationSection(key);
                if (f == null || !f.getBoolean("enabled", true)) {
                    continue;
                }
                int weight = Math.max(0, f.getInt("weight", 1));
                if (weight > 0) {
                    families.add(new Family(key.toLowerCase(Locale.ROOT), weight));
                    totalWeight += weight;
                }
            }
        }
    }

    // ── the pass ─────────────────────────────────────────────────────────────────

    private void tick() {
        if (!enabled || provider == null || !provider.available() || families.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();

        // Group eligible players by island (so we count/cap once per island, not per player).
        Map<UUID, List<Player>> byIsland = new HashMap<>();
        Map<UUID, Island> islands = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Island island = plugin.islands().getIslandByWorld(p.getWorld());
            if (island == null || !island.isEnabled(IslandSetting.MOB_SPAWNS)) {
                continue;
            }
            if (onlyNight && !isNight(p.getWorld())) {
                continue;
            }
            if (now - island.createdAt() < firstNightGraceMillis) {
                continue;
            }
            if (requireMemberPresent && !isMember(island, p.getUniqueId())) {
                continue;
            }
            byIsland.computeIfAbsent(island.id(), k -> new ArrayList<>()).add(p);
            islands.put(island.id(), island);
        }

        for (Map.Entry<UUID, List<Player>> entry : byIsland.entrySet()) {
            Island island = islands.get(entry.getKey());
            List<Player> present = entry.getValue();
            World world = present.get(0).getWorld();
            int budget = Math.min(maxMobsPerIsland - countIslandMobs(world), maxSpawnsPerScan);
            int guard = present.size() * spawnAttempts;
            int i = 0;
            while (budget > 0 && guard-- > 0) {
                Player near = present.get(i++ % present.size());
                Location loc = findSpawnLocation(near, island);
                if (loc == null) {
                    continue;
                }
                OfflinePlayer ref = ownerCombatLevel ? owner(island) : near;
                int level = Math.max(1, Math.min(combatLevelCap, combat.levelOf(ref)));
                String mobId = mobIdFormat
                        .replace("%family%", pickFamily())
                        .replace("%level%", Integer.toString(level));
                Entity spawned = provider.spawn(mobId, loc);
                if (spawned != null) {
                    tag(spawned, island.id());
                    budget--;
                    if (debug) {
                        plugin.getLogger().info("[island-mobs] spawned " + mobId + " on island " + island.id());
                    }
                } else if (debug) {
                    plugin.getLogger().info("[island-mobs] provider returned no entity for '" + mobId + "'");
                }
            }
        }
    }

    // ── spawn location ─────────────────────────────────────────────────────────────

    private Location findSpawnLocation(Player player, Island island) {
        World world = player.getWorld();
        int radius = island.radius();
        for (int attempt = 0; attempt < spawnAttempts; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            double dist = spawnRadiusMin + ThreadLocalRandom.current().nextDouble(spawnRadiusMax - spawnRadiusMin + 1);
            int x = player.getLocation().getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = player.getLocation().getBlockZ() + (int) Math.round(Math.sin(angle) * dist);

            if (Math.abs(x - centerX) > radius || Math.abs(z - centerZ) > radius) {
                continue;                        // outside the island's square border
            }
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            int y = world.getHighestBlockYAt(x, z);
            Block ground = world.getBlockAt(x, y, z);
            Block feet = world.getBlockAt(x, y + 1, z);
            Block head = world.getBlockAt(x, y + 2, z);
            if (!ground.getType().isSolid() || ground.isLiquid()) {
                continue;
            }
            if (!feet.isPassable() || !head.isPassable() || feet.isLiquid()) {
                continue;
            }
            if (requireLowLight && feet.getLightLevel() > maxLight) {
                continue;
            }
            Location loc = feet.getLocation().add(0.5, 0, 0.5);
            if (loc.distanceSquared(player.getLocation()) < (double) spawnRadiusMin * spawnRadiusMin) {
                continue;                        // never spawn right on top of the player
            }
            return loc;
        }
        return null;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private boolean isNight(World world) {
        long time = world.getTime() % 24000L;
        return time >= 13000L && time <= 23000L;
    }

    private int countIslandMobs(World world) {
        int count = 0;
        for (LivingEntity e : world.getLivingEntities()) {
            if (e.getPersistentDataContainer().has(mobKey, PersistentDataType.BYTE)) {
                count++;
            }
        }
        return count;
    }

    private void tag(Entity entity, UUID islandId) {
        entity.getPersistentDataContainer().set(mobKey, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(islandKey, PersistentDataType.STRING, islandId.toString());
    }

    private boolean isMember(Island island, UUID uuid) {
        Profile profile = plugin.profiles().getProfile(island.profileId());
        return profile != null && (uuid.equals(profile.owner()) || profile.isMember(uuid));
    }

    private OfflinePlayer owner(Island island) {
        Profile profile = plugin.profiles().getProfile(island.profileId());
        return profile != null ? Bukkit.getOfflinePlayer(profile.owner()) : null;
    }

    private String pickFamily() {
        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        int acc = 0;
        for (Family f : families) {
            acc += f.weight();
            if (roll < acc) {
                return f.name();
            }
        }
        return families.get(0).name();
    }

    // ── admin test spawn (used by /is admin mobspawn test) ──────────────────────────

    /** Force-spawn a family/level near the player, ignoring night/grace/cap. Returns a status string. */
    public String testSpawn(Player player, String family, int level) {
        if (provider == null || !provider.available()) {
            return "provider '" + (provider == null ? "none" : provider.id()) + "' is unavailable";
        }
        Island island = plugin.islands().getIslandByWorld(player.getWorld());
        if (island == null) {
            return "you must be on an island world";
        }
        Location loc = findSpawnLocation(player, island);
        if (loc == null) {
            loc = player.getLocation();          // for a forced test, fall back to the player's spot
        }
        int lvl = Math.max(1, Math.min(combatLevelCap, level));
        String mobId = mobIdFormat.replace("%family%", family.toLowerCase(Locale.ROOT)).replace("%level%", Integer.toString(lvl));
        Entity spawned = provider.spawn(mobId, loc);
        if (spawned == null) {
            return "provider had no mob '" + mobId + "'";
        }
        tag(spawned, island.id());
        return "spawned " + mobId;
    }

    public boolean enabled() {
        return enabled;
    }

    public String providerId() {
        return provider == null ? "none" : provider.id();
    }

    public boolean providerAvailable() {
        return provider != null && provider.available();
    }

    public int familyCount() {
        return families.size();
    }
}
