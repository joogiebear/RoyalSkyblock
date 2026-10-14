package com.mystipixel.royalskyblock.data;

import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.island.IslandRole;
import com.mystipixel.royalskyblock.profile.Gamemode;
import com.mystipixel.royalskyblock.profile.Profile;
import com.mystipixel.royalskyblock.profile.ProfileMember;
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
}
