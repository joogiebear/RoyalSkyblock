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
 * {@link Storage} on eco's data layer ({@code storage.type: ECO}): islands, profiles, rosters and
 * banks go wherever eco's {@code data-handler} keeps everything else. Each row is one
 * {@link PersistentDataKeyType#CONFIG} value on a UUID (eco accepts any UUID, not just a player's);
 * rows without their own UUID get a name-based one from {@link #derived}.
 *
 * <p>Player-scoped keys live on {@code rsb-player:<uuid>}, never the player's own UUID:
 * {@link com.mystipixel.royalskyblock.hooks.EcoProfileBridge} swaps every non-local key on the player's
 * profile when they switch profiles. Keys are built in the constructor, not statically, so a server on
 * SQL storage never registers them. Writes are buffered by eco, so a hard crash loses the last save
 * interval.
 *
 * <p>Single-node only: eco caches non-player profiles for the whole uptime, and the index lists are
 * read-modify-write under a JVM-local lock, so two nodes would drop each other's entries. Networks use
 * SQL storage; the plugin warns at boot when this store sits on a shared handler.
 */
public final class EcoStorage implements Storage {

    // bumped only if a stored layout changes incompatibly; also marks a row as present at all
    private static final int SCHEMA = 1;

    // ledger entries kept per account; older ones are dropped
    private static final int MAX_TXNS = 100;

    private final RoyalSkyblockPlugin plugin;

    // Rows. CONFIG for anything with more than one field, so a row is one read and one write.
    private final PersistentDataKey<Config> islandKey;
    private final PersistentDataKey<Config> profileKey;
    private final PersistentDataKey<Config> pendingKey;
    private final PersistentDataKey<Config> profileDataKey;
    private final PersistentDataKey<Config> bankKey;
    private final PersistentDataKey<List<String>> bankTxnsKey;

    // lookups SQL answered with an index; each lives on the UUID it is looked up by
    private final PersistentDataKey<String> activeProfileKey;   // on rsb-player:<player>
    private final PersistentDataKey<String> islandOfKey;        // on the profile's UUID
    private final PersistentDataKey<List<String>> ownedKey;     // on rsb-player:<player>
    private final PersistentDataKey<List<String>> memberOfKey;  // on rsb-player:<player>
    private final PersistentDataKey<List<String>> payoutKey;    // on rsb-player:<player>

    // full scans, on the server profile (nil UUID) so every node shares one list
    private final PersistentDataKey<List<String>> islandIndexKey;
    private final PersistentDataKey<List<String>> pendingIndexKey;

    private final PersistentDataKey<String> migratedFromKey;

    // guards read-modify-write on the shared index lists; eco has no atomic update
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
        this.payoutKey = stringList("coop_payouts");

        this.islandIndexKey = stringList("island_index");
        this.pendingIndexKey = stringList("pending_index");
        this.migratedFromKey = string(SqliteMigration.MARKER_KEY);
    }

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

    @Override
    public boolean connect() {
        if (!Bukkit.getPluginManager().isPluginEnabled("eco")) {
            plugin.getLogger().severe("storage.type is ECO but eco is not enabled: cannot continue.");
            return false;
        }
        plugin.getLogger().info("RoyalSkyblock connected to ECO storage (eco data-handler: " + handlerName() + ").");
        return true;
    }

    private String handlerName() {
        try {
            org.bukkit.plugin.Plugin eco = Bukkit.getPluginManager().getPlugin("eco");
            String handler = eco == null ? null : eco.getConfig().getString("data-handler");
            return handler == null || handler.isBlank() ? "unknown" : handler.toLowerCase(Locale.ROOT);
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }

    /** Whether this store already holds islands, so a migration won't merge into a live store. */
    public boolean hasIslands() {
        return !readIndex(islandIndexKey).isEmpty();
    }

    /** The source a previous migration came from, or blank if this store was never migrated into. */
    public String migrationMarker() {
        return orEmpty(ServerProfile.load().read(migratedFromKey));
    }

    public void setMigrationMarker(String source) {
        ServerProfile.load().write(migratedFromKey, source);
    }

    @Override
    public void close() {
        // Nothing to release: eco owns the connection and flushes on its own disable, which runs after
        // ours because it is a hard dependency.
    }

    @Override
    public @Nullable Island getIsland(UUID id) {
        Config row = read(id, islandKey);
        if (!present(row)) {
            return null;
        }
        String worldName = row.getStringOrNull("world-name");
        String profileId = row.getStringOrNull("profile-id");
        if (worldName == null || profileId == null) {
            plugin.getLogger().severe("Island " + id + " is stored without a world or profile: skipping it.");
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
     * Every island, resolved one keyed read at a time from the shared index. The index can drift after a
     * crash; {@link #saveIsland} re-adds membership on every save, and ids that no longer resolve are
     * pruned here.
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

    @Override
    public @Nullable Profile getProfile(UUID id) {
        Config row = read(id, profileKey);
        if (!present(row)) {
            return null;
        }
        String owner = row.getStringOrNull("owner");
        if (owner == null) {
            plugin.getLogger().severe("Profile " + id + " is stored without an owner: skipping it.");
            return null;
        }
        Profile profile = new Profile(id, uuid(owner), orEmpty(row.getStringOrNull("name")),
                Gamemode.fromString(row.getStringOrNull("gamemode"), Gamemode.SOLO), getLong(row, "created-at"));
        profile.setRewardLevel(row.getInt("reward-level"));
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
     * Save a profile and reconcile the lookups that point at it. The roster is replaced wholesale, so the
     * previous row is read first to drop removed members' {@code member_profiles} entries.
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
        row.set("reward-level", profile.rewardLevel());

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

    // parse one roster entry, or null if malformed; callers report
    static @Nullable ProfileMember readMember(String entry) {
        // uuid;name;role;joinedAt (a name is [A-Za-z0-9_] so it never contains the separator)
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

    @Override
    public void addCoopPayout(UUID player, UUID fromProfile) {
        addToIndex(payoutKey, derived("rsb-player", player.toString()), fromProfile.toString());
    }

    @Override
    public List<UUID> getCoopPayouts(UUID player) {
        List<UUID> out = new ArrayList<>();
        for (String id : orEmpty(read(derived("rsb-player", player.toString()), payoutKey))) {
            out.add(uuid(id));
        }
        return out;
    }

    @Override
    public void removeCoopPayout(UUID player, UUID fromProfile) {
        removeFromIndex(payoutKey, derived("rsb-player", player.toString()), fromProfile.toString());
    }

    static UUID profileDataUuid(UUID profileId, UUID playerUuid) {
        return derived("rsb-data", profileId + ":" + playerUuid);
    }

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
            // upgradeKey;targetTier;completeAt (upgrade keys are config ids, so no separator in them)
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
        // the index only carries islands with something cooking, so getAllPending stays proportional to
        // running timers
        if (entries.isEmpty()) {
            removeFromIndex(pendingIndexKey, islandId.toString());
        } else {
            addToIndex(pendingIndexKey, islandId.toString());
        }
    }

    @Override
    public @Nullable BankAccount getBankAccount(String accountId) {
        Config row = read(bankUuid(accountId), bankKey);
        if (!present(row)) {
            return null;
        }
        return new BankAccount(accountId, row.getDouble("balance"), row.getInt("level"),
                getLong(row, "last-interest"),
                row.has("interest-floor") ? row.getDouble("interest-floor") : -1.0);
    }

    /**
     * Write the balance and append the ledger entry. eco has no transactions, so these are two writes;
     * the balance goes first, so a crash between them loses a ledger line, never the balance.
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
        row.set("interest-floor", account.interestFloor());
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

    // For SqliteMigration only: writes the account and its existing ledger (newest first) without
    // appending a transaction, so the migrated history is kept.
    void importBankAccount(BankAccount account, List<BankTxn> newestFirst) {
        UUID id = bankUuid(account.id());

        Config row = Configs.empty();
        row.set("v", SCHEMA);
        row.set("balance", account.balance());
        row.set("level", account.level());
        putLong(row, "last-interest", account.lastInterest());
        row.set("interest-floor", account.interestFloor());
        write(id, bankKey, row);

        List<String> ledger = new ArrayList<>();
        for (BankTxn txn : newestFirst) {
            if (ledger.size() >= MAX_TXNS) {
                break;
            }
            ledger.add(writeTxn(txn));
        }
        write(id, bankTxnsKey, ledger);
    }

    /** The newest {@code limit} entries; the list is already newest-first and capped at {@link #MAX_TXNS}. */
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
        // The note is player text, so it is base64'd rather than trusted not to contain the separator.
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
            // a LinkedHashSet: these lists are rewritten on every profile save, so a duplicate would be permanent
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

    // Name-based (version 3), so the same key maps to the same UUID on every node and restart and can't
    // collide with random (version 4) ids. Prefixes follow EcoProfileBridge's convention.
    static UUID derived(String prefix, String key) {
        return UUID.nameUUIDFromBytes((prefix + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    // whether a config holds a row rather than the empty default of an absent key
    private static boolean present(Config config) {
        return config != null && config.getInt("v") > 0;
    }

    // Longs go in as strings: eco's Config has no long accessor, and getInt would truncate a timestamp.
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
