package com.mystipixel.royalskyblock.data;

import java.util.Properties;

/**
 * Connection settings for the SQLite store, handed to the driver as connection properties so it
 * applies every one of them to every connection it opens.
 *
 * <p>They used to be a single {@code connectionInitSql} string of four {@code PRAGMA} statements.
 * sqlite-jdbc runs only the first statement of a multi-statement string, so journal mode was set and
 * the other three never were — {@code busy_timeout} included, which is what lets a writer wait for
 * another instead of failing with {@code SQLITE_BUSY}. That was harmless only while the pool held a
 * single connection.
 */
final class SqliteSettings {

    /** Milliseconds a connection waits for another's write lock before giving up. */
    static final int BUSY_TIMEOUT_MS = 5000;

    /**
     * Connections in the pool. WAL lets readers run alongside each other and alongside the one writer,
     * so a quick read on the server thread no longer queues behind a full-table scan or a large save
     * running in the background — which, with one connection, it did for up to the pool timeout.
     */
    static final int POOL_SIZE = 4;

    private SqliteSettings() {
    }

    static Properties properties() {
        Properties props = new Properties();
        props.setProperty("journal_mode", "WAL");
        props.setProperty("synchronous", "NORMAL");
        props.setProperty("busy_timeout", String.valueOf(BUSY_TIMEOUT_MS));
        props.setProperty("foreign_keys", "true");
        return props;
    }
}
