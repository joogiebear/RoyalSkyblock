package com.mystipixel.royalskyblock.data;

import com.mystipixel.royalskyblock.bank.BankAccount;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.message.Reason;
import com.mystipixel.royalskyblock.island.IslandRole;
import com.mystipixel.royalskyblock.profile.Gamemode;
import com.mystipixel.royalskyblock.profile.Profile;
import com.mystipixel.royalskyblock.profile.ProfileMember;
import com.mystipixel.royalskyblock.upgrade.PendingUpgrade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two {@link SqlStorage}s on one database stand in for two servers. Here both open one SQLite file;
 * {@link MySqlSqlStorageSharedTest} runs the same cases on MariaDB/MySQL.
 */
class SqlStorageSharedTest {

    @TempDir
    Path dir;

    private final List<SqlStorage> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        synchronized (opened) {
            opened.forEach(SqlStorage::close);
            opened.clear();
        }
    }

    SqlSettings settings(String serverId) {
        Path file = dir.resolve("islands.db");
        return new SqlSettings(SqlStorage.Type.SQLITE, "jdbc:sqlite:" + file.toAbsolutePath(),
                SqlSettings.SQLITE_DRIVER, null, null, SqliteSettings.POOL_SIZE, SqliteSettings.properties(),
                serverId, folder(serverId));
    }

    Connection raw() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("islands.db").toAbsolutePath());
    }

    final java.io.File folder(String serverId) {
        try {
            return Files.createDirectories(dir.resolve(serverId)).toFile();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    final SqlStorage open(String serverId) {
        SqlStorage storage = tryOpen(serverId, Logger.getLogger("rsb-test-" + serverId));
        assertTrue(storage != null, "storage " + serverId + " connects");
        return storage;
    }

    final SqlStorage open(String serverId, java.time.Duration hostTtl) {
        SqlStorage storage = new SqlStorage(settings(serverId), Logger.getLogger("rsb-test-" + serverId));
        storage.hostTtl(hostTtl);
        assertTrue(storage.connect(), "storage " + serverId + " connects");
        synchronized (opened) {
            opened.add(storage);
        }
        return storage;
    }

    // null when connect() refused
    final SqlStorage tryOpen(String serverId, Logger logger) {
        SqlStorage storage = new SqlStorage(settings(serverId), logger);
        if (!storage.connect()) {
            return null;
        }
        synchronized (opened) {
            opened.add(storage);
        }
        return storage;
    }

    final int schemaVersion() throws SQLException {
        try (Connection c = raw(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM rsb_schema_version WHERE id = 1")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    final boolean hasColumn(String table, String column) throws SQLException {
        try (Connection c = raw(); ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, table, column)) {
            return rs.next();
        }
    }

    @Test
    void migrationStampsVersionOneOnAFreshDatabase() throws Exception {
        open("a");
        assertEquals(SqlStorage.SCHEMA_VERSION, schemaVersion());
        assertTrue(hasColumn("islands", "version"));
        assertTrue(hasColumn("profiles", "version"));
        assertTrue(hasColumn("rsb_island_hosts", "holder"));
        assertTrue(hasColumn("rsb_player_session", "state"));
        // a second server finds nothing to do
        open("b");
        assertEquals(SqlStorage.SCHEMA_VERSION, schemaVersion());
    }

    @Test
    void legacyTablesWithoutNewColumnsAreUpgradedOnce() throws Exception {
        try (Connection c = raw(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE islands (id VARCHAR(36) PRIMARY KEY, profile_id VARCHAR(36) NOT NULL, "
                    + "world_name VARCHAR(255) NOT NULL, created_at BIGINT NOT NULL, radius INT NOT NULL DEFAULT 50, "
                    + "level DOUBLE NOT NULL DEFAULT 0, home_x DOUBLE NOT NULL DEFAULT 0, home_y DOUBLE NOT NULL DEFAULT 0, "
                    + "home_z DOUBLE NOT NULL DEFAULT 0, home_yaw FLOAT NOT NULL DEFAULT 0, "
                    + "home_pitch FLOAT NOT NULL DEFAULT 0)");
            st.executeUpdate("INSERT INTO islands (id, profile_id, world_name, created_at) VALUES "
                    + "('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', 'island_x', 5)");
        }
        assertFalse(hasColumn("islands", "settings"));
        SqlStorage a = open("a");
        assertTrue(hasColumn("islands", "settings"));
        assertTrue(hasColumn("islands", "unloaded_at"));
        assertTrue(hasColumn("islands", "version"));
        assertEquals(SqlStorage.SCHEMA_VERSION, schemaVersion());
        assertEquals("island_x", a.getIsland(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .worldName());
    }

    @Test
    void aNewerSchemaRefusesToConnect() throws Exception {
        open("a");
        try (Connection c = raw(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE rsb_schema_version SET version = 99 WHERE id = 1");
        }
        assertEquals(null, tryOpen("b", Logger.getLogger("rsb-test-b")));
    }

    final Island newIsland(SqlStorage storage) {
        UUID id = UUID.randomUUID();
        Island island = new Island(id, UUID.randomUUID(), "island_" + id, 1L);
        island.setRadius(50);
        assertTrue(storage.insertIsland(island));
        return island;
    }

    final Profile newProfile(SqlStorage storage, UUID owner) {
        Profile profile = new Profile(UUID.randomUUID(), owner, "Apple", Gamemode.COOP, 1L);
        profile.putMember(new ProfileMember(owner, "owner", IslandRole.OWNER, 1L));
        assertTrue(storage.insertProfile(profile));
        return profile;
    }

    @Test
    void insertNeverOverwritesAnExistingRow() {
        SqlStorage a = open("a");
        Island island = newIsland(a);
        island.setLevel(99);
        assertFalse(a.insertIsland(island));
        assertEquals(0, a.getIsland(island.id()).level());
    }

    @Test
    void homeOnOneServerAndLevelOnAnotherBothPersist() {
        SqlStorage a = open("a");
        SqlStorage b = open("b");
        Island island = newIsland(a);
        long before = a.getIsland(island.id()).version();
        assertTrue(a.updateIslandHome(island.id(), 1, 2, 3, 4f, 5f) > before);
        long after = b.updateIslandLevel(island.id(), 12.5, 0);
        assertEquals(before + 2, after);
        Island read = b.getIsland(island.id());
        assertEquals(2, read.homeY());
        assertEquals(12.5, read.level());
        assertEquals(after, read.version());
        assertEquals(-1, a.updateIslandHome(UUID.randomUUID(), 0, 0, 0, 0f, 0f));
    }

    @Test
    void rewardAndPerkLevelsNeverGoDown() {
        SqlStorage a = open("a");
        SqlStorage b = open("b");
        Island island = newIsland(a);
        Profile profile = newProfile(a, UUID.randomUUID());
        a.updateIslandLevel(island.id(), 10, 7);
        b.updateIslandLevel(island.id(), 9, 4);
        a.updateIslandPerkLevel(island.id(), 6);
        b.updateIslandPerkLevel(island.id(), 2);
        assertTrue(a.updateProfileRewardLevel(profile.id(), 8));
        assertTrue(b.updateProfileRewardLevel(profile.id(), 3));
        Island read = a.getIsland(island.id());
        assertEquals(9, read.level());
        assertEquals(7, read.rewardLevel());
        assertEquals(6, read.perkLevel());
        assertEquals(8, b.getProfile(profile.id()).rewardLevel());
    }

    @Test
    void settingAndUpgradeTierUpdatesMergeRatherThanReplace() {
        SqlStorage a = open("a");
        SqlStorage b = open("b");
        Island island = newIsland(a);
        a.updateIslandUpgradeTier(island.id(), "size", 2, 75);
        b.updateIslandUpgradeTier(island.id(), "guest-limit", 1, null);
        a.updateIslandSetting(island.id(), "pvp", true);
        b.updateIslandSetting(island.id(), "listed", false);
        Island read = a.getIsland(island.id());
        assertEquals(2, read.upgradeTier("size"));
        assertEquals(1, read.upgradeTier("guest-limit"));
        assertEquals(75, read.radius());
        assertTrue(read.serializeSettings().contains("pvp=1"));
        assertTrue(read.serializeSettings().contains("listed=0"));
    }

    @Test
    void reservePendingRefusesASecondReservationAndAStaleTier() {
        SqlStorage a = open("a");
        SqlStorage b = open("b");
        Island island = newIsland(a);
        PendingUpgrade first = new PendingUpgrade(island.id(), "size", 1, 1_000L);
        assertEquals(Storage.ReserveResult.RESERVED, a.reservePending(first, 0));
        assertEquals(Storage.ReserveResult.IN_PROGRESS,
                b.reservePending(new PendingUpgrade(island.id(), "size", 1, 2_000L), 0));
        assertEquals(Storage.ReserveResult.RESERVED,
                b.reservePending(new PendingUpgrade(island.id(), "generator", 1, 2_000L), 0));
        assertTrue(b.completePending(first, 75));
        assertFalse(a.completePending(first, 75));
        Island read = a.getIsland(island.id());
        assertEquals(1, read.upgradeTier("size"));
        assertEquals(75, read.radius());
        // a server that still sees tier 0 can't sell tier 1 again
        assertEquals(Storage.ReserveResult.TIER_CHANGED,
                b.reservePending(new PendingUpgrade(island.id(), "size", 1, 3_000L), 0));
        assertEquals(Storage.ReserveResult.FAILED,
                a.reservePending(new PendingUpgrade(UUID.randomUUID(), "size", 1, 3_000L), 0));
    }

    @Test
    void completePendingNeverLowersATier() {
        SqlStorage a = open("a");
        Island island = newIsland(a);
        PendingUpgrade p = new PendingUpgrade(island.id(), "size", 1, 0L);
        assertEquals(Storage.ReserveResult.RESERVED, a.reservePending(p, 0));
        a.updateIslandUpgradeTier(island.id(), "size", 3, 90);
        assertTrue(a.completePending(p, 75));
        Island read = a.getIsland(island.id());
        assertEquals(3, read.upgradeTier("size"));
        assertEquals(90, read.radius());
        assertTrue(a.getPending(island.id()).isEmpty());
    }

    @Test
    void duePendingOnlyListsTheGivenIslands() {
        SqlStorage a = open("a");
        Island hosted = newIsland(a);
        Island elsewhere = newIsland(a);
        a.reservePending(new PendingUpgrade(hosted.id(), "size", 1, 100L), 0);
        a.reservePending(new PendingUpgrade(hosted.id(), "generator", 1, 900L), 0);
        a.reservePending(new PendingUpgrade(elsewhere.id(), "size", 1, 100L), 0);
        List<PendingUpgrade> due = a.duePending(500L, List.of(hosted.id()));
        assertEquals(List.of(new PendingUpgrade(hosted.id(), "size", 1, 100L)), due);
        assertEquals(2, a.duePending(1_000L, List.of(hosted.id())).size());
        assertEquals(3, a.duePending(1_000L, List.of(hosted.id(), elsewhere.id())).size());
        assertTrue(a.duePending(1_000L, List.of()).isEmpty());
        assertEquals(2, a.getPending(hosted.id()).size());
    }

    @Test
    void deletePendingReportsWhetherItRemovedAnything() {
        SqlStorage a = open("a");
        SqlStorage b = open("b");
        Island island = newIsland(a);
        a.reservePending(new PendingUpgrade(island.id(), "size", 1, 100L), 0);
        assertEquals(1, b.deletePending(island.id(), "size"));
        assertEquals(0, a.deletePending(island.id(), "size"));
    }

    @Test
    void addMemberRefusesBeyondTheCapAndDuplicates() {
        SqlStorage a = open("a");
        SqlStorage b = open("b");
        Profile profile = newProfile(a, UUID.randomUUID());
        ProfileMember first = new ProfileMember(UUID.randomUUID(), "first", IslandRole.MEMBER, 2L);
        ProfileMember second = new ProfileMember(UUID.randomUUID(), "second", IslandRole.MEMBER, 3L);
        assertEquals(Storage.AddMemberResult.ADDED, a.addMember(profile.id(), first, 2));
        assertEquals(Storage.AddMemberResult.ALREADY, b.addMember(profile.id(), first, 2));
        assertEquals(Storage.AddMemberResult.FULL, b.addMember(profile.id(), second, 2));
        assertEquals(Storage.AddMemberResult.FAILED, b.addMember(UUID.randomUUID(), second, 2));
        assertEquals(2, a.getProfile(profile.id()).memberCount());
    }

    @Test
    void removeMemberAndRoleChangesDoNotTouchOtherMembers() {
        SqlStorage a = open("a");
        SqlStorage b = open("b");
        Profile profile = newProfile(a, UUID.randomUUID());
        ProfileMember kicked = new ProfileMember(UUID.randomUUID(), "kicked", IslandRole.MEMBER, 2L);
        ProfileMember promoted = new ProfileMember(UUID.randomUUID(), "promoted", IslandRole.MEMBER, 3L);
        a.addMember(profile.id(), kicked, 10);
        // b read the roster before the second member joined; its kick must not drop them
        b.getProfile(profile.id());
        a.addMember(profile.id(), promoted, 10);
        assertTrue(b.removeMember(profile.id(), kicked.uuid()));
        assertTrue(a.setMemberRole(profile.id(), promoted.uuid(), IslandRole.CO_OWNER));
        Profile read = b.getProfile(profile.id());
        assertFalse(read.isMember(kicked.uuid()));
        assertEquals(IslandRole.CO_OWNER, read.roleOf(promoted.uuid()));
        assertEquals(IslandRole.OWNER, read.roleOf(profile.owner()));
        assertFalse(a.setMemberRole(profile.id(), kicked.uuid(), IslandRole.CO_OWNER));
    }

    @Test
    void transferOwnerSwapsRolesAtomically() {
        SqlStorage a = open("a");
        UUID owner = UUID.randomUUID();
        Profile profile = newProfile(a, owner);
        ProfileMember heir = new ProfileMember(UUID.randomUUID(), "heir", IslandRole.MEMBER, 2L);
        a.addMember(profile.id(), heir, 10);
        long before = a.getProfile(profile.id()).version();
        assertTrue(open("b").transferOwner(profile.id(), heir.uuid(), owner));
        Profile read = a.getProfile(profile.id());
        assertEquals(heir.uuid(), read.owner());
        assertEquals(IslandRole.OWNER, read.roleOf(heir.uuid()));
        assertEquals(IslandRole.CO_OWNER, read.roleOf(owner));
        assertTrue(read.version() > before);
        // someone no longer on the roster can't be made owner, and nothing changes
        assertFalse(a.transferOwner(profile.id(), UUID.randomUUID(), heir.uuid()));
        assertEquals(heir.uuid(), a.getProfile(profile.id()).owner());
    }

    private static final Reason NOT_ENOUGH = new Reason("bank.result.account.withdraw.not-enough-money");

    static BankOutcome withdrawOne(BankAccount locked) {
        return locked.balance() < 1 ? BankOutcome.refuse(locked, NOT_ENOUGH)
                : BankOutcome.write(locked.withBalance(locked.balance() - 1), "WITHDRAW", 1, "test");
    }

    @Test
    void withdrawalsNeverExceedTheBalance() throws Exception {
        SqlStorage a = open("a");
        SqlStorage b = open("b");
        String account = "c:" + UUID.randomUUID();
        a.bankTransaction(account, 1, locked -> BankOutcome.write(locked.withBalance(100), "DEPOSIT", 100, "seed"));
        java.util.concurrent.atomic.AtomicInteger paid = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger refused = new java.util.concurrent.atomic.AtomicInteger();
        List<Throwable> problems = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            SqlStorage server = i % 2 == 0 ? a : b;
            threads.add(new Thread(() -> {
                for (int n = 0; n < 100; n++) {
                    BankOutcome outcome = server.bankTransaction(account, 1, SqlStorageSharedTest::withdrawOne);
                    if (outcome.failed()) {
                        problems.add(new AssertionError("a withdrawal failed"));
                    } else if (outcome.written()) {
                        paid.incrementAndGet();
                    } else if (outcome.refusal() == NOT_ENOUGH) {
                        refused.incrementAndGet();
                    } else {
                        problems.add(new AssertionError("unexpected refusal " + outcome.refusal()));
                    }
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(100, paid.get());
        assertEquals(300, refused.get());
        assertEquals(0, b.getBankAccount(account).balance());
        long withdrawals = a.getBankTransactions(account, 1000).stream().filter(t -> t.type().equals("WITHDRAW")).count();
        assertEquals(100, withdrawals);
    }

    @Test
    void depositIsCappedAtTheLockedBalance() {
        SqlStorage a = open("a");
        SqlStorage b = open("b");
        String account = "p:" + UUID.randomUUID();
        double max = 1000;
        BankMutation depositUpToMax = locked -> {
            double credit = Math.min(700, max - locked.balance());
            return credit <= 0 ? BankOutcome.refuse(locked, null)
                    : BankOutcome.write(locked.withBalance(locked.balance() + credit), "DEPOSIT", credit, "test");
        };
        assertEquals(700, a.bankTransaction(account, 1, depositUpToMax).amount());
        // b's cached view said 0; the locked row says 700, so only 300 fits
        BankOutcome second = b.bankTransaction(account, 1, depositUpToMax);
        assertEquals(300, second.amount());
        assertEquals(max, second.account().balance());
        assertFalse(a.bankTransaction(account, 1, depositUpToMax).written());
    }

    @Test
    void interestIsPaidOncePerPeriod() {
        SqlStorage a = open("a");
        SqlStorage b = open("b");
        String account = "c:" + UUID.randomUUID();
        a.bankTransaction(account, 1, locked -> BankOutcome.write(locked.withBalance(100), "DEPOSIT", 100, "seed"));
        long now = 1_000_000L;
        BankMutation claim = locked -> locked.lastInterest() > 0 && now < locked.lastInterest() + 3600
                ? BankOutcome.refuse(locked, new Reason("bank.result.interest-not-ready"))
                : BankOutcome.write(locked.withBalance(locked.balance() + 5).withLastInterest(now), "INTEREST", 5, "i");
        assertTrue(a.bankTransaction(account, 1, claim).written());
        assertFalse(b.bankTransaction(account, 1, claim).written());
        assertEquals(105, a.getBankAccount(account).balance());
    }

    @Test
    void aNewAccountStartsAtTheStartingLevel() {
        SqlStorage a = open("a");
        BankOutcome outcome = a.bankTransaction("p:" + UUID.randomUUID(), 3, locked -> BankOutcome.refuse(locked, null));
        assertEquals(3, outcome.account().level());
        assertEquals(0, outcome.account().balance());
    }

    @Test
    void onlyOneServerClaimsTheUnloadStamp() {
        SqlStorage a = open("a");
        SqlStorage b = open("b");
        Island island = newIsland(a);
        a.updateIslandUnloadedAt(island.id(), 12345L);
        assertEquals(12345L, b.claimUnloadStamp(island.id()));
        assertEquals(0L, a.claimUnloadStamp(island.id()));
        assertEquals(0L, a.getIsland(island.id()).unloadedAt());
    }

    @Test
    void hostLockIsExclusiveUntilReleasedOrExpired() throws Exception {
        SqlStorage a = open("a", java.time.Duration.ofMillis(500));
        SqlStorage b = open("b", java.time.Duration.ofMillis(500));
        UUID island = UUID.randomUUID();
        assertTrue(a.hosts().tryAcquireOrRenew(island));
        assertTrue(a.hosts().tryAcquireOrRenew(island), "the holder may renew");
        assertFalse(b.hosts().tryAcquireOrRenew(island));
        a.hosts().release(island);
        assertTrue(b.hosts().tryAcquireOrRenew(island), "a released island is free at once");
        assertFalse(a.hosts().tryAcquireOrRenew(island));
        Thread.sleep(900);
        assertTrue(a.hosts().tryAcquireOrRenew(island), "an expired claim can be taken");
    }

    @Test
    void renewAllReportsLostIslands() throws Exception {
        SqlStorage a = open("a", java.time.Duration.ofMillis(500));
        SqlStorage b = open("b", java.time.Duration.ofMillis(500));
        UUID kept = UUID.randomUUID();
        UUID taken = UUID.randomUUID();
        assertTrue(a.hosts().tryAcquireOrRenew(kept));
        assertTrue(a.hosts().tryAcquireOrRenew(taken));
        Thread.sleep(900);
        // a stopped renewing; b takes one of the two, the other is still a's for the asking
        assertTrue(b.hosts().tryAcquireOrRenew(taken));
        assertEquals(1, a.hosts().renewAll());
        assertEquals(java.util.Set.of(kept), a.hosts().heldBy());
        assertFalse(b.hosts().tryAcquireOrRenew(kept));
        assertEquals(1, a.hosts().releaseAll());
        assertTrue(a.hosts().heldBy().isEmpty());
    }

    @Test
    void holderOfNamesTheServer() throws Exception {
        SqlStorage a = open("alpha");
        SqlStorage b = open("beta");
        UUID island = UUID.randomUUID();
        assertEquals(null, b.hosts().holderOf(island));
        a.hosts().tryAcquireOrRenew(island);
        assertEquals("alpha", b.hosts().holderOf(island));
        a.hosts().release(island);
        assertEquals(null, b.hosts().holderOf(island));
    }

    @Test
    void sqliteHandoffNeverWaits() throws Exception {
        SqlStorage a = open("a");
        UUID player = UUID.randomUUID();
        a.handoff().markActive(player);
        assertEquals(com.mystipixel.royalcommon.storage.PlayerHandoff.Status.SINGLE_SERVER,
                open("b").handoff().awaitSaved(player, java.time.Duration.ofSeconds(5)).status());
    }
}
