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
import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The SQL-backed {@link Storage}: SQLite by default, MySQL for a network, behind a HikariCP pool.
 * Holds islands, profiles, coop rosters, active profiles, per-profile saved state and banks; island
 * blocks live in the ASP slime data source. The JDBC driver and pool come from Paper's library loader.
 */
public final class SqlStorage implements Storage {

    public enum Type { SQLITE, MYSQL }

    private final RoyalSkyblockPlugin plugin;

    private Type type;
    private HikariDataSource dataSource;

    public SqlStorage(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean connect() {
        try {
            ConfigurationSection storage = plugin.conf().getConfigurationSection("storage");
            if (storage == null) {
                storage = plugin.conf().createSection("storage");
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
                hikari.setMaximumPoolSize(SqliteSettings.POOL_SIZE);
                hikari.setDataSourceProperties(SqliteSettings.properties());
            }
            // fail the call rather than hold the server thread; Hikari's 30s default could freeze the server
            // behind one stuck query
            hikari.setConnectionTimeout(10_000);

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
                        + "home_pitch " + flt + " NOT NULL DEFAULT 0, "
                        + "settings " + (mysql() ? "VARCHAR(512)" : "TEXT") + " NOT NULL DEFAULT '', "
                        + "guest_home " + (mysql() ? "VARCHAR(128)" : "TEXT") + " NOT NULL DEFAULT '', "
                        + "upgrades " + (mysql() ? "VARCHAR(512)" : "TEXT") + " NOT NULL DEFAULT '', "
                        + "reward_level " + integer + " NOT NULL DEFAULT 0, "
                        + "perk_level " + integer + " NOT NULL DEFAULT 0, "
                        + "unloaded_at " + big + " NOT NULL DEFAULT 0)",
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
                // keyed by (profile, player) so each coop member keeps their own inventory/progression
                "CREATE TABLE IF NOT EXISTS pending_upgrades ("
                        + "island_id " + txt36 + " NOT NULL, upgrade_key " + txt32 + " NOT NULL, "
                        + "target_tier " + integer + " NOT NULL, complete_at " + big + " NOT NULL, "
                        + "PRIMARY KEY (island_id, upgrade_key))",
                "CREATE TABLE IF NOT EXISTS profile_data ("
                        + "profile_id " + txt36 + " NOT NULL, player_uuid " + txt36 + " NOT NULL, "
                        + "inventory " + blob + ", ender_chest " + blob + ", "
                        + "exp_level " + integer + " NOT NULL DEFAULT 0, exp_progress " + flt + " NOT NULL DEFAULT 0, "
                        + "health " + dbl + " NOT NULL DEFAULT 20, food " + integer + " NOT NULL DEFAULT 20, "
                        + "saturation " + flt + " NOT NULL DEFAULT 5, PRIMARY KEY (profile_id, player_uuid))",
                "CREATE TABLE IF NOT EXISTS bank_accounts ("
                        + "account_id " + txt + " PRIMARY KEY, balance " + dbl + " NOT NULL DEFAULT 0, "
                        + "level " + integer + " NOT NULL DEFAULT 1, last_interest " + big + " NOT NULL DEFAULT 0)",
                // Former coop members still owed their savings and items (see Storage.addCoopPayout).
                "CREATE TABLE IF NOT EXISTS coop_payouts ("
                        + "player_uuid " + txt36 + " NOT NULL, profile_id " + txt36 + " NOT NULL, "
                        + "PRIMARY KEY (player_uuid, profile_id))",
                "CREATE TABLE IF NOT EXISTS bank_txns ("
                        + "id " + (mysql() ? "BIGINT PRIMARY KEY AUTO_INCREMENT" : "INTEGER PRIMARY KEY AUTOINCREMENT")
                        + ", account_id " + txt + " NOT NULL, type " + txt32 + " NOT NULL, amount " + dbl + " NOT NULL, "
                        + "balance_after " + dbl + " NOT NULL, created_at " + big + " NOT NULL, note " + txt + " NOT NULL DEFAULT '')"
        };
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String q : ddl) {
                s.executeUpdate(q);
            }
            createIndexIfMissing(c, "idx_islands_profile", "islands", "profile_id");
            createIndexIfMissing(c, "idx_profiles_owner", "profiles", "owner");
            createIndexIfMissing(c, "idx_profile_members_uuid", "profile_members", "uuid");
            // serves getBankTransactions (WHERE account_id = ? ORDER BY created_at DESC) without a full scan
            createIndexIfMissing(c, "idx_bank_txns_account_created", "bank_txns", "account_id, created_at");
        }
        // migrations for tables that predate a column
        addColumnIfMissing("islands", "settings", (mysql() ? "VARCHAR(512)" : "TEXT") + " NOT NULL DEFAULT ''");
        addColumnIfMissing("islands", "guest_home", (mysql() ? "VARCHAR(128)" : "TEXT") + " NOT NULL DEFAULT ''");
        addColumnIfMissing("islands", "upgrades", (mysql() ? "VARCHAR(512)" : "TEXT") + " NOT NULL DEFAULT ''");
        addColumnIfMissing("islands", "reward_level", (mysql() ? "INT" : "INTEGER") + " NOT NULL DEFAULT 0");
        addColumnIfMissing("islands", "perk_level", (mysql() ? "INT" : "INTEGER") + " NOT NULL DEFAULT 0");
        addColumnIfMissing("islands", "unloaded_at", (mysql() ? "BIGINT" : "INTEGER") + " NOT NULL DEFAULT 0");
        addColumnIfMissing("profiles", "reward_level", (mysql() ? "INT" : "INTEGER") + " NOT NULL DEFAULT 0");
        // -1 = not recorded yet, read as the balance (BankAccount.interestBase).
        addColumnIfMissing("bank_accounts", "interest_floor", dbl + " NOT NULL DEFAULT -1");
        // Per-profile personal bank snapshot (only used when RoyalBank is the backend).
        addColumnIfMissing("profile_data", "bank_saved", integer + " NOT NULL DEFAULT 0");
        addColumnIfMissing("profile_data", "bank_balance", dbl + " NOT NULL DEFAULT 0");
        addColumnIfMissing("profile_data", "bank_level", integer + " NOT NULL DEFAULT 1");
        addColumnIfMissing("profile_data", "bank_last_interest", big + " NOT NULL DEFAULT 0");
        addColumnIfMissing("profile_data", "bank_bonus", integer + " NOT NULL DEFAULT 0");
    }

    // ADD COLUMN unless the metadata says it exists, so it is safe to run every boot
    private void addColumnIfMissing(String table, String column, String definition) {
        try (Connection c = dataSource.getConnection()) {
            try (ResultSet rs = c.getMetaData().getColumns(null, null, table, column)) {
                if (rs.next()) {
                    return;                     // already there
                }
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                plugin.getLogger().info("Migrated " + table + ": added column " + column + ".");
            }
        } catch (SQLException e) {
            // a half-migrated schema breaks every read, so fail loudly
            plugin.getLogger().severe("Migration failed: could not add " + table + "." + column
                    + " (" + e.getMessage() + ")");
        }
    }

    // MySQL has no CREATE INDEX IF NOT EXISTS, so ask the metadata first. Also retrofits a table created
    // before the index existed.
    private void createIndexIfMissing(Connection c, String name, String table, String columns)
            throws SQLException {
        try (ResultSet rs = c.getMetaData().getIndexInfo(null, null, table, false, true)) {
            while (rs.next()) {
                if (name.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    return;
                }
            }
        }
        try (Statement s = c.createStatement()) {
            s.executeUpdate("CREATE INDEX " + name + " ON " + table + " (" + columns + ")");
        }
    }

    public @Nullable Island getIsland(UUID id) {
        return queryIsland("WHERE id = ?", id.toString());
    }

    public @Nullable Island getIslandByProfile(UUID profileId) {
        return queryIsland("WHERE profile_id = ?", profileId.toString());
    }

    // the columns readIsland expects, in one place so every query asks for all of them
    private static final String ISLAND_COLUMNS =
            "id, profile_id, world_name, created_at, radius, level, home_x, home_y, home_z, "
            + "home_yaw, home_pitch, settings, guest_home, upgrades, reward_level, perk_level, unloaded_at";

    private @Nullable Island queryIsland(String where, String param) {
        String sql = "SELECT " + ISLAND_COLUMNS + " FROM islands " + where;
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, param);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? readIsland(rs) : null;
            }
        } catch (SQLException e) {
            // not null: null means "no such island", and callers delete things on that answer
            throw new StorageException("Could not load island (" + where + "): " + e.getMessage(), e);
        }
    }

    private Island readIsland(ResultSet rs) throws SQLException {
        Island island = new Island(UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("profile_id")),
                rs.getString("world_name"), rs.getLong("created_at"));
        island.setRadius(rs.getInt("radius"));
        island.setLevel(rs.getDouble("level"));
        island.setHome(rs.getDouble("home_x"), rs.getDouble("home_y"), rs.getDouble("home_z"),
                rs.getFloat("home_yaw"), rs.getFloat("home_pitch"));
        island.loadSettings(rs.getString("settings"));
        island.loadGuestHome(rs.getString("guest_home"));
        island.loadUpgrades(rs.getString("upgrades"));
        island.setRewardLevel(rs.getInt("reward_level"));
        island.setPerkLevel(rs.getInt("perk_level"));
        island.setUnloadedAt(rs.getLong("unloaded_at"));
        return island;
    }

    public List<Island> getAllIslands() {
        List<Island> out = new ArrayList<>();
        String sql = "SELECT " + ISLAND_COLUMNS + " FROM islands";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(readIsland(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load islands: " + e.getMessage());
        }
        return out;
    }

    public boolean saveIsland(Island island) {
        String sql = mysql()
                ? "INSERT INTO islands (id, profile_id, world_name, created_at, radius, level, home_x, home_y, home_z, home_yaw, home_pitch, settings, guest_home, upgrades, reward_level, perk_level, unloaded_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE profile_id=VALUES(profile_id), world_name=VALUES(world_name), "
                + "radius=VALUES(radius), level=VALUES(level), home_x=VALUES(home_x), home_y=VALUES(home_y), home_z=VALUES(home_z), "
                + "home_yaw=VALUES(home_yaw), home_pitch=VALUES(home_pitch), settings=VALUES(settings), guest_home=VALUES(guest_home), upgrades=VALUES(upgrades), reward_level=VALUES(reward_level), perk_level=VALUES(perk_level), unloaded_at=VALUES(unloaded_at)"
                : "INSERT INTO islands (id, profile_id, world_name, created_at, radius, level, home_x, home_y, home_z, home_yaw, home_pitch, settings, guest_home, upgrades, reward_level, perk_level, unloaded_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET profile_id=excluded.profile_id, world_name=excluded.world_name, "
                + "radius=excluded.radius, level=excluded.level, home_x=excluded.home_x, home_y=excluded.home_y, home_z=excluded.home_z, "
                + "home_yaw=excluded.home_yaw, home_pitch=excluded.home_pitch, settings=excluded.settings, guest_home=excluded.guest_home, upgrades=excluded.upgrades, reward_level=excluded.reward_level, perk_level=excluded.perk_level, unloaded_at=excluded.unloaded_at";
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
            st.setString(12, island.serializeSettings());
            st.setString(13, island.serializeGuestHome());
            st.setString(14, island.serializeUpgrades());
            st.setInt(15, island.rewardLevel());
            st.setInt(16, island.perkLevel());
            st.setLong(17, island.unloadedAt());
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

    public @Nullable Profile getProfile(UUID id) {
        String sql = "SELECT id, owner, name, gamemode, created_at, reward_level FROM profiles WHERE id = ?";
        Profile profile;
        // Release this connection before looking up the island: that query opens its own, and nesting on
        // the single-connection SQLite pool would deadlock.
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, id.toString());
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                profile = readProfile(rs);
                loadMembers(c, profile);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load profile " + id + ": " + e.getMessage());
            return null;
        }
        Island island = getIslandByProfile(profile.id());
        if (island != null) {
            profile.setIslandId(island.id());
        }
        return profile;
    }

    /** Every profile a player owns, with members and island ids filled in. Three queries regardless of count. */
    public List<Profile> getProfilesByOwner(UUID owner) {
        List<Profile> out = new ArrayList<>();
        Map<UUID, Profile> byId = new HashMap<>();
        String sql = "SELECT id, owner, name, gamemode, created_at, reward_level FROM profiles WHERE owner = ? ORDER BY created_at";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, owner.toString());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    Profile p = readProfile(rs);
                    out.add(p);
                    byId.put(p.id(), p);
                }
            }
            if (!out.isEmpty()) {
                loadMembersFor(c, byId);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load profiles for " + owner + ": " + e.getMessage());
        }
        // island lookup after the connection above is released (see getProfile)
        if (!byId.isEmpty()) {
            attachIslandIds(byId);
        }
        return out;
    }

    // members for several profiles in one query
    private void loadMembersFor(Connection c, Map<UUID, Profile> profiles) throws SQLException {
        String sql = "SELECT profile_id, uuid, name, role, joined_at FROM profile_members WHERE profile_id IN ("
                + placeholders(profiles.size()) + ")";
        try (PreparedStatement st = c.prepareStatement(sql)) {
            bindUuids(st, profiles.keySet());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    Profile profile = profiles.get(UUID.fromString(rs.getString("profile_id")));
                    if (profile == null) {
                        continue;
                    }
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

    // set each profile's island id in one query; the full row is loaded on demand elsewhere
    private void attachIslandIds(Map<UUID, Profile> profiles) {
        String sql = "SELECT id, profile_id FROM islands WHERE profile_id IN ("
                + placeholders(profiles.size()) + ")";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            bindUuids(st, profiles.keySet());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    Profile profile = profiles.get(UUID.fromString(rs.getString("profile_id")));
                    if (profile != null) {
                        profile.setIslandId(UUID.fromString(rs.getString("id")));
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load island ids for " + profiles.size()
                    + " profile(s): " + e.getMessage());
        }
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static void bindUuids(PreparedStatement st, Collection<UUID> ids) throws SQLException {
        int index = 1;
        for (UUID id : ids) {
            st.setString(index++, id.toString());
        }
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
        Profile profile = new Profile(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("owner")),
                rs.getString("name"), Gamemode.fromString(rs.getString("gamemode"), Gamemode.SOLO),
                rs.getLong("created_at"));
        profile.setRewardLevel(rs.getInt("reward_level"));
        return profile;
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
                ? "INSERT INTO profiles (id, owner, name, gamemode, created_at, reward_level) VALUES (?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE owner=VALUES(owner), name=VALUES(name), gamemode=VALUES(gamemode), "
                + "reward_level=VALUES(reward_level)"
                : "INSERT INTO profiles (id, owner, name, gamemode, created_at, reward_level) VALUES (?,?,?,?,?,?) "
                + "ON CONFLICT(id) DO UPDATE SET owner=excluded.owner, name=excluded.name, gamemode=excluded.gamemode, "
                + "reward_level=excluded.reward_level";
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
                    st.setInt(6, profile.rewardLevel());
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
                try (PreparedStatement st = c.prepareStatement("DELETE FROM profile_members WHERE profile_id = ?")) {
                    st.setString(1, id.toString());
                    st.executeUpdate();
                }
                // Keep the rows of former members who haven't collected their payout yet: those rows
                // are the items they carried here, and deleting them is the loss the payout prevents.
                try (PreparedStatement st = c.prepareStatement("DELETE FROM profile_data WHERE profile_id = ? "
                        + "AND player_uuid NOT IN (SELECT player_uuid FROM coop_payouts WHERE profile_id = ?)")) {
                    st.setString(1, id.toString());
                    st.setString(2, id.toString());
                    st.executeUpdate();
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

    public List<com.mystipixel.royalskyblock.upgrade.PendingUpgrade> getAllPending() {
        List<com.mystipixel.royalskyblock.upgrade.PendingUpgrade> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "SELECT island_id, upgrade_key, target_tier, complete_at FROM pending_upgrades");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(new com.mystipixel.royalskyblock.upgrade.PendingUpgrade(
                        UUID.fromString(rs.getString("island_id")), rs.getString("upgrade_key"),
                        rs.getInt("target_tier"), rs.getLong("complete_at")));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load pending upgrades: " + e.getMessage());
        }
        return out;
    }

    public boolean savePending(com.mystipixel.royalskyblock.upgrade.PendingUpgrade p) {
        String sql = mysql()
                ? "INSERT INTO pending_upgrades (island_id, upgrade_key, target_tier, complete_at) VALUES (?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE target_tier=VALUES(target_tier), complete_at=VALUES(complete_at)"
                : "INSERT INTO pending_upgrades (island_id, upgrade_key, target_tier, complete_at) VALUES (?,?,?,?) "
                + "ON CONFLICT(island_id, upgrade_key) DO UPDATE SET target_tier=excluded.target_tier, complete_at=excluded.complete_at";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, p.islandId().toString());
            st.setString(2, p.upgradeKey());
            st.setInt(3, p.targetTier());
            st.setLong(4, p.completeAt());
            st.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not save pending upgrade: " + e.getMessage());
            return false;
        }
    }

    public void deletePending(UUID islandId, String upgradeKey) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "DELETE FROM pending_upgrades WHERE island_id = ? AND upgrade_key = ?")) {
            st.setString(1, islandId.toString());
            st.setString(2, upgradeKey);
            st.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not delete pending upgrade: " + e.getMessage());
        }
    }

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

    public @Nullable ProfileData getProfileData(UUID profileId, UUID playerUuid) {
        String sql = "SELECT inventory, ender_chest, exp_level, exp_progress, health, food, saturation "
                + "FROM profile_data WHERE profile_id = ? AND player_uuid = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, profileId.toString());
            st.setString(2, playerUuid.toString());
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ProfileData(rs.getBytes("inventory"), rs.getBytes("ender_chest"),
                        rs.getInt("exp_level"), rs.getFloat("exp_progress"), rs.getDouble("health"),
                        rs.getInt("food"), rs.getFloat("saturation"));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load profile data " + profileId + "/" + playerUuid + ": " + e.getMessage());
            return null;
        }
    }

    public boolean saveProfileData(UUID profileId, UUID playerUuid, ProfileData data) {
        String sql = mysql()
                ? "INSERT INTO profile_data (profile_id, player_uuid, inventory, ender_chest, exp_level, exp_progress, health, food, saturation) "
                + "VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE inventory=VALUES(inventory), ender_chest=VALUES(ender_chest), "
                + "exp_level=VALUES(exp_level), exp_progress=VALUES(exp_progress), health=VALUES(health), food=VALUES(food), saturation=VALUES(saturation)"
                : "INSERT INTO profile_data (profile_id, player_uuid, inventory, ender_chest, exp_level, exp_progress, health, food, saturation) "
                + "VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(profile_id, player_uuid) DO UPDATE SET inventory=excluded.inventory, ender_chest=excluded.ender_chest, "
                + "exp_level=excluded.exp_level, exp_progress=excluded.exp_progress, health=excluded.health, food=excluded.food, saturation=excluded.saturation";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, profileId.toString());
            st.setString(2, playerUuid.toString());
            st.setBytes(3, data.inventory());
            st.setBytes(4, data.enderChest());
            st.setInt(5, data.expLevel());
            st.setFloat(6, data.expProgress());
            st.setDouble(7, data.health());
            st.setInt(8, data.food());
            st.setFloat(9, data.saturation());
            st.executeUpdate();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not save profile data " + profileId + "/" + playerUuid + ": " + e.getMessage());
            return false;
        }
    }

    public com.mystipixel.royalskyblock.bank.@Nullable BankAccount getBankAccount(String accountId) {
        String sql = "SELECT balance, level, last_interest, interest_floor FROM bank_accounts WHERE account_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, accountId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? new com.mystipixel.royalskyblock.bank.BankAccount(
                        accountId, rs.getDouble("balance"), rs.getInt("level"), rs.getLong("last_interest"),
                        rs.getDouble("interest_floor")) : null;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load bank account " + accountId + ": " + e.getMessage());
            return null;
        }
    }

    /** Upsert an account and append its ledger row in one transaction. */
    public boolean saveBankAccountWithTxn(com.mystipixel.royalskyblock.bank.BankAccount account,
                                          String type, double amount, double balanceAfter, String note) {
        String upsert = mysql()
                ? "INSERT INTO bank_accounts (account_id, balance, level, last_interest, interest_floor) VALUES (?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE balance=VALUES(balance), level=VALUES(level), last_interest=VALUES(last_interest), "
                + "interest_floor=VALUES(interest_floor)"
                : "INSERT INTO bank_accounts (account_id, balance, level, last_interest, interest_floor) VALUES (?,?,?,?,?) "
                + "ON CONFLICT(account_id) DO UPDATE SET balance=excluded.balance, level=excluded.level, "
                + "last_interest=excluded.last_interest, interest_floor=excluded.interest_floor";
        String txn = "INSERT INTO bank_txns (account_id, type, amount, balance_after, created_at, note) VALUES (?,?,?,?,?,?)";
        try (Connection c = dataSource.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement st = c.prepareStatement(upsert)) {
                    st.setString(1, account.id());
                    st.setDouble(2, account.balance());
                    st.setInt(3, account.level());
                    st.setLong(4, account.lastInterest());
                    st.setDouble(5, account.interestFloor());
                    st.executeUpdate();
                }
                try (PreparedStatement st = c.prepareStatement(txn)) {
                    st.setString(1, account.id());
                    st.setString(2, type);
                    st.setDouble(3, amount);
                    st.setDouble(4, balanceAfter);
                    st.setLong(5, java.time.Instant.now().getEpochSecond());
                    st.setString(6, note == null ? "" : note);
                    st.executeUpdate();
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                try { c.rollback(); } catch (SQLException ignored) { }
                plugin.getLogger().severe("Could not save bank account " + account.id() + ": " + e.getMessage());
                return false;
            } finally {
                try { c.setAutoCommit(auto); } catch (SQLException ignored) { }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Bank DB connection error: " + e.getMessage());
            return false;
        }
    }

    public java.util.List<com.mystipixel.royalskyblock.bank.BankTxn> getBankTransactions(String accountId, int limit) {
        java.util.List<com.mystipixel.royalskyblock.bank.BankTxn> out = new java.util.ArrayList<>();
        String sql = "SELECT type, amount, balance_after, created_at, note FROM bank_txns "
                + "WHERE account_id = ? ORDER BY created_at DESC, id DESC LIMIT ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, accountId);
            st.setInt(2, Math.max(1, limit));
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(new com.mystipixel.royalskyblock.bank.BankTxn(rs.getString("type"), rs.getDouble("amount"),
                            rs.getDouble("balance_after"), rs.getLong("created_at"), rs.getString("note")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load bank transactions " + accountId + ": " + e.getMessage());
        }
        return out;
    }

    /** Record a coop payout owed to a player who left or was kicked; see {@link Storage#addCoopPayout}. */
    public void addCoopPayout(UUID player, UUID fromProfile) {
        String sql = (mysql() ? "INSERT IGNORE" : "INSERT OR IGNORE")
                + " INTO coop_payouts (player_uuid, profile_id) VALUES (?, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, player.toString());
            st.setString(2, fromProfile.toString());
            st.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not record coop payout " + fromProfile + " -> " + player + ": " + e.getMessage());
        }
    }

    @Override
    public List<UUID> getCoopPayouts(UUID player) {
        List<UUID> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT profile_id FROM coop_payouts WHERE player_uuid = ?")) {
            st.setString(1, player.toString());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(UUID.fromString(rs.getString("profile_id")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load coop payouts for " + player + ": " + e.getMessage());
        }
        return out;
    }

    @Override
    public void removeCoopPayout(UUID player, UUID fromProfile) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "DELETE FROM coop_payouts WHERE player_uuid = ? AND profile_id = ?")) {
            st.setString(1, player.toString());
            st.setString(2, fromProfile.toString());
            st.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not clear coop payout " + fromProfile + " -> " + player + ": " + e.getMessage());
        }
    }

    @Override
    public void deleteProfileData(UUID profileId, UUID playerUuid) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "DELETE FROM profile_data WHERE profile_id = ? AND player_uuid = ?")) {
            st.setString(1, profileId.toString());
            st.setString(2, playerUuid.toString());
            st.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not delete profile data " + profileId + "/" + playerUuid + ": " + e.getMessage());
        }
    }

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
