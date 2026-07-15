package com.mystipixel.royalskyblock.data;

import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.island.IslandMember;
import com.mystipixel.royalskyblock.island.IslandRole;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Persistence for island METADATA over one HikariCP data source, serving both SQLite (default, single
 * server) and MySQL (a shared island DB across a network). Mirrors the pattern used across the Royal
 * suite: the JDBC driver and pool come from Paper's library loader (see plugin.yml {@code libraries}).
 *
 * <p>Island blocks are not stored here — they live in the ASP slime data-source. This layer holds the
 * roster, home, level, radius and ownership. Save is transactional: the island row and its member
 * rows commit together or not at all.
 */
public final class IslandDatabase {

    public enum Type { SQLITE, MYSQL }

    private final JavaPlugin plugin;

    private Type type;
    private HikariDataSource dataSource;

    public IslandDatabase(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────

    public boolean connect() {
        try {
            ConfigurationSection storage = plugin.getConfig().getConfigurationSection("storage");
            if (storage == null) {
                storage = plugin.getConfig().createSection("storage");
            }
            String rawType = storage.getString("type", "SQLITE").toUpperCase(Locale.ROOT);
            this.type = "MYSQL".equals(rawType) ? Type.MYSQL : Type.SQLITE;

            HikariConfig hikari = new HikariConfig();
            hikari.setPoolName("RoyalSkyblock");

            if (type == Type.MYSQL) {
                ConfigurationSection my = storage.getConfigurationSection("mysql");
                if (my == null) {
                    my = storage.createSection("mysql");
                }
                String host = my.getString("host", "localhost");
                int port = my.getInt("port", 3306);
                String database = my.getString("database", "royalskyblock");
                String props = my.getString("properties", "useSSL=false");
                loadDriver("com.mysql.cj.jdbc.Driver");
                hikari.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?" + props);
                hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
                hikari.setUsername(my.getString("username", "root"));
                hikari.setPassword(my.getString("password", ""));
                hikari.setMaximumPoolSize(Math.max(1, my.getInt("pool-size", 10)));
            } else {
                File dataFolder = plugin.getDataFolder();
                if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                    plugin.getLogger().severe("Could not create plugin data folder: " + dataFolder.getAbsolutePath());
                    return false;
                }
                File databaseFile = new File(dataFolder, storage.getString("sqlite-file", "islands.db"));
                loadDriver("org.sqlite.JDBC");
                hikari.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
                hikari.setDriverClassName("org.sqlite.JDBC");
                hikari.setMaximumPoolSize(1);
                hikari.setConnectionInitSql(
                        "PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=5000; PRAGMA foreign_keys=ON;");
            }

            this.dataSource = new HikariDataSource(hikari);
            createTables();
            plugin.getLogger().info("RoyalSkyblock connected to " + type + " storage.");
            return true;
        } catch (Exception exception) {
            plugin.getLogger().severe("RoyalSkyblock storage init failed: " + exception.getMessage());
            return false;
        }
    }

    private void loadDriver(String driverClass) {
        try {
            Class.forName(driverClass, true, getClass().getClassLoader());
        } catch (ClassNotFoundException exception) {
            plugin.getLogger().log(Level.WARNING, "JDBC driver not found on classpath: " + driverClass, exception);
        }
    }

    private boolean mysql() {
        return type == Type.MYSQL;
    }

