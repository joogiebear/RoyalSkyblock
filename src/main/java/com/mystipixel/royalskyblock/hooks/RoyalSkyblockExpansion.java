package com.mystipixel.royalskyblock.hooks;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.bank.BankService;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.profile.Profile;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * PlaceholderAPI expansion exposing island data as {@code %royalskyblock_<key>%} placeholders, for use
 * in TAB, scoreboards, chat, holograms, and any other PlaceholderAPI consumer.
 *
 * <p>Reads are cheap: island/profile lookups hit RoyalSkyblock's in-memory caches (falling back to the
 * pooled database once per island, then cached), and the level {@link #rankCache leaderboard rank} is
 * pre-computed on a background timer so a placeholder request never sorts every island inline.
 *
 * <table>
 *   <tr><td>{@code has_island}</td><td>true/false — the player owns/belongs to an island</td></tr>
 *   <tr><td>{@code on_island}</td><td>true/false — standing on ANY island world (gate eco effects to islands)</td></tr>
 *   <tr><td>{@code on_own_island}</td><td>true/false — standing on their OWN island</td></tr>
 *   <tr><td>{@code level}</td><td>island level, whole number with grouping</td></tr>
 *   <tr><td>{@code level_raw}</td><td>island level, raw</td></tr>
 *   <tr><td>{@code rank}</td><td>leaderboard position (1 = highest), or {@code -}</td></tr>
 *   <tr><td>{@code size}</td><td>buildable size as {@code NxN}</td></tr>
 *   <tr><td>{@code radius}</td><td>protection radius</td></tr>
 *   <tr><td>{@code members}</td><td>member count on the active profile</td></tr>
 *   <tr><td>{@code profile}</td><td>active profile name</td></tr>
 *   <tr><td>{@code gamemode}</td><td>active gamemode (Solo / Coop / Ironman)</td></tr>
 *   <tr><td>{@code bank}</td><td>personal bank balance, formatted</td></tr>
 *   <tr><td>{@code bank_raw}</td><td>personal bank balance, raw</td></tr>
 *   <tr><td>{@code upgrade_<key>}</td><td>tier of an upgrade (e.g. {@code upgrade_size})</td></tr>
 * </table>
 */
public final class RoyalSkyblockExpansion extends PlaceholderExpansion {

    private final RoyalSkyblockPlugin plugin;
    /** islandId -> 1-based rank by level; replaced wholesale by {@link #refreshLeaderboard()}. */
    private volatile Map<UUID, Integer> rankCache = Map.of();

    public RoyalSkyblockExpansion(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "royalskyblock";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Mystipixel";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // survive PlaceholderAPI reloads; we manage our own lifecycle
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

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
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
                // Is the player currently standing on ANY island world? (for gating eco effects to islands)
                if (!player.isOnline()) {
                    return "false";
                }
                return String.valueOf(plugin.islands().getIslandByWorld(player.getPlayer().getWorld()) != null);
            }
            case "on_own_island": {
                // Is the player on THEIR OWN island (their active profile's island)?
                if (!player.isOnline()) {
                    return "false";
                }
                Island here = plugin.islands().getIslandByWorld(player.getPlayer().getWorld());
                return String.valueOf(here != null && profileId != null && here.profileId().equals(profileId));
            }
            case "profile":
                return profile != null ? profile.name() : "";
            case "gamemode":
                return profile != null ? title(profile.gamemode().key()) : "";
            case "members":
                return profile != null ? String.valueOf(profile.memberCount()) : "0";
            case "level":
                return island != null ? formatWhole(island.level()) : "0";
            case "level_raw":
                return island != null ? String.valueOf(island.level()) : "0";
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
                return null; // unknown placeholder
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
