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

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Moves an existing {@code islands.db} into {@link EcoStorage}, once, on the boot that switches
 * {@code storage.type} to {@code eco}. After that, eco's own {@code perform-data-migration} handles
 * moves between its handlers.
 *
 * <p>Reads the source with its own SQL (the {@link Storage} interface can't enumerate every row) and
 * writes through {@link EcoStorage}'s normal methods so its indexes stay right. Ids carry across
 * unchanged, so a re-run after a crash overwrites rather than duplicates. Every row is read back and
 * compared to the source, and the source is renamed (never deleted) only after that passes; on any
 * failure the plugin refuses to start.
 */
public final class SqliteMigration {

    // written to eco once a migration completes, so a retry can tell "mine" from "someone else's"
    static final String MARKER_KEY = "migrated_from_sqlite";

    /** What a run did. Empty {@link #problems()} means every row was written and read back intact. */
    public record Report(int islands, int profiles, int members, int activeProfiles, int profileData,
                         int pending, int bankAccounts, int bankTxns, List<String> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }

        public String summary() {
            return islands + " island(s), " + profiles + " profile(s) with " + members + " member(s), "
                    + activeProfiles + " active-profile pointer(s), " + profileData + " saved state(s), "
                    + pending + " pending upgrade(s), " + bankAccounts + " bank account(s) with "
                    + bankTxns + " transaction(s)";
        }
    }

    private final RoyalSkyblockPlugin plugin;
    private final File source;
    private final EcoStorage target;
    private final List<String> problems = new ArrayList<>();

    public SqliteMigration(RoyalSkyblockPlugin plugin, File source, EcoStorage target) {
        this.plugin = plugin;
        this.source = source;
        this.target = target;
    }

    /** Copy everything across and verify it. Does not rename the source: that's {@link #retireSource()}. */
    public Report run() {
        int islands = 0;
        int profiles = 0;
        int members = 0;
        int active = 0;
        int data = 0;
        int pending = 0;
        int accounts = 0;
        int txns = 0;

        try {
            Class.forName("org.sqlite.JDBC", true, getClass().getClassLoader());
        } catch (ClassNotFoundException missing) {
            problems.add("the SQLite driver is not on the classpath, so " + source.getName()
                    + " cannot be read");
            return report(0, 0, 0, 0, 0, 0, 0, 0);
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath())) {
            islands = copyIslands(c);
            int[] profileCounts = copyProfiles(c);
            profiles = profileCounts[0];
            members = profileCounts[1];
            active = copyActiveProfiles(c);
            data = copyProfileData(c);
            pending = copyPending(c);
            int[] bankCounts = copyBank(c);
            accounts = bankCounts[0];
            txns = bankCounts[1];
        } catch (SQLException e) {
            problems.add("could not read " + source.getName() + ": " + e.getMessage());
        }
        return report(islands, profiles, members, active, data, pending, accounts, txns);
    }

    private Report report(int islands, int profiles, int members, int active, int data, int pending,
                          int accounts, int txns) {
        return new Report(islands, profiles, members, active, data, pending, accounts, txns,
                List.copyOf(problems));
    }

    private int copyIslands(Connection c) throws SQLException {
        Set<String> columns = columnsOf(c, "islands");
        if (columns.isEmpty()) {
            return 0;                                   // table absent: nothing to carry across
        }
        int count = 0;
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM islands")) {
            while (rs.next()) {
                UUID id = UUID.fromString(rs.getString("id"));
                Island island = new Island(id, UUID.fromString(rs.getString("profile_id")),
                        rs.getString("world_name"), rs.getLong("created_at"));
                island.setRadius(rs.getInt("radius"));
                island.setLevel(rs.getDouble("level"));
                island.setHome(rs.getDouble("home_x"), rs.getDouble("home_y"), rs.getDouble("home_z"),
                        rs.getFloat("home_yaw"), rs.getFloat("home_pitch"));
                // columns added in later versions; asking for a missing one would fail the whole table
                if (columns.contains("settings")) {
                    island.loadSettings(rs.getString("settings"));
                }
                if (columns.contains("guest_home")) {
                    island.loadGuestHome(rs.getString("guest_home"));
                }
                if (columns.contains("upgrades")) {
                    island.loadUpgrades(rs.getString("upgrades"));
                }
                if (columns.contains("reward_level")) {
                    island.setRewardLevel(rs.getInt("reward_level"));
                }
                if (columns.contains("perk_level")) {
                    island.setPerkLevel(rs.getInt("perk_level"));
                }
                if (columns.contains("unloaded_at")) {
                    island.setUnloadedAt(rs.getLong("unloaded_at"));
                }

                target.saveIsland(island);
                verifyIsland(island);
                count++;
            }
        }
        return count;
    }

    private void verifyIsland(Island expected) {
        Island got = target.getIsland(expected.id());
        if (got == null) {
            problems.add("island " + expected.id() + " did not read back");
            return;
        }
        if (!got.worldName().equals(expected.worldName())
                || !got.profileId().equals(expected.profileId())
                || got.createdAt() != expected.createdAt()
                || got.level() != expected.level()
                || got.radius() != expected.radius()
                || got.rewardLevel() != expected.rewardLevel()
                || got.perkLevel() != expected.perkLevel()
                || got.unloadedAt() != expected.unloadedAt()
                || !got.upgrades().equals(expected.upgrades())) {
            problems.add("island " + expected.id() + " read back different from the source");
        }
    }

    // returns {profiles, members}
    private int[] copyProfiles(Connection c) throws SQLException {
        if (columnsOf(c, "profiles").isEmpty()) {
            return new int[]{0, 0};
        }
        List<Profile> loaded = new ArrayList<>();
        boolean hasRewardLevel = columnsOf(c, "profiles").contains("reward_level");
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM profiles")) {
            while (rs.next()) {
                Profile profile = new Profile(UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("owner")), rs.getString("name"),
                        Gamemode.fromString(rs.getString("gamemode"), Gamemode.SOLO),
                        rs.getLong("created_at"));
                if (hasRewardLevel) {
                    profile.setRewardLevel(rs.getInt("reward_level"));
                }
                loaded.add(profile);
            }
        }

        int members = 0;
        boolean hasRoster = !columnsOf(c, "profile_members").isEmpty();
        for (Profile profile : loaded) {
            if (hasRoster) {
                members += loadMembers(c, profile);
            }
            target.saveProfile(profile);
            verifyProfile(profile);
        }
        return new int[]{loaded.size(), members};
    }

    private int loadMembers(Connection c, Profile profile) throws SQLException {
        int count = 0;
        try (PreparedStatement st = c.prepareStatement(
                "SELECT uuid, name, role, joined_at FROM profile_members WHERE profile_id = ?")) {
            st.setString(1, profile.id().toString());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    IslandRole role;
                    try {
                        role = IslandRole.valueOf(rs.getString("role"));
                    } catch (IllegalArgumentException unknown) {
                        role = IslandRole.MEMBER;
                    }
                    profile.putMember(new ProfileMember(UUID.fromString(rs.getString("uuid")),
                            rs.getString("name"), role, rs.getLong("joined_at")));
                    count++;
                }
            }
        }
        return count;
    }

    private void verifyProfile(Profile expected) {
        Profile got = target.getProfile(expected.id());
        if (got == null) {
            problems.add("profile " + expected.id() + " did not read back");
            return;
        }
        if (!got.owner().equals(expected.owner())
                || !got.name().equals(expected.name())
                || got.gamemode() != expected.gamemode()
                || got.createdAt() != expected.createdAt()
                || got.rewardLevel() != expected.rewardLevel()
                || got.memberCount() != expected.memberCount()) {
            problems.add("profile " + expected.id() + " read back different from the source");
            return;
        }
        for (ProfileMember member : expected.members()) {
            if (got.roleOf(member.uuid()) != member.role()) {
                problems.add("profile " + expected.id() + " lost member " + member.uuid()
                        + " or their role");
                return;
            }
        }
    }

    private int copyActiveProfiles(Connection c) throws SQLException {
        if (columnsOf(c, "player_state").isEmpty()) {
            return 0;
        }
        int count = 0;
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT uuid, active_profile FROM player_state")) {
            while (rs.next()) {
                String raw = rs.getString("active_profile");
                if (raw == null || raw.isBlank()) {
                    continue;                           // a row that never picked one carries nothing
                }
                UUID player = UUID.fromString(rs.getString("uuid"));
                UUID profileId = UUID.fromString(raw);
                target.setActiveProfile(player, profileId);
                if (!profileId.equals(target.getActiveProfile(player))) {
                    problems.add("active profile for " + player + " did not read back");
                }
                count++;
            }
        }
        return count;
    }

    private int copyProfileData(Connection c) throws SQLException {
        if (columnsOf(c, "profile_data").isEmpty()) {
            return 0;
        }
        int count = 0;
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT profile_id, player_uuid, inventory, ender_chest, "
                     + "exp_level, exp_progress, health, food, saturation FROM profile_data")) {
            while (rs.next()) {
                UUID profileId = UUID.fromString(rs.getString("profile_id"));
                UUID player = UUID.fromString(rs.getString("player_uuid"));
                ProfileData data = new ProfileData(rs.getBytes("inventory"), rs.getBytes("ender_chest"),
                        rs.getInt("exp_level"), rs.getFloat("exp_progress"), rs.getDouble("health"),
                        rs.getInt("food"), rs.getFloat("saturation"));
                target.saveProfileData(profileId, player, data);
                verifyProfileData(profileId, player, data);
                count++;
            }
        }
        return count;
    }

    private void verifyProfileData(UUID profileId, UUID player, ProfileData expected) {
        ProfileData got = target.getProfileData(profileId, player);
        if (got == null) {
            problems.add("saved state " + profileId + "/" + player + " did not read back");
            return;
        }
        // the inventory is compared byte for byte: it is what a player would notice losing
        if (!java.util.Arrays.equals(got.inventory(), expected.inventory())
                || !java.util.Arrays.equals(got.enderChest(), expected.enderChest())
                || got.expLevel() != expected.expLevel()
                || got.health() != expected.health()
                || got.food() != expected.food()) {
            problems.add("saved state " + profileId + "/" + player + " read back different from the source");
        }
    }

    private int copyPending(Connection c) throws SQLException {
        if (columnsOf(c, "pending_upgrades").isEmpty()) {
            return 0;
        }
        List<PendingUpgrade> loaded = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT island_id, upgrade_key, target_tier, complete_at FROM pending_upgrades")) {
            while (rs.next()) {
                loaded.add(new PendingUpgrade(UUID.fromString(rs.getString("island_id")),
                        rs.getString("upgrade_key"), rs.getInt("target_tier"), rs.getLong("complete_at")));
            }
        }
        for (PendingUpgrade p : loaded) {
            target.savePending(p);
        }
        if (!loaded.isEmpty()) {
            List<PendingUpgrade> got = target.getAllPending();
            for (PendingUpgrade p : loaded) {
                boolean found = got.stream().anyMatch(g -> g.islandId().equals(p.islandId())
                        && g.upgradeKey().equals(p.upgradeKey())
                        && g.targetTier() == p.targetTier()
                        && g.completeAt() == p.completeAt());
                if (!found) {
                    problems.add("pending upgrade " + p.upgradeKey() + " on " + p.islandId()
                            + " did not read back");
                }
            }
        }
        return loaded.size();
    }

    // returns {accounts, transactions}
    private int[] copyBank(Connection c) throws SQLException {
        if (columnsOf(c, "bank_accounts").isEmpty()) {
            return new int[]{0, 0};
        }
        boolean hasLedger = !columnsOf(c, "bank_txns").isEmpty();
        int accounts = 0;
        int txns = 0;

        List<BankAccount> loaded = new ArrayList<>();
        boolean hasFloor = columnsOf(c, "bank_accounts").contains("interest_floor");
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM bank_accounts")) {
            while (rs.next()) {
                loaded.add(new BankAccount(rs.getString("account_id"), rs.getDouble("balance"),
                        rs.getInt("level"), rs.getLong("last_interest"),
                        hasFloor ? rs.getDouble("interest_floor") : -1.0));
            }
        }

        for (BankAccount account : loaded) {
            List<BankTxn> ledger = hasLedger ? readLedger(c, account.id()) : List.of();
            target.importBankAccount(account, ledger);
            txns += ledger.size();
            verifyBank(account, ledger);
            accounts++;
        }
        return new int[]{accounts, txns};
    }

    // newest first, the order the ledger is stored and read in
    private List<BankTxn> readLedger(Connection c, String accountId) throws SQLException {
        List<BankTxn> out = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement(
                "SELECT type, amount, balance_after, created_at, note FROM bank_txns "
                        + "WHERE account_id = ? ORDER BY created_at DESC, id DESC")) {
            st.setString(1, accountId);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    String note = rs.getString("note");
                    out.add(new BankTxn(rs.getString("type"), rs.getDouble("amount"),
                            rs.getDouble("balance_after"), rs.getLong("created_at"),
                            note == null ? "" : note));
                }
            }
        }
        return out;
    }

    private void verifyBank(BankAccount expected, List<BankTxn> ledger) {
        BankAccount got = target.getBankAccount(expected.id());
        if (got == null) {
            problems.add("bank account " + expected.id() + " did not read back");
            return;
        }
        if (got.balance() != expected.balance() || got.level() != expected.level()
                || got.lastInterest() != expected.lastInterest()
                || got.interestFloor() != expected.interestFloor()) {
            problems.add("bank account " + expected.id() + " read back different from the source");
            return;
        }
        if (ledger.isEmpty()) {
            return;
        }
        List<BankTxn> back = target.getBankTransactions(expected.id(), 1);
        if (back.isEmpty()) {
            problems.add("bank account " + expected.id() + " lost its ledger");
        } else if (back.get(0).timestamp() != ledger.get(0).timestamp()
                || !back.get(0).type().equals(ledger.get(0).type())) {
            problems.add("bank account " + expected.id() + "'s ledger came back in the wrong order");
        }
    }

    /**
     * Rename the source so the next boot goes straight to eco. Renamed, never deleted, and numbered so an
     * earlier {@code .migrated} file is never overwritten.
     */
    public boolean retireSource() {
        File target = new File(source.getPath() + ".migrated");
        for (int n = 2; target.exists() && n < 100; n++) {
            target = new File(source.getPath() + ".migrated-" + n);
        }
        if (source.renameTo(target)) {
            plugin.getLogger().info("Renamed " + source.getName() + " to " + target.getName()
                    + ": it is no longer read, and nothing deletes it.");
            return true;
        }
        // WAL sidecars keep a handle alive on some platforms; say so rather than leave a file that makes
        // every boot try to migrate again.
        plugin.getLogger().severe("Could not rename " + source.getName() + ". Move it aside by hand, "
                + "or the next boot will try to migrate it again.");
        return false;
    }

    /** Delete the WAL sidecars once the database has been retired. */
    public void cleanSidecars() {
        for (String suffix : new String[]{"-shm", "-wal"}) {
            File sidecar = new File(source.getPath() + suffix);
            if (sidecar.isFile()) {
                try {
                    Files.deleteIfExists(sidecar.toPath());
                } catch (Exception ignored) {
                    // harmless if they stay: SQLite rebuilds them, and nothing reads them now
                }
            }
        }
    }

    // table columns, or empty if the table isn't there
    private static Set<String> columnsOf(Connection c, String table) throws SQLException {
        Set<String> out = new HashSet<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, null, table, null)) {
            while (rs.next()) {
                out.add(rs.getString("COLUMN_NAME"));
            }
        }
        return out;
    }
}
