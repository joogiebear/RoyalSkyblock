package com.mystipixel.royalskyblock.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks every setting against the real driver. The old multi-statement init string looked right and
 * applied only its first PRAGMA, which is exactly the kind of thing only a test like this catches.
 */
class SqliteSettingsTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("every connection gets WAL, NORMAL sync, the busy timeout and foreign keys")
    void everySettingIsApplied() throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("islands.db");
        for (int i = 0; i < 2; i++) { // a second connection too: pooled connections must all get them
            try (Connection c = DriverManager.getConnection(url, SqliteSettings.properties());
                 Statement st = c.createStatement()) {
                assertEquals("wal", pragma(st, "journal_mode").toLowerCase());
                assertEquals("1", pragma(st, "synchronous"), "synchronous NORMAL is 1");
                assertEquals(String.valueOf(SqliteSettings.BUSY_TIMEOUT_MS), pragma(st, "busy_timeout"));
                assertEquals("1", pragma(st, "foreign_keys"));
            }
        }
    }

    private static String pragma(Statement st, String name) throws Exception {
        try (ResultSet rs = st.executeQuery("PRAGMA " + name)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
