package com.mystipixel.royalskyblock.data;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.bank.BankAccount;
import com.mystipixel.royalskyblock.bank.BankTxn;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.island.IslandRole;
import com.mystipixel.royalskyblock.profile.Gamemode;
import com.mystipixel.royalskyblock.profile.Profile;
import com.mystipixel.royalskyblock.profile.ProfileData;
import com.mystipixel.royalskyblock.profile.ProfileMember;
import com.mystipixel.royalskyblock.upgrade.PendingUpgrade;
import com.willfp.eco.core.config.Configs;
import com.willfp.eco.core.config.interfaces.Config;
import com.willfp.eco.core.data.PlayerProfile;
import com.willfp.eco.core.data.ServerProfile;
import com.willfp.eco.core.data.keys.PersistentDataKey;
import com.willfp.eco.core.data.keys.PersistentDataKeyType;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@link Storage} on eco's own data layer, so RoyalSkyblock stops configuring a database.
 *
 * <p>Every other plugin in the suite persists through eco: one {@code data-handler} in eco's config
 * (yaml / MySQL / MariaDB / MongoDB) serves all of them, and none of them ship a {@code storage}
 * section. Selecting {@code storage.type: ECO} puts this plugin in the same position — islands,
 * profiles, rosters and banks land wherever eco already keeps EcoSkills levels and EcoBits balances,
 * and changing backend becomes eco's job rather than ours. eco's own {@code perform-data-migration}
 * then moves this data with everything else.
 *
 * <h2>How a table becomes a key</h2>
 *
 * eco stores values as typed {@link PersistentDataKey}s against a UUID — {@link PlayerProfile#load}
 * accepts <em>any</em> UUID, not just a real player's, so a UUID works as a primary key and each row
 * becomes one {@link PersistentDataKeyType#CONFIG} value. Islands and profiles already have UUIDs;
 * everything else gets a deterministic name-based one from {@link #derived}.
 *
 * <h2>Three things that are easy to get wrong</h2>
 *
 * <p><b>Player-scoped rows must not sit on the player's own UUID.</b> It is the obvious choice and it
 * silently corrupts profile switching: {@link com.mystipixel.royalskyblock.hooks.EcoProfileBridge}
 * copies <em>every</em> non-local key off the player's live profile into a per-profile shadow on each
 * switch, so {@code active_profile} — written during that very switch — would be swapped along with
 * their skill levels. Player-scoped keys therefore live on {@code rsb-player:<uuid>}, a UUID the
 * bridge never visits. The bridge also skips this plugin's namespace now, but the separation is what
 * actually makes it safe.
 *
 * <p><b>Keys are constructed here, not statically.</b> A {@link PersistentDataKey} registers itself
 * with eco in its constructor and is then visited by every full-key iteration in the suite. Building
 * them in the constructor means a server on SQL storage never registers them at all.
 *
 * <p><b>Durability is eco's, not SQLite's.</b> Writes land in eco's write buffer and reach the
 * handler on its save interval rather than at once, so a hard crash loses whatever the buffer held —
 * the same exposure every eco plugin has for levels and balances. A clean shutdown is safe by
 * construction: eco disables after its dependents, so its final save runs after our last write.
 *
 * <h2>Multi-node</h2>
 *
 * <p>Non-local keys go to eco's shared handler and local ones to a per-node file — eco uses exactly
 * this split for its own network-wide {@code server_id} against a per-node {@code local_server_id}.
 * Every key here is non-local, so on MySQL/MongoDB all nodes read and write one set of islands.
 *
 * <p>What that does <em>not</em> buy is cache coherency. eco pins a loaded profile in memory and only
 * drops it on player login/quit, so a UUID that is not a player stays cached for the node's whole
 * uptime and a write on one node is invisible to another until it restarts. Island blocks are already
 * single-node (ASP allows a slime world on one server at a time), so the island's own data has one
 * writer; what can read stale is the shared view — the leaderboard and the visit browser.
 *
 * @see Storage for the shape this implements and why {@link #getAllIslands()} is the hard part
 */
public final class EcoStorage implements Storage {

    /** Bumped only if a stored layout changes incompatibly; also marks a row as present at all. */
    private static final int SCHEMA = 1;

    /** Ledger entries kept per account. The UI asks for the newest few; the rest are dropped. */
    private static final int MAX_TXNS = 100;

    private final RoyalSkyblockPlugin plugin;

    // Rows. CONFIG for anything with more than one field, so a row is one read and one write.
    private final PersistentDataKey<Config> islandKey;
    private final PersistentDataKey<Config> profileKey;
    private final PersistentDataKey<Config> pendingKey;
    private final PersistentDataKey<Config> profileDataKey;
    private final PersistentDataKey<Config> bankKey;
    private final PersistentDataKey<List<String>> bankTxnsKey;

    // Lookups that SQL answered with an index. Each lives on the UUID it is looked up by.
    private final PersistentDataKey<String> activeProfileKey;   // on rsb-player:<player>
    private final PersistentDataKey<String> islandOfKey;        // on the profile's UUID
    private final PersistentDataKey<List<String>> ownedKey;     // on rsb-player:<player>
    private final PersistentDataKey<List<String>> memberOfKey;  // on rsb-player:<player>

    // Full scans. On the server profile (the nil UUID) so every node shares one list.
    private final PersistentDataKey<List<String>> islandIndexKey;
    private final PersistentDataKey<List<String>> pendingIndexKey;

    /** Guards read-modify-write on the shared index lists, which eco has no atomic update for. */
    private final Object indexLock = new Object();

    public EcoStorage(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;

        this.islandKey = config("island");
        this.profileKey = config("profile");
        this.pendingKey = config("pending");
        this.profileDataKey = config("profile_data");
        this.bankKey = config("bank");
        this.bankTxnsKey = stringList("bank_txns");

        this.activeProfileKey = string("active_profile");
        this.islandOfKey = string("island_of");
        this.ownedKey = stringList("owned_profiles");
        this.memberOfKey = stringList("member_profiles");

        this.islandIndexKey = stringList("island_index");
        this.pendingIndexKey = stringList("pending_index");
    }

    // ── key construction ───────────────────────────────────────────────────────

    private PersistentDataKey<Config> config(String name) {
        return new PersistentDataKey<>(new NamespacedKey(plugin, name), PersistentDataKeyType.CONFIG,
                Configs.empty());
    }

    private PersistentDataKey<String> string(String name) {
        return new PersistentDataKey<>(new NamespacedKey(plugin, name), PersistentDataKeyType.STRING, "");
    }

    private PersistentDataKey<List<String>> stringList(String name) {
        return new PersistentDataKey<>(new NamespacedKey(plugin, name), PersistentDataKeyType.STRING_LIST,
                List.of());
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public boolean connect() {
        if (!Bukkit.getPluginManager().isPluginEnabled("eco")) {
            plugin.getLogger().severe("storage.type is ECO but eco is not enabled — cannot continue.");
            return false;
        }
        plugin.getLogger().info("RoyalSkyblock connected to ECO storage (eco data-handler: " + handlerName() + ").");
        return true;
    }

    /** eco's configured handler, for the boot log. Best-effort: it is a nicety, not a dependency. */
    private String handlerName() {
        try {
            org.bukkit.plugin.Plugin eco = Bukkit.getPluginManager().getPlugin("eco");
            String handler = eco == null ? null : eco.getConfig().getString("data-handler");
            return handler == null || handler.isBlank() ? "unknown" : handler.toLowerCase(Locale.ROOT);
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }

    @Override
    public void close() {
        // Nothing to release: eco owns the connection and flushes on its own disable, which runs after
        // ours because it is a hard dependency. Forcing a save here is not possible through the API
        // and would not add anything if it were.
    }

    // ── islands ────────────────────────────────────────────────────────────────

    @Override
    public @Nullable Island getIsland(UUID id) {
        Config row = read(id, islandKey);
        if (!present(row)) {
            return null;
        }
        String worldName = row.getStringOrNull("world-name");
        String profileId = row.getStringOrNull("profile-id");
        if (worldName == null || profileId == null) {
            plugin.getLogger().severe("Island " + id + " is stored without a world or profile — skipping it.");
            return null;
        }
        Island island = new Island(id, uuid(profileId), worldName, getLong(row, "created-at"));
        island.setRadius(row.getInt("radius"));
        island.setLevel(row.getDouble("level"));
        island.setHome(row.getDouble("home-x"), row.getDouble("home-y"), row.getDouble("home-z"),
                (float) row.getDouble("home-yaw"), (float) row.getDouble("home-pitch"));
        island.loadSettings(row.getStringOrNull("settings"));
        island.loadGuestHome(row.getStringOrNull("guest-home"));
        island.loadUpgrades(row.getStringOrNull("upgrades"));
        island.setRewardLevel(row.getInt("reward-level"));
        island.setPerkLevel(row.getInt("perk-level"));
        island.setUnloadedAt(getLong(row, "unloaded-at"));
        return island;
    }

    @Override
    public @Nullable Island getIslandByProfile(UUID profileId) {
        String islandId = read(profileId, islandOfKey);
        return islandId == null || islandId.isBlank() ? null : getIsland(uuid(islandId));
    }

    /**
     * Every island, resolved one keyed read at a time from the shared index.
     *
     * <p>The index is the price of a store with no query layer, and it can drift — a crash between
     * writing an island and writing the index leaves an island nothing lists. It repairs itself
     * because {@link #saveIsland} re-asserts membership on every save and islands are saved whenever
     * their level is recalculated, so a dropped entry comes back within a cycle rather than needing an
     * admin. Ids that no longer resolve are pruned here, which is the only write on this read path.
     */
    @Override
    public List<Island> getAllIslands() {
        List<String> index = readIndex(islandIndexKey);
        List<Island> out = new ArrayList<>(index.size());
        List<String> stale = new ArrayList<>();
        for (String id : index) {
            Island island = getIsland(uuid(id));
            if (island == null) {
                stale.add(id);
            } else {
                out.add(island);
            }
        }
        if (!stale.isEmpty()) {
            synchronized (indexLock) {
                List<String> current = new ArrayList<>(readIndex(islandIndexKey));
                if (current.removeAll(stale)) {
                    writeIndex(islandIndexKey, current);
                }
            }
            plugin.getLogger().warning("Pruned " + stale.size() + " island id(s) from the index that no "
                    + "longer resolve to an island.");
        }
        return out;
    }

    @Override
    public boolean saveIsland(Island island) {
        Config row = Configs.empty();
        row.set("v", SCHEMA);
        row.set("profile-id", island.profileId().toString());
        row.set("world-name", island.worldName());
        putLong(row, "created-at", island.createdAt());
        row.set("radius", island.radius());
        row.set("level", island.level());
        row.set("home-x", island.homeX());
        row.set("home-y", island.homeY());
        row.set("home-z", island.homeZ());
        row.set("home-yaw", (double) island.homeYaw());
        row.set("home-pitch", (double) island.homePitch());
        row.set("settings", island.serializeSettings());
        row.set("guest-home", island.serializeGuestHome());
        row.set("upgrades", island.serializeUpgrades());
        row.set("reward-level", island.rewardLevel());
        row.set("perk-level", island.perkLevel());
        putLong(row, "unloaded-at", island.unloadedAt());

        write(island.id(), islandKey, row);
        write(island.profileId(), islandOfKey, island.id().toString());
        addToIndex(islandIndexKey, island.id().toString());
        return true;
    }

    @Override
    public boolean deleteIsland(UUID id) {
        Island island = getIsland(id);
        if (island != null) {
            write(island.profileId(), islandOfKey, "");
        }
        write(id, islandKey, Configs.empty());
        write(id, pendingKey, Configs.empty());
        removeFromIndex(islandIndexKey, id.toString());
        removeFromIndex(pendingIndexKey, id.toString());
        return true;
    }

    // ── profiles ───────────────────────────────────────────────────────────────

    @Override
    public @Nullable Profile getProfile(UUID id) {
        Config row = read(id, profileKey);
        if (!present(row)) {
            return null;
        }
        String owner = row.getStringOrNull("owner");
        if (owner == null) {
            plugin.getLogger().severe("Profile " + id + " is stored without an owner — skipping it.");
            return null;
        }
        Profile profile = new Profile(id, uuid(owner), orEmpty(row.getStringOrNull("name")),
                Gamemode.fromString(row.getStringOrNull("gamemode"), Gamemode.SOLO), getLong(row, "created-at"));
        for (String entry : orEmpty(row.getStringsOrNull("members"))) {
            ProfileMember member = readMember(entry);
            if (member == null) {
                plugin.getLogger().warning("Ignoring malformed roster entry on profile " + id + ": " + entry);
                continue;
            }
            profile.putMember(member);
        }
        String islandId = read(id, islandOfKey);
        if (islandId != null && !islandId.isBlank()) {
            profile.setIslandId(uuid(islandId));
        }
        return profile;
    }

    @Override
    public List<Profile> getProfilesByOwner(UUID owner) {
        List<Profile> out = new ArrayList<>();
        for (String id : orEmpty(read(derived("rsb-player", owner.toString()), ownedKey))) {
            Profile profile = getProfile(uuid(id));
            if (profile != null) {
                out.add(profile);
            }
        }
        out.sort(java.util.Comparator.comparingLong(Profile::createdAt));
        return out;
    }

    @Override
    public List<UUID> getProfileIdsByMember(UUID uuid) {
        List<UUID> out = new ArrayList<>();
        for (String id : orEmpty(read(derived("rsb-player", uuid.toString()), memberOfKey))) {
            out.add(uuid(id));
        }
        return out;
    }

    /**
     * Save a profile and reconcile the three lookups that point at it.
     *
     * <p>The roster is replaced wholesale, exactly as the SQL version deletes and re-inserts it, so
     * anyone dropped from it also has to lose their {@code member_profiles} entry. That needs the
     * roster as it was, which is why the previous row is read before the new one overwrites it.
     */
    @Override
    public boolean saveProfile(Profile profile) {
        List<String> previousMembers = orEmpty(read(profile.id(), profileKey).getStringsOrNull("members"));

        Config row = Configs.empty();
        row.set("v", SCHEMA);
        row.set("owner", profile.owner().toString());
        row.set("name", profile.name());
        row.set("gamemode", profile.gamemode().name());
        putLong(row, "created-at", profile.createdAt());

        List<String> members = new ArrayList<>();
        for (ProfileMember member : profile.members()) {
            members.add(writeMember(member));
        }
        row.set("members", members);
        write(profile.id(), profileKey, row);

        addToIndex(ownedKey, derived("rsb-player", profile.owner().toString()), profile.id().toString());
        for (String entry : previousMembers) {
            ProfileMember was = readMember(entry);
            if (was != null && !profile.isMember(was.uuid())) {
                removeFromIndex(memberOfKey, derived("rsb-player", was.uuid().toString()), profile.id().toString());
            }
        }
        for (ProfileMember member : profile.members()) {
            addToIndex(memberOfKey, derived("rsb-player", member.uuid().toString()), profile.id().toString());
        }
        return true;
    }

    @Override
    public boolean deleteProfile(UUID id) {
        Profile profile = getProfile(id);
        if (profile != null) {
            for (ProfileMember member : profile.members()) {
                removeFromIndex(memberOfKey, derived("rsb-player", member.uuid().toString()), id.toString());
                deleteProfileData(id, member.uuid());
            }
            removeFromIndex(ownedKey, derived("rsb-player", profile.owner().toString()), id.toString());
        }
        write(id, profileKey, Configs.empty());
        write(id, islandOfKey, "");
        return true;
    }

    /** Parse one roster entry, or {@code null} if it is malformed. Callers report; this only parses. */
    static @Nullable ProfileMember readMember(String entry) {
        // uuid;name;role;joinedAt — a name is [A-Za-z0-9_] so it can never contain the separator.
        String[] parts = entry.split(";", -1);
        if (parts.length != 4) {
            return null;
        }
        IslandRole role;
        try {
            role = IslandRole.valueOf(parts[2]);
        } catch (IllegalArgumentException unknown) {
            role = IslandRole.MEMBER;
        }
        return new ProfileMember(uuid(parts[0]), parts[1], role, parseLong(parts[3]));
    }

    static String writeMember(ProfileMember member) {
        return member.uuid() + ";" + orEmpty(member.name()) + ";" + member.role().name() + ";" + member.joinedAt();
    }

    // ── active profile ─────────────────────────────────────────────────────────

    @Override
    public @Nullable UUID getActiveProfile(UUID player) {
        String id = read(derived("rsb-player", player.toString()), activeProfileKey);
        return id == null || id.isBlank() ? null : uuid(id);
    }

    @Override
    public void setActiveProfile(UUID player, UUID profileId) {
        write(derived("rsb-player", player.toString()), activeProfileKey,
                profileId == null ? "" : profileId.toString());
    }

    // ── per-profile player state ───────────────────────────────────────────────

    @Override
    public @Nullable ProfileData getProfileData(UUID profileId, UUID playerUuid) {
        Config row = read(profileDataUuid(profileId, playerUuid), profileDataKey);
        if (!present(row)) {
            return null;
        }
        return new ProfileData(decode(row.getStringOrNull("inventory")), decode(row.getStringOrNull("ender-chest")),
                row.getInt("exp-level"), (float) row.getDouble("exp-progress"), row.getDouble("health"),
                row.getInt("food"), (float) row.getDouble("saturation"));
    }

    @Override
    public boolean saveProfileData(UUID profileId, UUID playerUuid, ProfileData data) {
        Config row = Configs.empty();
        row.set("v", SCHEMA);
        // Inventories are Paper's item bytes; base64 keeps them intact through a text-backed handler.
        row.set("inventory", encode(data.inventory()));
        row.set("ender-chest", encode(data.enderChest()));
        row.set("exp-level", data.expLevel());
        row.set("exp-progress", (double) data.expProgress());
        row.set("health", data.health());
        row.set("food", data.food());
        row.set("saturation", (double) data.saturation());
        write(profileDataUuid(profileId, playerUuid), profileDataKey, row);
        return true;
    }

    @Override
    public void deleteProfileData(UUID profileId, UUID playerUuid) {
        write(profileDataUuid(profileId, playerUuid), profileDataKey, Configs.empty());
    }

    static UUID profileDataUuid(UUID profileId, UUID playerUuid) {
        return derived("rsb-data", profileId + ":" + playerUuid);
    }

    // ── pending upgrades ───────────────────────────────────────────────────────

    @Override
    public List<PendingUpgrade> getAllPending() {
        List<PendingUpgrade> out = new ArrayList<>();
        for (String id : readIndex(pendingIndexKey)) {
            UUID islandId = uuid(id);
            out.addAll(readPending(islandId));
        }
        return out;
    }

    private List<PendingUpgrade> readPending(UUID islandId) {
        List<PendingUpgrade> out = new ArrayList<>();
        for (String entry : orEmpty(read(islandId, pendingKey).getStringsOrNull("entries"))) {
            // upgradeKey;targetTier;completeAt — upgrade keys are config ids, so no separator in them.
            String[] parts = entry.split(";", -1);
            if (parts.length != 3) {
                plugin.getLogger().warning("Ignoring malformed pending upgrade on " + islandId + ": " + entry);
                continue;
            }
            out.add(new PendingUpgrade(islandId, parts[0], (int) parseLong(parts[1]), parseLong(parts[2])));
        }
        return out;
    }

    @Override
    public boolean savePending(PendingUpgrade p) {
        List<PendingUpgrade> current = readPending(p.islandId());
        current.removeIf(existing -> existing.upgradeKey().equals(p.upgradeKey()));
        current.add(p);
        writePending(p.islandId(), current);
        return true;
    }

    @Override
    public void deletePending(UUID islandId, String upgradeKey) {
        List<PendingUpgrade> current = readPending(islandId);
        if (current.removeIf(existing -> existing.upgradeKey().equals(upgradeKey))) {
            writePending(islandId, current);
        }
    }

    private void writePending(UUID islandId, List<PendingUpgrade> pending) {
        Config row = Configs.empty();
        row.set("v", SCHEMA);
        List<String> entries = new ArrayList<>();
        for (PendingUpgrade p : pending) {
            entries.add(p.upgradeKey() + ";" + p.targetTier() + ";" + p.completeAt());
        }
        row.set("entries", entries);
        write(islandId, pendingKey, row);
        // The index carries only islands that have something cooking, so getAllPending stays
        // proportional to the timers running rather than to every island ever made.
        if (entries.isEmpty()) {
            removeFromIndex(pendingIndexKey, islandId.toString());
        } else {
            addToIndex(pendingIndexKey, islandId.toString());
        }
    }

    // ── bank ───────────────────────────────────────────────────────────────────

    @Override
    public @Nullable BankAccount getBankAccount(String accountId) {
        Config row = read(bankUuid(accountId), bankKey);
        if (!present(row)) {
            return null;
        }
        return new BankAccount(accountId, row.getDouble("balance"), row.getInt("level"),
                getLong(row, "last-interest"));
    }

    /**
     * Write the balance and append the ledger entry.
     *
     * <p>SQL did both in one transaction so they could not disagree; eco has no transaction, so they
     * are two writes. They land in the same in-memory buffer microseconds apart and are flushed
     * together, which makes a split far less likely than the wording suggests — but a crash between
     * them would keep the balance and lose the ledger line, never the reverse, because the balance is
     * written first. A ledger that under-reports is the safer failure of the two.
     */
    @Override
    public boolean saveBankAccountWithTxn(BankAccount account, String type, double amount,
                                          double balanceAfter, String note) {
        UUID id = bankUuid(account.id());

        Config row = Configs.empty();
        row.set("v", SCHEMA);
        row.set("balance", account.balance());
        row.set("level", account.level());
        putLong(row, "last-interest", account.lastInterest());
        write(id, bankKey, row);

        List<String> ledger = new ArrayList<>();
        ledger.add(writeTxn(new BankTxn(type, amount, balanceAfter,
                java.time.Instant.now().getEpochSecond(), note == null ? "" : note)));
        ledger.addAll(orEmpty(read(id, bankTxnsKey)));
        if (ledger.size() > MAX_TXNS) {
            ledger = new ArrayList<>(ledger.subList(0, MAX_TXNS));
        }
        write(id, bankTxnsKey, ledger);
        return true;
    }

    /**
     * The newest {@code limit} entries. The list is already newest-first and capped at
     * {@link #MAX_TXNS}, so this is a sublist rather than the sort-and-limit SQL needed an index for.
     */
    @Override
    public List<BankTxn> getBankTransactions(String accountId, int limit) {
        List<String> ledger = orEmpty(read(bankUuid(accountId), bankTxnsKey));
        List<BankTxn> out = new ArrayList<>();
        for (String entry : ledger) {
            if (out.size() >= Math.max(1, limit)) {
                break;
            }
            BankTxn txn = readTxn(entry);
            if (txn == null) {
                plugin.getLogger().warning("Ignoring malformed ledger entry on " + accountId + ": " + entry);
                continue;
            }
            out.add(txn);
        }
        return out;
    }

    static String writeTxn(BankTxn txn) {
        // The note is player-supplied text, so it is base64'd rather than trusted not to contain the
        // separator. Everything before it is numeric or a fixed word and stays readable in data.yml.
        return txn.type() + ";" + txn.amount() + ";" + txn.balanceAfter() + ";" + txn.timestamp()
                + ";" + encode(txn.note().getBytes(StandardCharsets.UTF_8));
    }

    static @Nullable BankTxn readTxn(String entry) {
        String[] parts = entry.split(";", -1);
        if (parts.length != 5) {
            return null;
        }
        byte[] note = decode(parts[4]);
        return new BankTxn(parts[0], parseDouble(parts[1]), parseDouble(parts[2]), parseLong(parts[3]),
                note == null ? "" : new String(note, StandardCharsets.UTF_8));
    }

    static UUID bankUuid(String accountId) {
        return derived("rsb-bank", accountId);
    }

    // ── eco access ─────────────────────────────────────────────────────────────

    private <T> T read(UUID uuid, PersistentDataKey<T> key) {
        return PlayerProfile.load(uuid).read(key);
    }

    private <T> void write(UUID uuid, PersistentDataKey<T> key, T value) {
        PlayerProfile.load(uuid).write(key, value);
    }

    private List<String> readIndex(PersistentDataKey<List<String>> key) {
        return orEmpty(ServerProfile.load().read(key));
    }

    private void writeIndex(PersistentDataKey<List<String>> key, List<String> value) {
        ServerProfile.load().write(key, value);
    }

    private void addToIndex(PersistentDataKey<List<String>> key, String value) {
        synchronized (indexLock) {
            List<String> current = readIndex(key);
            if (current.contains(value)) {
                return;
            }
            List<String> updated = new ArrayList<>(current);
            updated.add(value);
            writeIndex(key, updated);
        }
    }

    private void removeFromIndex(PersistentDataKey<List<String>> key, String value) {
        synchronized (indexLock) {
            List<String> updated = new ArrayList<>(readIndex(key));
            if (updated.remove(value)) {
                writeIndex(key, updated);
            }
        }
    }

    private void addToIndex(PersistentDataKey<List<String>> key, UUID owner, String value) {
        synchronized (indexLock) {
            // A LinkedHashSet because these lists are rewritten on every profile save and a duplicate
            // would otherwise be permanent.
            LinkedHashSet<String> updated = new LinkedHashSet<>(orEmpty(read(owner, key)));
            if (updated.add(value)) {
                write(owner, key, new ArrayList<>(updated));
            }
        }
    }

    private void removeFromIndex(PersistentDataKey<List<String>> key, UUID owner, String value) {
        synchronized (indexLock) {
            List<String> updated = new ArrayList<>(orEmpty(read(owner, key)));
            if (updated.remove(value)) {
                write(owner, key, updated);
            }
        }
    }

    // ── value helpers ──────────────────────────────────────────────────────────

    /**
     * A deterministic UUID for a key that has none of its own.
     *
     * <p>Name-based (version 3) so the same account or profile-slot maps to the same UUID on every
     * node and every restart, and so it cannot collide with the random (version 4) ids Minecraft and
     * this plugin generate — the version nibble differs. The prefixes match the convention
     * {@link com.mystipixel.royalskyblock.hooks.EcoProfileBridge} already established.
     */
    static UUID derived(String prefix, String key) {
        return UUID.nameUUIDFromBytes((prefix + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    /** Whether a config actually holds a row, rather than being the empty default of an absent key. */
    private static boolean present(Config config) {
        return config != null && config.getInt("v") > 0;
    }

    /**
     * Longs go in as strings. eco's {@link Config} has no long accessor, and reading a timestamp back
     * through {@code getInt} would silently truncate it.
     */
    private static void putLong(Config config, String path, long value) {
        config.set(path, Long.toString(value));
    }

    private static long getLong(Config config, String path) {
        return parseLong(config.getStringOrNull(path));
    }

    private static long parseLong(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException malformed) {
            return 0L;
        }
    }

    private static double parseDouble(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return 0d;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException malformed) {
            return 0d;
        }
    }

    private static String encode(byte @Nullable [] bytes) {
        return bytes == null ? "" : Base64.getEncoder().encodeToString(bytes);
    }

    private static byte @Nullable [] decode(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static UUID uuid(String raw) {
        return UUID.fromString(raw.trim());
    }

    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> orEmpty(@Nullable List<T> value) {
        return value == null ? List.of() : value;
    }
}
