package com.mystipixel.royalskyblock.data;

import com.mystipixel.royalcommon.storage.Dialect;
import com.mystipixel.royalcommon.storage.TxRunner;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Which server hosts each island world, so two servers never have the same island loaded (the last one to
 * save would wipe the other's blocks). Table {@code rsb_island_hosts}: island_id, holder (server id),
 * expires_at (database time, epoch ms). A holder renews every island it has loaded; a crashed holder's
 * rows expire and any server may then take them.
 */
public final class IslandHostLock {

    public static final String TABLE = "rsb_island_hosts";
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(90);

    private final TxRunner db;
    private final String serverId;
    private final Duration ttl;

    public IslandHostLock(TxRunner db, String serverId, Duration ttl) {
        this.db = db;
        this.serverId = serverId;
        this.ttl = ttl;
    }

    public String serverId() {
        return serverId;
    }

    public Duration ttl() {
        return ttl;
    }

    public void createTable() throws SQLException {
        String ddl = db.dialect() == Dialect.MYSQL
                ? "CREATE TABLE IF NOT EXISTS " + TABLE + " (island_id VARCHAR(36) NOT NULL PRIMARY KEY,"
                + " holder VARCHAR(64) NOT NULL, expires_at BIGINT NOT NULL)"
                : "CREATE TABLE IF NOT EXISTS " + TABLE + " (island_id TEXT NOT NULL PRIMARY KEY,"
                + " holder TEXT NOT NULL, expires_at INTEGER NOT NULL)";
        db.inTransaction(tx -> {
            try (Statement statement = tx.connection().createStatement()) {
                statement.executeUpdate(ddl);
            }
            return null;
        });
    }

    /** Takes the island if nobody holds it or the holder's claim expired, or extends this server's claim. */
    public boolean tryAcquireOrRenew(UUID islandId) throws SQLException {
        String now = db.dialect().nowMillis();
        // the placeholder row is born expired, so the UPDATE is the only place an island is ever granted
        String insert = db.dialect().insertIgnore() + " INTO " + TABLE
                + " (island_id, holder, expires_at) VALUES (?, '', 0)";
        String claim = "UPDATE " + TABLE + " SET holder = ?, expires_at = " + now + " + ?"
                + " WHERE island_id = ? AND (holder = ? OR expires_at < " + now + ")";
        return db.inTransaction(tx -> {
            try (PreparedStatement statement = tx.connection().prepareStatement(insert)) {
                statement.setString(1, islandId.toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = tx.connection().prepareStatement(claim)) {
                statement.setString(1, serverId);
                statement.setLong(2, ttl.toMillis());
                statement.setString(3, islandId.toString());
                statement.setString(4, serverId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    /**
     * Extends every claim this server still has. A claim past its expiry that nobody else took is still ours:
     * taking it would have changed the holder.
     *
     * @return how many islands this server holds now
     */
    public int renewAll() throws SQLException {
        String sql = "UPDATE " + TABLE + " SET expires_at = " + db.dialect().nowMillis() + " + ? WHERE holder = ?";
        return db.inTransaction(tx -> {
            try (PreparedStatement statement = tx.connection().prepareStatement(sql)) {
                statement.setLong(1, ttl.toMillis());
                statement.setString(2, serverId);
                return statement.executeUpdate();
            }
        });
    }

    /** The islands this server holds. */
    public Set<UUID> heldBy() throws SQLException {
        Set<UUID> held = new HashSet<>();
        try (Connection connection = db.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT island_id FROM " + TABLE + " WHERE holder = ?")) {
            statement.setString(1, serverId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    held.add(UUID.fromString(rows.getString(1)));
                }
            }
        }
        return held;
    }

    /** The server hosting the island, or null when nobody holds an unexpired claim. */
    public @Nullable String holderOf(UUID islandId) throws SQLException {
        String sql = "SELECT holder FROM " + TABLE + " WHERE island_id = ? AND holder <> '' AND expires_at >= "
                + db.dialect().nowMillis();
        try (Connection connection = db.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, islandId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }

    /** Gives the island up so another server can host it at once. Does nothing unless this server holds it. */
    public void release(UUID islandId) throws SQLException {
        String sql = "UPDATE " + TABLE + " SET holder = '', expires_at = 0 WHERE island_id = ? AND holder = ?";
        db.inTransaction(tx -> {
            try (PreparedStatement statement = tx.connection().prepareStatement(sql)) {
                statement.setString(1, islandId.toString());
                statement.setString(2, serverId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** Gives up every island this server holds: at boot (left over from a crash) and at shutdown. */
    public int releaseAll() throws SQLException {
        String sql = "UPDATE " + TABLE + " SET holder = '', expires_at = 0 WHERE holder = ?";
        return db.inTransaction(tx -> {
            try (PreparedStatement statement = tx.connection().prepareStatement(sql)) {
                statement.setString(1, serverId);
                return statement.executeUpdate();
            }
        });
    }
}
