package com.mystipixel.royalskyblock.data;

import java.util.Properties;

// Handed to the driver as connection properties so every connection gets all of them; sqlite-jdbc
// runs only the first statement of a multi-statement connectionInitSql.
final class SqliteSettings {

    // ms a connection waits for another's write lock before giving up
    static final int BUSY_TIMEOUT_MS = 5000;

    // WAL lets reads run alongside each other and the one writer, so a server-thread read doesn't queue behind a save
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
