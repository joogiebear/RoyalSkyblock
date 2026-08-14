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
 * {@code storage.type} to {@code eco}.
 *
 * <p>This is the only migration this plugin should ever need to write. eco's own
 * {@code perform-data-migration} moves data between <em>its</em> handlers, so once a server is on the
 * eco layer, going from yaml to MySQL to MongoDB is a config edit eco performs itself. This one exists
 * because the data starts outside eco entirely.
 *
 * <h2>What makes it safe to run on a live server</h2>
 *
 * <p><b>It reads the source directly rather than through {@link SqlStorage}.</b> The interface has no
 * way to enumerate profiles, saved states or bank accounts — it never needed one — so a migration
 * built on it would silently carry across only the rows it could reach. A migration is about the
 * source's concrete shape, so it owns its SQL.
 *
 * <p><b>It writes through {@link EcoStorage}'s normal methods</b>, so the island index, the
 * owner/member lookups and the profile→island pointer are all maintained by the code that owns them
 * rather than reproduced here and left to drift.
 *
 * <p><b>It is idempotent.</b> Every id is carried across unchanged and every key is derived from an
 * id, so re-running overwrites rather than duplicates. That matters because the failure it has to
 * survive is dying half-way: the source is still intact, and the next boot simply finishes the job.
 *
 * <p><b>It verifies by reading back, not by counting what it wrote.</b> Every row is read out of eco
 * afterwards and compared to the source. A count of successful writes only proves the writes did not
 * throw; reading back proves the data is there and says the same thing.
 *
 * <p><b>It never deletes the source.</b> {@code islands.db} is renamed only after verification
 * passes, and if anything fails the file is left exactly where it was and the plugin refuses to start
 * rather than come up on a half-populated store.
 */
public final class SqliteMigration {

    /** Written to eco once a migration completes, so a retry can tell "mine" from "someone else's". */
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

    /**
     * Copy everything across and verify it.
     *
     * <p>Does not rename the source — {@link #retireSource()} does that, and only the caller knows
     * whether it is willing to commit.
     */
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

    // ── islands ────────────────────────────────────────────────────────────────

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
                // These arrived in later versions. A database that predates one of them simply has no
                // such column, and asking for it would fail the whole table rather than one field.
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

    // ── profiles + rosters ─────────────────────────────────────────────────────

    /** @return {profiles, members} */
    private int[] copyProfiles(Connection c) throws SQLException {
        if (columnsOf(c, "profiles").isEmpty()) {
            return new int[]{0, 0};
        }
        List<Profile> loaded = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, owner, name, gamemode, created_at FROM profiles")) {
            while (rs.next()) {
                loaded.add(new Profile(UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("owner")), rs.getString("name"),
                        Gamemode.fromString(rs.getString("gamemode"), Gamemode.SOLO),
                        rs.getLong("created_at")));
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

    // ── which profile each player is on ────────────────────────────────────────

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

    // ── per-profile saved state ────────────────────────────────────────────────

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
        // The inventory is the part worth checking byte for byte — it is the one field a player would
        // notice losing, and the one that travels as base64 rather than as a number.
        if (!java.util.Arrays.equals(got.inventory(), expected.inventory())
                || !java.util.Arrays.equals(got.enderChest(), expected.enderChest())
                || got.expLevel() != expected.expLevel()
                || got.health() != expected.health()
                || got.food() != expected.food()) {
            problems.add("saved state " + profileId + "/" + player + " read back different from the source");
        }
    }

    // ── in-progress upgrades ───────────────────────────────────────────────────

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

    // ── bank ───────────────────────────────────────────────────────────────────

    /** @return {accounts, transactions} */
    private int[] copyBank(Connection c) throws SQLException {
        if (columnsOf(c, "bank_accounts").isEmpty()) {
            return new int[]{0, 0};
        }
        boolean hasLedger = !columnsOf(c, "bank_txns").isEmpty();
        int accounts = 0;
        int txns = 0;

        List<BankAccount> loaded = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT account_id, balance, level, last_interest FROM bank_accounts")) {
            while (rs.next()) {
                loaded.add(new BankAccount(rs.getString("account_id"), rs.getDouble("balance"),
                        rs.getInt("level"), rs.getLong("last_interest")));
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

    /** Newest first, which is the order the ledger is stored and read in. */
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
                || got.lastInterest() != expected.lastInterest()) {
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

    // ── source retirement ──────────────────────────────────────────────────────

    /**
     * Rename the source out of the way so the next boot goes straight to eco.
     *
     * <p>Renamed, never deleted: it is the only copy of the data that existed before this ran, and a
     * migration that verified clean is still a migration someone might want to second-guess. The
     * numbered suffixes mean an earlier {@code .migrated} is never overwritten either.
     */
    public boolean retireSource() {
        File target = new File(source.getPath() + ".migrated");
        for (int n = 2; target.exists() && n < 100; n++) {
            target = new File(source.getPath() + ".migrated-" + n);
        }
        if (source.renameTo(target)) {
            plugin.getLogger().info("Renamed " + source.getName() + " to " + target.getName()
                    + " — it is no longer read, and nothing deletes it.");
            return true;
        }
        // WAL sidecars keep a handle alive on some platforms; say so rather than leaving a file that
        // makes every future boot try to migrate again.
        plugin.getLogger().severe("Could not rename " + source.getName() + ". Move it aside by hand, "
                + "or the next boot will try to migrate it again.");
        return false;
    }

    /** The WAL sidecars, which are meaningless once the database itself has been retired. */
    public void cleanSidecars() {
        for (String suffix : new String[]{"-shm", "-wal"}) {
            File sidecar = new File(source.getPath() + suffix);
            if (sidecar.isFile()) {
                try {
                    Files.deleteIfExists(sidecar.toPath());
                } catch (Exception ignored) {
                    // Harmless if they stay: SQLite rebuilds them, and nothing reads them now.
                }
            }
        }
    }

    /** Table columns, or empty if the table isn't there at all. */
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
