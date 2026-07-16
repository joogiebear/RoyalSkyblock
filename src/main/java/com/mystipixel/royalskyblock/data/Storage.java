package com.mystipixel.royalskyblock.data;

import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.island.IslandRole;
import com.mystipixel.royalskyblock.profile.Gamemode;
import com.mystipixel.royalskyblock.profile.Profile;
import com.mystipixel.royalskyblock.profile.ProfileData;
import com.mystipixel.royalskyblock.profile.ProfileMember;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The whole metadata store over one HikariCP data source (SQLite default / MySQL for a network) —
 * islands, profiles, coop rosters, each player's active profile, and per-profile saved state. Island
 * blocks live in the ASP slime data-source, not here. Matches the suite's dual-dialect pattern; the
 * JDBC driver + pool come from Paper's library loader.
 */
public final class Storage {

    public enum Type { SQLITE, MYSQL }

    private final JavaPlugin plugin;

    private Type type;
    private HikariDataSource dataSource;

    public Storage(JavaPlugin plugin) {
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
        String blob = mysql() ? "MEDIUMBLOB" : "BLOB";
        String big = mysql() ? "BIGINT" : "INTEGER";
        String dbl = mysql() ? "DOUBLE" : "REAL";
        String flt = mysql() ? "FLOAT" : "REAL";
        String txt = mysql() ? "VARCHAR(255)" : "TEXT";
        String txt36 = mysql() ? "VARCHAR(36)" : "TEXT";
        String txt32 = mysql() ? "VARCHAR(32)" : "TEXT";
        String txt16 = mysql() ? "VARCHAR(16)" : "TEXT";
        String integer = mysql() ? "INT" : "INTEGER";

        String[] ddl = {
                "CREATE TABLE IF NOT EXISTS islands ("
                        + "id " + txt36 + " PRIMARY KEY, profile_id " + txt36 + " NOT NULL, "
                        + "world_name " + txt + " NOT NULL, created_at " + big + " NOT NULL, "
                        + "radius " + integer + " NOT NULL DEFAULT 50, level " + dbl + " NOT NULL DEFAULT 0, "
                        + "home_x " + dbl + " NOT NULL DEFAULT 0, home_y " + dbl + " NOT NULL DEFAULT 0, "
                        + "home_z " + dbl + " NOT NULL DEFAULT 0, home_yaw " + flt + " NOT NULL DEFAULT 0, "
                        + "home_pitch " + flt + " NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS profiles ("
                        + "id " + txt36 + " PRIMARY KEY, owner " + txt36 + " NOT NULL, "
                        + "name " + txt32 + " NOT NULL, gamemode " + txt16 + " NOT NULL, "
                        + "created_at " + big + " NOT NULL)",
                "CREATE TABLE IF NOT EXISTS profile_members ("
                        + "profile_id " + txt36 + " NOT NULL, uuid " + txt36 + " NOT NULL, "
                        + "name " + txt32 + " NOT NULL DEFAULT '', role " + txt16 + " NOT NULL, "
                        + "joined_at " + big + " NOT NULL, PRIMARY KEY (profile_id, uuid))",
                "CREATE TABLE IF NOT EXISTS player_state ("
                        + "uuid " + txt36 + " PRIMARY KEY, active_profile " + txt36 + ")",
                "CREATE TABLE IF NOT EXISTS profile_data ("
                        + "profile_id " + txt36 + " PRIMARY KEY, inventory " + blob + ", ender_chest " + blob + ", "
                        + "exp_level " + integer + " NOT NULL DEFAULT 0, exp_progress " + flt + " NOT NULL DEFAULT 0, "
                        + "health " + dbl + " NOT NULL DEFAULT 20, food " + integer + " NOT NULL DEFAULT 20, "
                        + "saturation " + flt + " NOT NULL DEFAULT 5)"
        };
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String q : ddl) {
                s.executeUpdate(q);
            }
            s.executeUpdate(indexSql("idx_islands_profile", "islands", "profile_id"));
            s.executeUpdate(indexSql("idx_profiles_owner", "profiles", "owner"));
            s.executeUpdate(indexSql("idx_profile_members_uuid", "profile_members", "uuid"));
        }
    }

    private String indexSql(String name, String table, String column) {
        return "CREATE INDEX IF NOT EXISTS " + name + " ON " + table + "(" + column + ")";
    }

    // ── islands ────────────────────────────────────────────────────────────────

    public @Nullable Island getIsland(UUID id) {
        return queryIsland("WHERE id = ?", id.toString());
    }

    public @Nullable Island getIslandByProfile(UUID profileId) {
        return queryIsland("WHERE profile_id = ?", profileId.toString());
    }

    private @Nullable Island queryIsland(String where, String param) {
        String sql = "SELECT id, profile_id, world_name, created_at, radius, level, "
                + "home_x, home_y, home_z, home_yaw, home_pitch FROM islands " + where;
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, param);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Island island = new Island(UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("profile_id")),
                        rs.getString("world_name"), rs.getLong("created_at"));
                island.setRadius(rs.getInt("radius"));
                island.setLevel(rs.getDouble("level"));
                island.setHome(rs.getDouble("home_x"), rs.getDouble("home_y"), rs.getDouble("home_z"),
                        rs.getFloat("home_yaw"), rs.getFloat("home_pitch"));
                return island;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load island (" + where + "): " + e.getMessage());
            return null;
        }
    }

    public boolean saveIsland(Island island) {
        String sql = mysql()
                ? "INSERT INTO islands (id, profile_id, world_name, created_at, radius, level, home_x, home_y, home_z, home_yaw, home_pitch) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE profile_id=VALUES(profile_id), world_name=VALUES(world_name), "
                + "radius=VALUES(radius), level=VALUES(level), home_x=VALUES(home_x), home_y=VALUES(home_y), home_z=VALUES(home_z), "
                + "home_yaw=VALUES(home_yaw), home_pitch=VALUES(home_pitch)"
                : "INSERT INTO islands (id, profile_id, world_name, created_at, radius, level, home_x, home_y, home_z, home_yaw, home_pitch) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET profile_id=excluded.profile_id, world_name=excluded.world_name, "
                + "radius=excluded.radius, level=excluded.level, home_x=excluded.home_x, home_y=excluded.home_y, home_z=excluded.home_z, "
                + "home_yaw=excluded.home_yaw, home_pitch=excluded.home_pitch";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, island.id().toString());
            st.setString(2, island.profileId().toString());
            st.setString(3, island.worldName());
            st.setLong(4, island.createdAt());
            st.setInt(5, island.radius());
            st.setDouble(6, island.level());
            st.setDouble(7, island.homeX());
            st.setDouble(8, island.homeY());
            st.setDouble(9, island.homeZ());
            st.setFloat(10, island.homeYaw());
            st.setFloat(11, island.homePitch());
            st.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not save island " + island.id() + ": " + e.getMessage());
            return false;
        }
    }

    public boolean deleteIsland(UUID id) {
        return executeUpdate("DELETE FROM islands WHERE id = ?", id.toString());
    }

    // ── profiles ────────────────────────────────────────────────────────────────

    public @Nullable Profile getProfile(UUID id) {
        String sql = "SELECT id, owner, name, gamemode, created_at FROM profiles WHERE id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, id.toString());
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Profile profile = readProfile(rs);
                loadMembers(c, profile);
                Island island = getIslandByProfile(profile.id());
                if (island != null) {
                    profile.setIslandId(island.id());
                }
                return profile;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load profile " + id + ": " + e.getMessage());
            return null;
        }
    }

    public List<Profile> getProfilesByOwner(UUID owner) {
        List<Profile> out = new ArrayList<>();
        String sql = "SELECT id, owner, name, gamemode, created_at FROM profiles WHERE owner = ? ORDER BY created_at";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, owner.toString());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(readProfile(rs));
                }
            }
            for (Profile p : out) {
                loadMembers(c, p);
                Island island = getIslandByProfile(p.id());
                if (island != null) {
                    p.setIslandId(island.id());
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load profiles for " + owner + ": " + e.getMessage());
        }
        return out;
    }

    /** Profile ids a player is a member of (coop), including ones they don't own. */
    public List<UUID> getProfileIdsByMember(UUID uuid) {
        List<UUID> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT profile_id FROM profile_members WHERE uuid = ?")) {
            st.setString(1, uuid.toString());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(UUID.fromString(rs.getString(1)));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load member profiles for " + uuid + ": " + e.getMessage());
        }
        return out;
    }

    private Profile readProfile(ResultSet rs) throws SQLException {
        return new Profile(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("owner")),
                rs.getString("name"), Gamemode.fromString(rs.getString("gamemode"), Gamemode.SOLO),
                rs.getLong("created_at"));
    }

    private void loadMembers(Connection c, Profile profile) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
                "SELECT uuid, name, role, joined_at FROM profile_members WHERE profile_id = ?")) {
            st.setString(1, profile.id().toString());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    IslandRole role;
                    try {
                        role = IslandRole.valueOf(rs.getString("role"));
                    } catch (IllegalArgumentException bad) {
                        role = IslandRole.MEMBER;
                    }
                    profile.putMember(new ProfileMember(UUID.fromString(rs.getString("uuid")),
                            rs.getString("name"), role, rs.getLong("joined_at")));
                }
            }
        }
    }

    /** Upsert a profile and replace its member roster in one transaction. */
    public boolean saveProfile(Profile profile) {
        String upsert = mysql()
                ? "INSERT INTO profiles (id, owner, name, gamemode, created_at) VALUES (?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE owner=VALUES(owner), name=VALUES(name), gamemode=VALUES(gamemode)"
                : "INSERT INTO profiles (id, owner, name, gamemode, created_at) VALUES (?,?,?,?,?) "
                + "ON CONFLICT(id) DO UPDATE SET owner=excluded.owner, name=excluded.name, gamemode=excluded.gamemode";
        try (Connection c = dataSource.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement st = c.prepareStatement(upsert)) {
                    st.setString(1, profile.id().toString());
                    st.setString(2, profile.owner().toString());
                    st.setString(3, profile.name());
                    st.setString(4, profile.gamemode().name());
                    st.setLong(5, profile.createdAt());
                    st.executeUpdate();
                }
                try (PreparedStatement del = c.prepareStatement("DELETE FROM profile_members WHERE profile_id = ?")) {
                    del.setString(1, profile.id().toString());
                    del.executeUpdate();
                }
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO profile_members (profile_id, uuid, name, role, joined_at) VALUES (?,?,?,?,?)")) {
                    for (ProfileMember m : profile.members()) {
                        ins.setString(1, profile.id().toString());
                        ins.setString(2, m.uuid().toString());
                        ins.setString(3, m.name() == null ? "" : m.name());
                        ins.setString(4, m.role().name());
                        ins.setLong(5, m.joinedAt());
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                plugin.getLogger().severe("Could not save profile " + profile.id() + ": " + e.getMessage());
                return false;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Profile DB connection error (save): " + e.getMessage());
            return false;
        }
    }

    /** Delete a profile, its roster and its saved state. The island is deleted separately. */
    public boolean deleteProfile(UUID id) {
        try (Connection c = dataSource.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                for (String table : new String[]{"profile_members", "profile_data"}) {
                    try (PreparedStatement st = c.prepareStatement("DELETE FROM " + table + " WHERE profile_id = ?")) {
                        st.setString(1, id.toString());
                        st.executeUpdate();
                    }
                }
                try (PreparedStatement st = c.prepareStatement("DELETE FROM profiles WHERE id = ?")) {
                    st.setString(1, id.toString());
                    st.executeUpdate();
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                plugin.getLogger().severe("Could not delete profile " + id + ": " + e.getMessage());
                return false;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Profile DB connection error (delete): " + e.getMessage());
            return false;
        }
    }

    // ── active profile (player_state) ────────────────────────────────────────────

    public @Nullable UUID getActiveProfile(UUID player) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT active_profile FROM player_state WHERE uuid = ?")) {
            st.setString(1, player.toString());
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    String v = rs.getString("active_profile");
                    return v == null ? null : UUID.fromString(v);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load active profile for " + player + ": " + e.getMessage());
        }
        return null;
    }

    public void setActiveProfile(UUID player, UUID profileId) {
        String sql = mysql()
                ? "INSERT INTO player_state (uuid, active_profile) VALUES (?,?) ON DUPLICATE KEY UPDATE active_profile=VALUES(active_profile)"
                : "INSERT INTO player_state (uuid, active_profile) VALUES (?,?) ON CONFLICT(uuid) DO UPDATE SET active_profile=excluded.active_profile";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, player.toString());
            st.setString(2, profileId == null ? null : profileId.toString());
            st.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not set active profile for " + player + ": " + e.getMessage());
        }
    }

    // ── profile data (saved state) ────────────────────────────────────────────────

    public @Nullable ProfileData getProfileData(UUID profileId) {
        String sql = "SELECT inventory, ender_chest, exp_level, exp_progress, health, food, saturation "
                + "FROM profile_data WHERE profile_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, profileId.toString());
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ProfileData(rs.getBytes("inventory"), rs.getBytes("ender_chest"),
                        rs.getInt("exp_level"), rs.getFloat("exp_progress"), rs.getDouble("health"),
                        rs.getInt("food"), rs.getFloat("saturation"));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load profile data " + profileId + ": " + e.getMessage());
            return null;
        }
    }

    public boolean saveProfileData(UUID profileId, ProfileData data) {
        String sql = mysql()
                ? "INSERT INTO profile_data (profile_id, inventory, ender_chest, exp_level, exp_progress, health, food, saturation) "
                + "VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE inventory=VALUES(inventory), ender_chest=VALUES(ender_chest), "
                + "exp_level=VALUES(exp_level), exp_progress=VALUES(exp_progress), health=VALUES(health), food=VALUES(food), saturation=VALUES(saturation)"
                : "INSERT INTO profile_data (profile_id, inventory, ender_chest, exp_level, exp_progress, health, food, saturation) "
                + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(profile_id) DO UPDATE SET inventory=excluded.inventory, ender_chest=excluded.ender_chest, "
                + "exp_level=excluded.exp_level, exp_progress=excluded.exp_progress, health=excluded.health, food=excluded.food, saturation=excluded.saturation";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, profileId.toString());
            st.setBytes(2, data.inventory());
            st.setBytes(3, data.enderChest());
            st.setInt(4, data.expLevel());
            st.setFloat(5, data.expProgress());
            st.setDouble(6, data.health());
            st.setInt(7, data.food());
            st.setFloat(8, data.saturation());
            st.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not save profile data " + profileId + ": " + e.getMessage());
            return false;
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private boolean executeUpdate(String sql, String param) {
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, param);
            st.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("DB update failed (" + sql + "): " + e.getMessage());
            return false;
        }
    }

    public void close() {
        if (dataSource == null) {
            return;
        }
        if (!mysql()) {
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (SQLException ignored) {
                // best effort
            }
        }
        dataSource.close();
        dataSource = null;
    }
}