    private void createTables() throws SQLException {
        String islandsDdl = mysql() ? """
                CREATE TABLE IF NOT EXISTS islands (
                    id VARCHAR(36) PRIMARY KEY,
                    owner VARCHAR(36) NOT NULL,
                    world_name VARCHAR(255) NOT NULL,
                    created_at BIGINT NOT NULL,
                    radius INT NOT NULL DEFAULT 50,
                    level DOUBLE NOT NULL DEFAULT 0,
                    home_x DOUBLE NOT NULL DEFAULT 0,
                    home_y DOUBLE NOT NULL DEFAULT 0,
                    home_z DOUBLE NOT NULL DEFAULT 0,
                    home_yaw FLOAT NOT NULL DEFAULT 0,
                    home_pitch FLOAT NOT NULL DEFAULT 0,
                    KEY idx_islands_owner (owner)
                )
                """ : """
                CREATE TABLE IF NOT EXISTS islands (
                    id TEXT PRIMARY KEY,
                    owner TEXT NOT NULL,
                    world_name TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    radius INTEGER NOT NULL DEFAULT 50,
                    level REAL NOT NULL DEFAULT 0,
                    home_x REAL NOT NULL DEFAULT 0,
                    home_y REAL NOT NULL DEFAULT 0,
                    home_z REAL NOT NULL DEFAULT 0,
                    home_yaw REAL NOT NULL DEFAULT 0,
                    home_pitch REAL NOT NULL DEFAULT 0
                )
                """;
        String membersDdl = mysql() ? """
                CREATE TABLE IF NOT EXISTS island_members (
                    island_id VARCHAR(36) NOT NULL,
                    uuid VARCHAR(36) NOT NULL,
                    name VARCHAR(32) NOT NULL DEFAULT '',
                    role VARCHAR(16) NOT NULL,
                    joined_at BIGINT NOT NULL,
                    PRIMARY KEY (island_id, uuid),
                    KEY idx_island_members_uuid (uuid)
                )
                """ : """
                CREATE TABLE IF NOT EXISTS island_members (
                    island_id TEXT NOT NULL,
                    uuid TEXT NOT NULL,
                    name TEXT NOT NULL DEFAULT '',
                    role TEXT NOT NULL,
                    joined_at INTEGER NOT NULL,
                    PRIMARY KEY (island_id, uuid)
                )
                """;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(islandsDdl);
            statement.executeUpdate(membersDdl);
            if (!mysql()) {
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_islands_owner ON islands(owner)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_island_members_uuid ON island_members(uuid)");
            }
        }
    }

    // ── reads ───────────────────────────────────────────────────────────────────

    /** Load a full island (row + roster), or {@code null} if it does not exist. */
    public @Nullable Island getIsland(UUID id) {
        String sql = "SELECT id, owner, world_name, created_at, radius, level, "
                + "home_x, home_y, home_z, home_yaw, home_pitch FROM islands WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Island island = new Island(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("owner")),
                        rs.getString("world_name"),
                        rs.getLong("created_at"));
                island.setRadius(rs.getInt("radius"));
                island.setLevel(rs.getDouble("level"));
                island.setHome(rs.getDouble("home_x"), rs.getDouble("home_y"), rs.getDouble("home_z"),
                        rs.getFloat("home_yaw"), rs.getFloat("home_pitch"));
                loadMembers(connection, island);
                return island;
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not load island " + id + ": " + exception.getMessage());
            return null;
        }
    }

    private void loadMembers(Connection connection, Island island) throws SQLException {
        String sql = "SELECT uuid, name, role, joined_at FROM island_members WHERE island_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, island.id().toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    IslandRole role;
                    try {
                        role = IslandRole.valueOf(rs.getString("role"));
                    } catch (IllegalArgumentException badRole) {
                        role = IslandRole.MEMBER;
                    }
                    island.putMember(new IslandMember(
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("name"),
                            role,
                            rs.getLong("joined_at")));
                }
            }
        }
    }

    /** The id of the island owned by {@code owner}, or {@code null}. */
    public @Nullable UUID getIslandIdByOwner(UUID owner) {
        return querySingleId("SELECT id FROM islands WHERE owner = ?", owner);
    }

    /** The id of the island {@code member} belongs to (owner or member), or {@code null}. */
    public @Nullable UUID getIslandIdByMember(UUID member) {
        return querySingleId("SELECT island_id FROM island_members WHERE uuid = ? LIMIT 1", member);
    }

    private @Nullable UUID querySingleId(String sql, UUID param) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, param.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? UUID.fromString(rs.getString(1)) : null;
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Island id lookup failed for " + param + ": " + exception.getMessage());
            return null;
        }
    }

    // ── writes ──────────────────────────────────────────────────────────────────

    /**
     * Upsert an island and its full roster in one transaction. Members are replaced wholesale
     * (delete-then-insert), so removing a member is just leaving them out of {@link Island#members()}.
     */
    public boolean saveIsland(Island island) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(saveIslandSql())) {
                    bindIsland(statement, island);
                    statement.executeUpdate();
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM island_members WHERE island_id = ?")) {
                    delete.setString(1, island.id().toString());
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO island_members (island_id, uuid, name, role, joined_at) VALUES (?, ?, ?, ?, ?)")) {
                    for (IslandMember member : island.members()) {
                        insert.setString(1, island.id().toString());
                        insert.setString(2, member.uuid().toString());
                        insert.setString(3, member.name() == null ? "" : member.name());
                        insert.setString(4, member.role().name());
                        insert.setLong(5, member.joinedAt());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                plugin.getLogger().severe("Could not save island " + island.id() + ": " + exception.getMessage());
                return false;
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Island DB connection error (save): " + exception.getMessage());
            return false;
        }
    }

    /** Delete an island and its roster. World deletion is handled separately by the world service. */
    public boolean deleteIsland(UUID id) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement members = connection.prepareStatement(
                        "DELETE FROM island_members WHERE island_id = ?")) {
                    members.setString(1, id.toString());
                    members.executeUpdate();
                }
                try (PreparedStatement island = connection.prepareStatement(
                        "DELETE FROM islands WHERE id = ?")) {
                    island.setString(1, id.toString());
                    island.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                plugin.getLogger().severe("Could not delete island " + id + ": " + exception.getMessage());
                return false;
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Island DB connection error (delete): " + exception.getMessage());
            return false;
        }
    }

    private String saveIslandSql() {
        return mysql() ? """
                INSERT INTO islands (id, owner, world_name, created_at, radius, level,
                    home_x, home_y, home_z, home_yaw, home_pitch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    owner = VALUES(owner),
                    world_name = VALUES(world_name),
                    radius = VALUES(radius),
                    level = VALUES(level),
                    home_x = VALUES(home_x),
                    home_y = VALUES(home_y),
                    home_z = VALUES(home_z),
                    home_yaw = VALUES(home_yaw),
                    home_pitch = VALUES(home_pitch)
                """ : """
                INSERT INTO islands (id, owner, world_name, created_at, radius, level,
                    home_x, home_y, home_z, home_yaw, home_pitch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    owner = excluded.owner,
                    world_name = excluded.world_name,
                    radius = excluded.radius,
                    level = excluded.level,
                    home_x = excluded.home_x,
                    home_y = excluded.home_y,
                    home_z = excluded.home_z,
                    home_yaw = excluded.home_yaw,
                    home_pitch = excluded.home_pitch
                """;
    }

    private void bindIsland(PreparedStatement statement, Island island) throws SQLException {
        statement.setString(1, island.id().toString());
        statement.setString(2, island.owner().toString());
        statement.setString(3, island.worldName());
        statement.setLong(4, island.createdAt());
        statement.setInt(5, island.radius());
        statement.setDouble(6, island.level());
        statement.setDouble(7, island.homeX());
        statement.setDouble(8, island.homeY());
        statement.setDouble(9, island.homeZ());
        statement.setFloat(10, island.homeYaw());
        statement.setFloat(11, island.homePitch());
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException exception) {
            plugin.getLogger().severe("Could not roll back failed island write: " + exception.getMessage());
        }
    }

    private void restoreAutoCommit(Connection connection, boolean value) {
        try {
            connection.setAutoCommit(value);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not restore auto-commit: " + exception.getMessage());
        }
    }

    public void close() {
        if (dataSource == null) {
            return;
        }
        if (!mysql()) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (SQLException ignored) {
                // best effort
            }
        }
        dataSource.close();
        dataSource = null;
    }
}
