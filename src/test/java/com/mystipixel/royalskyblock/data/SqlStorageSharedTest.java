package com.mystipixel.royalskyblock.data;

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
        assertEquals("island_x", a.getIsland(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
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
}
