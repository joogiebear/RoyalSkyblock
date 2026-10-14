package com.mystipixel.royalskyblock.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared-storage cases on MariaDB/MySQL, each "server" with its own pool. Runs when RSB_TEST_MYSQL_PORT
 * is set; RSB_TEST_MYSQL_USER and RSB_TEST_MYSQL_PASSWORD default to root with no password. The user needs
 * CREATE DATABASE: every test gets a fresh database and drops it afterwards.
 */
@EnabledIfEnvironmentVariable(named = "RSB_TEST_MYSQL_PORT", matches = "[0-9]+")
class MySqlSqlStorageSharedTest extends SqlStorageSharedTest {

    private String database;

    @BeforeEach
    void createDatabase() throws SQLException {
        database = "sdb_rsbtest_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(url(""), user(), password());
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE DATABASE " + database);
        }
    }

    @AfterEach
    void dropDatabase() throws SQLException {
        closeAll();
        try (Connection c = DriverManager.getConnection(url(""), user(), password());
             Statement st = c.createStatement()) {
            st.executeUpdate("DROP DATABASE IF EXISTS " + database);
        }
    }

    @Override
    SqlSettings settings(String serverId) {
        return new SqlSettings(SqlStorage.Type.MYSQL, url(database), SqlSettings.MYSQL_DRIVER, user(), password(),
                4, new Properties(), serverId, folder(serverId));
    }

    @Override
    Connection raw() throws SQLException {
        return DriverManager.getConnection(url(database), user(), password());
    }

    private static String url(String database) {
        return "jdbc:mysql://127.0.0.1:" + System.getenv("RSB_TEST_MYSQL_PORT") + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static String user() {
        return System.getenv().getOrDefault("RSB_TEST_MYSQL_USER", "root");
    }

    private static String password() {
        return System.getenv().getOrDefault("RSB_TEST_MYSQL_PASSWORD", "");
    }

    @Test
    void fourServersStartingTogetherMigrateOnce() throws Exception {
        AtomicInteger migrations = new AtomicInteger();
        List<SqlStorage> started = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String id = "s" + i;
            Logger logger = Logger.getLogger("rsb-test-boot-" + id + "-" + database);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record.getMessage() != null && record.getMessage().contains("migrated from version 0")) {
                        migrations.incrementAndGet();
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
            threads.add(new Thread(() -> started.add(tryOpen(id, logger))));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(4, started.stream().filter(java.util.Objects::nonNull).count());
        assertEquals(1, migrations.get());
        assertEquals(SqlStorage.SCHEMA_VERSION, schemaVersion());
    }

    @Test
    void hostLockFailsOverAfterTheTtl() throws Exception {
        SqlStorage a = open("a", java.time.Duration.ofSeconds(1));
        SqlStorage b = open("b", java.time.Duration.ofSeconds(1));
        UUID island = UUID.randomUUID();
        assertTrue(a.hosts().tryAcquireOrRenew(island));
        long start = System.nanoTime();
        while (!b.hosts().tryAcquireOrRenew(island)) {
            Thread.sleep(50);
            assertTrue(System.nanoTime() - start < 5_000_000_000L, "b never took over");
        }
        assertTrue(System.nanoTime() - start >= 700_000_000L, "b took over before the claim expired");
        assertEquals(0, a.hosts().renewAll());
        assertEquals("b", a.hosts().holderOf(island));
    }
}
