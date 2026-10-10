package com.mystipixel.royalskyblock.hooks;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.bank.BankService;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.profile.Profile;
import org.bukkit.OfflinePlayer;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves RoyalSkyblock's placeholders for both front ends: {@link RoyalSkyblockExpansion} for
 * PlaceholderAPI, and eco's registry for effect chains, menus and item lore (which work without PAPI).
 * Reads hit in-memory caches; the leaderboard rank is precomputed on a background timer.
 *
 * <table>
 *   <tr><td>{@code has_island}</td><td>true/false: the player owns/belongs to an island</td></tr>
 *   <tr><td>{@code on_island}</td><td>true/false: standing on ANY island world</td></tr>
 *   <tr><td>{@code on_own_island}</td><td>true/false: standing on their OWN island</td></tr>
 *   <tr><td>{@code level}</td><td>the player's OWN island level, whole number with grouping</td></tr>
 *   <tr><td>{@code level_raw}</td><td>the player's OWN island level, raw</td></tr>
 *   <tr><td>{@code island_level}</td><td>level of the island the player is IN (0 off-island)</td></tr>
 *   <tr><td>{@code island_level_raw}</td><td>as {@code island_level}, raw</td></tr>
 *   <tr><td>{@code rank}</td><td>leaderboard position (1 = highest), or {@code -}</td></tr>
 *   <tr><td>{@code size}</td><td>buildable size as {@code NxN}</td></tr>
 *   <tr><td>{@code radius}</td><td>protection radius</td></tr>
 *   <tr><td>{@code members}</td><td>member count on the active profile</td></tr>
 *   <tr><td>{@code profile}</td><td>active profile name</td></tr>
 *   <tr><td>{@code profile_id}</td><td>active profile id (stable; used for per-profile scoping)</td></tr>
 *   <tr><td>{@code gamemode}</td><td>active gamemode (Solo / Coop / Ironman)</td></tr>
 *   <tr><td>{@code bank}</td><td>personal bank balance, formatted</td></tr>
 *   <tr><td>{@code bank_raw}</td><td>personal bank balance, raw</td></tr>
 *   <tr><td>{@code upgrade_<key>}</td><td>tier of an upgrade (e.g. {@code upgrade_size})</td></tr>
 * </table>
 */
public final class IslandPlaceholders {

    /**
     * Every fixed placeholder id, for front ends that must enumerate (eco registers one object per id).
     * {@code upgrade_<key>} is a prefix, so eco callers use {@link #resolve} for those.
     */
    public static final List<String> IDS = List.of(
            "has_island", "on_island", "on_own_island",
            "level", "level_raw", "island_level", "island_level_raw",
            "rank", "size", "radius", "members",
            "profile", "profile_id", "gamemode",
            "bank", "bank_raw");

    private final RoyalSkyblockPlugin plugin;

    // islandId to 1-based rank by level; replaced wholesale by refreshLeaderboard()
    private volatile Map<UUID, Integer> rankCache = Map.of();

    public IslandPlaceholders(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    /** Recompute the level leaderboard. Runs off the main thread on a timer (DB read + sort). */
    public void refreshLeaderboard() {
        List<Island> all = plugin.storage().getAllIslands();
        all.sort((a, b) -> Double.compare(b.level(), a.level()));
        Map<UUID, Integer> ranks = new HashMap<>(all.size() * 2);
        int rank = 1;
        for (Island island : all) {
            ranks.put(island.id(), rank++);
        }
        this.rankCache = ranks;
    }

    /** Resolve one placeholder, or null when the id is not one of ours. */
    public String resolve(OfflinePlayer player, String params) {
        if (player == null || params == null) {
            return "";
        }
        UUID uuid = player.getUniqueId();
        Profile profile = player.isOnline()
                ? plugin.profiles().getActiveProfile(player.getPlayer())
                : null;
        UUID profileId = profile != null ? profile.id() : plugin.profiles().getActiveProfileId(uuid);
        Island island = profileId != null ? plugin.islands().getIslandByProfile(profileId) : null;

        switch (params.toLowerCase(Locale.ROOT)) {
            case "has_island":
                return String.valueOf(island != null);
            case "on_island": {
                if (!player.isOnline()) {
                    return "false";
                }
                return String.valueOf(plugin.islands().getIslandByWorld(player.getPlayer().getWorld()) != null);
            }
            case "on_own_island": {
                if (!player.isOnline()) {
                    return "false";
                }
                Island here = plugin.islands().getIslandByWorld(player.getPlayer().getWorld());
                return String.valueOf(here != null && profileId != null && here.profileId().equals(profileId));
            }
            case "profile":
                return profile != null ? profile.name() : "";
            case "profile_id":
                // stable per-profile id (RoyalWardrobe scopes wardrobes by it)
                return profileId != null ? profileId.toString() : "";
            case "gamemode":
                return profile != null ? title(profile.gamemode().key()) : "";
            case "members":
                return profile != null ? String.valueOf(profile.memberCount()) : "0";
            case "level":
                return island != null ? formatWhole(island.level()) : "0";
            case "level_raw":
                return island != null ? String.valueOf(island.level()) : "0";
            case "island_level":
            case "island_level_raw": {
                // Level of the island the player is standing in, not their own: what mob-spawn and effect scaling
                // want. 0 off any island.
                if (!player.isOnline()) {
                    return "0";
                }
                Island world = plugin.islands().getIslandByWorld(player.getPlayer().getWorld());
                if (world == null) {
                    return "0";
                }
                return params.endsWith("_raw") ? String.valueOf(world.level()) : formatWhole(world.level());
            }
            case "rank": {
                if (island == null) {
                    return "-";
                }
                Integer r = rankCache.get(island.id());
                return r != null ? String.valueOf(r) : "-";
            }
            case "size": {
                if (island == null) {
                    return "-";
                }
                int n = island.radius() * 2 + 1;
                return n + "x" + n;
            }
            case "radius":
                return island != null ? String.valueOf(island.radius()) : "0";
            case "bank":
                return plugin.bank().money(bankBalance(profileId, uuid));
            case "bank_raw":
                return String.valueOf(bankBalance(profileId, uuid));
            default:
                if (params.regionMatches(true, 0, "upgrade_", 0, 8) && island != null) {
                    return String.valueOf(island.upgradeTier(params.substring(8)));
                }
                return null;
        }
    }

    private double bankBalance(UUID profileId, UUID uuid) {
        if (profileId == null || !plugin.bank().available()) {
            return 0.0;
        }
        return plugin.bank().balance(BankService.personalId(profileId, uuid));
    }

    private static String formatWhole(double value) {
        return NumberFormat.getIntegerInstance(Locale.US).format(Math.floor(value));
    }

    private static String title(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        return key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1).toLowerCase(Locale.ROOT);
    }
}
