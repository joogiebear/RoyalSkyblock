package com.mystipixel.royalskyblock.data;

import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.island.IslandRole;
import com.mystipixel.royalskyblock.profile.Gamemode;
import com.mystipixel.royalskyblock.profile.Profile;
import com.mystipixel.royalskyblock.profile.ProfileData;
import com.mystipixel.royalskyblock.profile.ProfileMember;
import com.mystipixel.royalskyblock.upgrade.PendingUpgrade;
import com.mystipixel.royalcommon.storage.PlayerHandoff;
import com.mystipixel.royalcommon.storage.SchemaTooNewException;
import com.mystipixel.royalcommon.storage.SchemaVersion;
import com.mystipixel.royalcommon.storage.TxRunner;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The SQL-backed {@link Storage}: SQLite by default, MySQL for a network, behind a HikariCP pool.
 * Holds islands, profiles, coop rosters, active profiles, per-profile saved state and banks; island
 * blocks live in the ASP slime data source. The JDBC driver and pool come from Paper's library loader.
 */
public final class SqlStorage implements Storage {

    public enum Type { SQLITE, MYSQL }

    private final SqlSettings settings;
    private final Logger logger;

    static final int SCHEMA_VERSION = 1;
    /** Seconds a MySQL statement waits for a row lock another connection holds. */
    static final int LOCK_WAIT_SECONDS = 5;
    // a player's row may sit ACTIVE this long without a heartbeat before another server treats it as abandoned
    public static final Duration HANDOFF_STALE = Duration.ofSeconds(60);

    private Type type;
    private HikariDataSource dataSource;
    private TxRunner tx;
    // bank movements run on the server thread, so a lock wait is tried once and then refused, not retried
    private TxRunner bankTx;
    private PlayerHandoff handoff;
    private IslandHostLock hosts;
    private Duration hostTtl = IslandHostLock.DEFAULT_TTL;

    public SqlStorage(SqlSettings settings, Logger logger) {
        this.settings = settings;
        this.logger = logger;
    }

    /**
     * Whether other servers can share this database. SQLite is one file on one server, so nothing it caches
     * can change behind its back.
     */
    public boolean shared() {
        return type == Type.MYSQL;
    }

    /** For tests: a shorter island host claim. Call before {@link #connect}. */
    void hostTtl(Duration ttl) {
        this.hostTtl = ttl;
    }

    public boolean connect() {
        try {
            this.type = settings.type();
            HikariConfig hikari = new HikariConfig();
            hikari.setPoolName("RoyalSkyblock");
            if (type == Type.SQLITE) {
                File dataFolder = settings.dataFolder();
                if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                    logger.severe("Could not create plugin data folder: " + dataFolder.getAbsolutePath());
                    return false;
                }
            }
            loadDriver(settings.driver());
            hikari.setJdbcUrl(settings.jdbcUrl());
            hikari.setDriverClassName(settings.driver());
            if (settings.username() != null) {
                hikari.setUsername(settings.username());
                hikari.setPassword(settings.password());
            }
            hikari.setMaximumPoolSize(settings.poolSize());
            if (!settings.dataSourceProps().isEmpty()) {
                hikari.setDataSourceProperties(settings.dataSourceProps());
            }
            // fail the call rather than hold the server thread; Hikari's 30s default could freeze the server
            // behind one stuck query
            hikari.setConnectionTimeout(10_000);
            if (type == Type.MYSQL) {
                // a row lock held by another server fails fast instead of after InnoDB's 50 s default
                hikari.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout = " + LOCK_WAIT_SECONDS);
            }

            this.dataSource = new HikariDataSource(hikari);
            this.tx = new TxRunner(dataSource);
            this.bankTx = type == Type.MYSQL ? new TxRunner(dataSource, 1) : tx;
            migrate();
            this.handoff = new PlayerHandoff(tx, "rsb", settings.serverId(), HANDOFF_STALE);
            handoff.createTable();
            this.hosts = new IslandHostLock(tx, settings.serverId(), hostTtl);
            hosts.createTable();
            logger.info("RoyalSkyblock connected to " + type + " storage as server " + settings.serverId() + ".");
            return true;
        } catch (SchemaTooNewException tooNew) {
            logger.severe(tooNew.getMessage());
            closePool();
            return false;
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "RoyalSkyblock storage init failed: " + exception.getMessage(), exception);
            closePool();
            return false;
        }
    }

    private void closePool() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    // servers sharing a MySQL database that start together take turns here; the later ones find nothing to do
    private void migrate() throws SQLException {
        int before = new SchemaVersion(tx, "rsb").ensure(SCHEMA_VERSION, (c, dialect, from, to) -> {
            if (from < 1) {
                createTables(c);
            }
        });
        if (before < SCHEMA_VERSION) {
            logger.info("Database schema migrated from version " + before + " to " + SCHEMA_VERSION + ".");
        }
    }

    public TxRunner tx() {
        return tx;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public PlayerHandoff handoff() {
        return handoff;
    }

    public IslandHostLock hosts() {
        return hosts;
    }

    public String serverId() {
        return settings.serverId();
    }

    private void loadDriver(String driverClass) {
        try {
            Class.forName(driverClass, true, getClass().getClassLoader());
        } catch (ClassNotFoundException exception) {
            logger.log(Level.WARNING, "JDBC driver not found on classpath: " + driverClass, exception);
        }
    }

    private boolean mysql() {
        return type == Type.MYSQL;
    }

    // Every step is safe to repeat: on MySQL the migration runs in autocommit, so a server that dies half way
    // leaves some steps done.
    private void createTables(Connection c) throws SQLException {
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
        try (Statement s = c.createStatement()) {
            for (String q : ddl) {
                s.executeUpdate(q);
            }
        }
        createIndexIfMissing(c, "idx_islands_profile", "islands", "profile_id");
        createIndexIfMissing(c, "idx_profiles_owner", "profiles", "owner");
        createIndexIfMissing(c, "idx_profile_members_uuid", "profile_members", "uuid");
        // serves getBankTransactions (WHERE account_id = ? ORDER BY created_at DESC) without a full scan
        createIndexIfMissing(c, "idx_bank_txns_account_created", "bank_txns", "account_id, created_at");
        createIndexIfMissing(c, "idx_pending_complete", "pending_upgrades", "complete_at");
        // migrations for tables that predate a column
        addColumnIfMissing(c, "islands", "settings", (mysql() ? "VARCHAR(512)" : "TEXT") + " NOT NULL DEFAULT ''");
        addColumnIfMissing(c, "islands", "guest_home", (mysql() ? "VARCHAR(128)" : "TEXT") + " NOT NULL DEFAULT ''");
        addColumnIfMissing(c, "islands", "upgrades", (mysql() ? "VARCHAR(512)" : "TEXT") + " NOT NULL DEFAULT ''");
        addColumnIfMissing(c, "islands", "reward_level", (mysql() ? "INT" : "INTEGER") + " NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "islands", "perk_level", (mysql() ? "INT" : "INTEGER") + " NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "islands", "unloaded_at", (mysql() ? "BIGINT" : "INTEGER") + " NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "profiles", "reward_level", (mysql() ? "INT" : "INTEGER") + " NOT NULL DEFAULT 0");
        // -1 = not recorded yet, read as the balance (BankAccount.interestBase).
        addColumnIfMissing(c, "bank_accounts", "interest_floor", dbl + " NOT NULL DEFAULT -1");
        // Unused: left from the RoyalBank-backed personal bank; balances now live in bank_accounts.
        addColumnIfMissing(c, "profile_data", "bank_saved", integer + " NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "profile_data", "bank_balance", dbl + " NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "profile_data", "bank_level", integer + " NOT NULL DEFAULT 1");
        addColumnIfMissing(c, "profile_data", "bank_last_interest", big + " NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "profile_data", "bank_bonus", integer + " NOT NULL DEFAULT 0");
        // raised by every write, so other servers sharing the database notice the row changed
        addColumnIfMissing(c, "islands", "version", big + " NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "profiles", "version", big + " NOT NULL DEFAULT 0");
    }

    // ADD COLUMN unless the metadata says it exists. Throws: a half-migrated schema breaks every read, and the
    // schema version must only be stamped once every step worked.
    private void addColumnIfMissing(Connection c, String table, String column, String definition)
            throws SQLException {
        try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, table, column)) {
            if (rs.next()) {
                return;
            }
        }
        try (Statement s = c.createStatement()) {
            s.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            logger.info("Migrated " + table + ": added column " + column + ".");
        }
    }

    // MySQL has no CREATE INDEX IF NOT EXISTS, so ask the metadata first. Also retrofits a table created
    // before the index existed.
    private void createIndexIfMissing(Connection c, String name, String table, String columns)
            throws SQLException {
        try (ResultSet rs = c.getMetaData().getIndexInfo(c.getCatalog(), null, table, false, true)) {
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
            + "home_yaw, home_pitch, settings, guest_home, upgrades, reward_level, perk_level, unloaded_at, version";

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
        island.setVersion(rs.getLong("version"));
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
            logger.severe("Could not load islands: " + e.getMessage());
        }
        return out;
    }

    public boolean insertIsland(Island island) {
        String sql = "INSERT INTO islands (id, profile_id, world_name, created_at, radius, level, home_x, home_y, home_z, "
                + "home_yaw, home_pitch, settings, guest_home, upgrades, reward_level, perk_level, unloaded_at, version) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)";
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
            logger.severe("Could not save island " + island.id() + ": " + e.getMessage());
            return false;
        }
    }

    private String greatest() {
        return mysql() ? "GREATEST" : "MAX";
    }

    // UPDATE islands SET <set>, version = version + 1 WHERE id = ?, then the new version; -1 if no row or failed
    private long updateIsland(UUID id, String what, String set, Object... values) {
        try {
            return tx.inTransaction(t -> {
                Connection c = t.connection();
                try (PreparedStatement st = c.prepareStatement(
                        "UPDATE islands SET " + set + ", version = version + 1 WHERE id = ?")) {
                    int i = 1;
                    for (Object value : values) {
                        st.setObject(i++, value);
                    }
                    st.setString(i, id.toString());
                    if (st.executeUpdate() == 0) {
                        return -1L;
                    }
                }
                return versionOf(c, "islands", id);
            });
        } catch (SQLException e) {
            logger.severe("Could not save the " + what + " of island " + id + ": " + e.getMessage());
            return -1;
        }
    }

    private static long versionOf(Connection c, String table, UUID id) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT version FROM " + table + " WHERE id = ?")) {
            st.setString(1, id.toString());
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    @Override
    public long updateIslandHome(UUID id, double x, double y, double z, float yaw, float pitch) {
        return updateIsland(id, "home", "home_x = ?, home_y = ?, home_z = ?, home_yaw = ?, home_pitch = ?",
                x, y, z, yaw, pitch);
    }

    @Override
    public long updateIslandGuestHome(UUID id, String serializedGuestHome) {
        return updateIsland(id, "guest home", "guest_home = ?", serializedGuestHome);
    }

    @Override
    public long updateIslandLevel(UUID id, double level, int rewardLevel) {
        return updateIsland(id, "level", "level = ?, reward_level = " + greatest() + "(reward_level, ?)",
                level, rewardLevel);
    }

    @Override
    public long updateIslandPerkLevel(UUID id, int perkLevel) {
        return updateIsland(id, "perk level", "perk_level = " + greatest() + "(perk_level, ?)", perkLevel);
    }

    @Override
    public long updateIslandUnloadedAt(UUID id, long unloadedAt) {
        return updateIsland(id, "unload time", "unloaded_at = ?", unloadedAt);
    }

    @Override
    public long claimUnloadStamp(UUID id) {
        try {
            return tx.inTransaction(t -> {
                Connection c = t.connection();
                long stamp;
                try (PreparedStatement st = c.prepareStatement(
                        "SELECT unloaded_at FROM islands WHERE id = ?" + t.dialect().forUpdate())) {
                    st.setString(1, id.toString());
                    try (ResultSet rs = st.executeQuery()) {
                        stamp = rs.next() ? rs.getLong(1) : 0;
                    }
                }
                if (stamp <= 0) {
                    return 0L;
                }
                try (PreparedStatement st = c.prepareStatement("UPDATE islands SET unloaded_at = 0, "
                        + "version = version + 1 WHERE id = ? AND unloaded_at = ?")) {
                    st.setString(1, id.toString());
                    st.setLong(2, stamp);
                    return st.executeUpdate() > 0 ? stamp : 0L;
                }
            });
        } catch (SQLException e) {
            logger.severe("Could not read the unload time of island " + id + ": " + e.getMessage());
            return 0;
        }
    }

    @Override
    public long updateIslandUpgradeTier(UUID id, String upgradeKey, int tier, @Nullable Integer radius) {
        return mergeIslandColumn(id, "upgrades", current -> Island.withUpgradeTier(current, upgradeKey, tier),
                radius);
    }

    @Override
    public long updateIslandSetting(UUID id, String settingKey, boolean enabled) {
        return mergeIslandColumn(id, "settings", current -> Island.withSetting(current, settingKey, enabled), null);
    }

    // a locked read-modify-write of one serialized map column, so a key set elsewhere meanwhile is kept
    private long mergeIslandColumn(UUID id, String column, java.util.function.UnaryOperator<String> merge,
                                   @Nullable Integer radius) {
        try {
            return tx.inTransaction(t -> {
                Connection c = t.connection();
                String current;
                try (PreparedStatement st = c.prepareStatement(
                        "SELECT " + column + " FROM islands WHERE id = ?" + t.dialect().forUpdate())) {
                    st.setString(1, id.toString());
                    try (ResultSet rs = st.executeQuery()) {
                        if (!rs.next()) {
                            return -1L;
                        }
                        current = rs.getString(1);
                    }
                }
                String sql = "UPDATE islands SET " + column + " = ?" + (radius != null ? ", radius = ?" : "")
                        + ", version = version + 1 WHERE id = ?";
                try (PreparedStatement st = c.prepareStatement(sql)) {
                    int i = 1;
                    st.setString(i++, merge.apply(current));
                    if (radius != null) {
                        st.setInt(i++, radius);
                    }
                    st.setString(i, id.toString());
                    st.executeUpdate();
                }
                return versionOf(c, "islands", id);
            });
        } catch (SQLException e) {
            logger.severe("Could not save the " + column + " of island " + id + ": " + e.getMessage());
            return -1;
        }
    }

    public boolean deleteIsland(UUID id) {
        return executeUpdate("DELETE FROM islands WHERE id = ?", id.toString());
    }

    public @Nullable Profile getProfile(UUID id) {
        String sql = "SELECT id, owner, name, gamemode, created_at, reward_level, version FROM profiles WHERE id = ?";
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
            logger.severe("Could not load profile " + id + ": " + e.getMessage());
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
        String sql = "SELECT id, owner, name, gamemode, created_at, reward_level, version FROM profiles WHERE owner = ? ORDER BY created_at";
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
            logger.severe("Could not load profiles for " + owner + ": " + e.getMessage());
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
            logger.severe("Could not load island ids for " + profiles.size()
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
            logger.severe("Could not load member profiles for " + uuid + ": " + e.getMessage());
        }
        return out;
    }

    private Profile readProfile(ResultSet rs) throws SQLException {
        Profile profile = new Profile(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("owner")),
                rs.getString("name"), Gamemode.fromString(rs.getString("gamemode"), Gamemode.SOLO),
                rs.getLong("created_at"));
        profile.setRewardLevel(rs.getInt("reward_level"));
        profile.setVersion(rs.getLong("version"));
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

    /** Insert a new profile and its roster in one transaction. */
    public boolean insertProfile(Profile profile) {
        try {
            return tx.inTransaction(t -> {
                Connection c = t.connection();
                try (PreparedStatement st = c.prepareStatement("INSERT INTO profiles "
                        + "(id, owner, name, gamemode, created_at, reward_level, version) VALUES (?,?,?,?,?,?,0)")) {
                    st.setString(1, profile.id().toString());
                    st.setString(2, profile.owner().toString());
                    st.setString(3, profile.name());
                    st.setString(4, profile.gamemode().name());
                    st.setLong(5, profile.createdAt());
                    st.setInt(6, profile.rewardLevel());
                    st.executeUpdate();
                }
                for (ProfileMember m : profile.members()) {
                    insertMember(c, profile.id(), m);
                }
                return true;
            });
        } catch (SQLException e) {
            logger.severe("Could not save profile " + profile.id() + ": " + e.getMessage());
            return false;
        }
    }

    private static void insertMember(Connection c, UUID profileId, ProfileMember m) throws SQLException {
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO profile_members (profile_id, uuid, name, role, joined_at) VALUES (?,?,?,?,?)")) {
            ins.setString(1, profileId.toString());
            ins.setString(2, m.uuid().toString());
            ins.setString(3, m.name() == null ? "" : m.name());
            ins.setString(4, m.role().name());
            ins.setLong(5, m.joinedAt());
            ins.executeUpdate();
        }
    }

    private static void bumpProfile(Connection c, UUID id) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("UPDATE profiles SET version = version + 1 WHERE id = ?")) {
            st.setString(1, id.toString());
            st.executeUpdate();
        }
    }

    // runs work with the profile row locked; false when the row is gone, the work says so, or the write failed
    private boolean withProfileLocked(UUID id, String what, TxRunner.Work<Boolean> work) {
        try {
            return tx.inTransaction(t -> {
                if (!t.lock("profiles", "id", id.toString()) || !work.run(t)) {
                    return false;
                }
                bumpProfile(t.connection(), id);
                return true;
            });
        } catch (SQLException e) {
            logger.severe("Could not " + what + " on profile " + id + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public AddMemberResult addMember(UUID profileId, ProfileMember member, int maxMembers) {
        try {
            return tx.inTransaction(t -> {
                Connection c = t.connection();
                if (!t.lock("profiles", "id", profileId.toString())) {
                    return AddMemberResult.FAILED;
                }
                try (PreparedStatement st = c.prepareStatement(
                        "SELECT COUNT(*), SUM(CASE WHEN uuid = ? THEN 1 ELSE 0 END) FROM profile_members WHERE profile_id = ?")) {
                    st.setString(1, member.uuid().toString());
                    st.setString(2, profileId.toString());
                    try (ResultSet rs = st.executeQuery()) {
                        rs.next();
                        if (rs.getInt(2) > 0) {
                            return AddMemberResult.ALREADY;
                        }
                        if (rs.getInt(1) >= maxMembers) {
                            return AddMemberResult.FULL;
                        }
                    }
                }
                insertMember(c, profileId, member);
                bumpProfile(c, profileId);
                return AddMemberResult.ADDED;
            });
        } catch (SQLException e) {
            logger.severe("Could not add " + member.uuid() + " to profile " + profileId + ": " + e.getMessage());
            return AddMemberResult.FAILED;
        }
    }

    @Override
    public boolean removeMember(UUID profileId, UUID member) {
        return withProfileLocked(profileId, "remove member " + member, t -> {
            try (PreparedStatement st = t.connection().prepareStatement(
                    "DELETE FROM profile_members WHERE profile_id = ? AND uuid = ?")) {
                st.setString(1, profileId.toString());
                st.setString(2, member.toString());
                st.executeUpdate();
            }
            return true;
        });
    }

    private static boolean setRole(Connection c, UUID profileId, UUID member, IslandRole role) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
                "UPDATE profile_members SET role = ? WHERE profile_id = ? AND uuid = ?")) {
            st.setString(1, role.name());
            st.setString(2, profileId.toString());
            st.setString(3, member.toString());
            return st.executeUpdate() > 0;
        }
    }

    @Override
    public boolean setMemberRole(UUID profileId, UUID member, IslandRole role) {
        return withProfileLocked(profileId, "change the role of " + member,
                t -> setRole(t.connection(), profileId, member, role));
    }

    @Override
    public boolean transferOwner(UUID profileId, UUID newOwner, UUID oldOwner) {
        return withProfileLocked(profileId, "transfer ownership to " + newOwner, t -> {
            Connection c = t.connection();
            if (!setRole(c, profileId, newOwner, IslandRole.OWNER)) {
                return false;                               // not on the roster any more: roll back
            }
            setRole(c, profileId, oldOwner, IslandRole.CO_OWNER);
            try (PreparedStatement st = c.prepareStatement("UPDATE profiles SET owner = ? WHERE id = ?")) {
                st.setString(1, newOwner.toString());
                st.setString(2, profileId.toString());
                st.executeUpdate();
            }
            return true;
        });
    }

    @Override
    public long updateProfileRewardLevel(UUID id, int rewardLevel) {
        try {
            return tx.inTransaction(t -> {
                Connection c = t.connection();
                try (PreparedStatement st = c.prepareStatement("UPDATE profiles SET reward_level = " + greatest()
                        + "(reward_level, ?), version = version + 1 WHERE id = ?")) {
                    st.setInt(1, rewardLevel);
                    st.setString(2, id.toString());
                    if (st.executeUpdate() == 0) {
                        return -1L;
                    }
                }
                return versionOf(c, "profiles", id);
            });
        } catch (SQLException e) {
            logger.severe("Could not save the reward level on profile " + id + ": " + e.getMessage());
            return -1;
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
                logger.severe("Could not delete profile " + id + ": " + e.getMessage());
                return false;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            logger.severe("Profile DB connection error (delete): " + e.getMessage());
            return false;
        }
    }

    private static final String PENDING_COLUMNS = "island_id, upgrade_key, target_tier, complete_at";

    @Override
    public ReserveResult reservePending(PendingUpgrade p, int expectedTier) {
        try {
            return tx.inTransaction(t -> {
                Connection c = t.connection();
                String upgrades;
                try (PreparedStatement st = c.prepareStatement(
                        "SELECT upgrades FROM islands WHERE id = ?" + t.dialect().forUpdate())) {
                    st.setString(1, p.islandId().toString());
                    try (ResultSet rs = st.executeQuery()) {
                        if (!rs.next()) {
                            return ReserveResult.FAILED;
                        }
                        upgrades = rs.getString(1);
                    }
                }
                if (Island.upgradeTierIn(upgrades, p.upgradeKey()) != expectedTier) {
                    return ReserveResult.TIER_CHANGED;
                }
                try (PreparedStatement st = c.prepareStatement(t.dialect().insertIgnore()
                        + " INTO pending_upgrades (" + PENDING_COLUMNS + ") VALUES (?,?,?,?)")) {
                    st.setString(1, p.islandId().toString());
                    st.setString(2, p.upgradeKey());
                    st.setInt(3, p.targetTier());
                    st.setLong(4, p.completeAt());
                    return st.executeUpdate() > 0 ? ReserveResult.RESERVED : ReserveResult.IN_PROGRESS;
                }
            });
        } catch (SQLException e) {
            logger.severe("Could not reserve upgrade " + p.upgradeKey() + " on island " + p.islandId() + ": "
                    + e.getMessage());
            return ReserveResult.FAILED;
        }
    }

    @Override
    public List<PendingUpgrade> getPending(UUID islandId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "SELECT " + PENDING_COLUMNS + " FROM pending_upgrades WHERE island_id = ?")) {
            st.setString(1, islandId.toString());
            return readPendingRows(st);
        } catch (SQLException e) {
            logger.severe("Could not load the upgrades cooking on island " + islandId + ": " + e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<PendingUpgrade> duePending(long now, Collection<UUID> islandIds) {
        List<PendingUpgrade> out = new ArrayList<>();
        List<UUID> ids = List.copyOf(islandIds);
        try (Connection c = dataSource.getConnection()) {
            for (int from = 0; from < ids.size(); from += 500) {
                List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + 500));
                String sql = "SELECT " + PENDING_COLUMNS + " FROM pending_upgrades WHERE complete_at <= ?"
                        + " AND island_id IN (" + String.join(",", java.util.Collections.nCopies(chunk.size(), "?"))
                        + ")";
                try (PreparedStatement st = c.prepareStatement(sql)) {
                    st.setLong(1, now);
                    for (int i = 0; i < chunk.size(); i++) {
                        st.setString(i + 2, chunk.get(i).toString());
                    }
                    out.addAll(readPendingRows(st));
                }
            }
        } catch (SQLException e) {
            logger.severe("Could not load finished upgrades: " + e.getMessage());
            return List.of();
        }
        return out;
    }

    private static List<PendingUpgrade> readPendingRows(PreparedStatement st) throws SQLException {
        List<PendingUpgrade> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(new PendingUpgrade(UUID.fromString(rs.getString("island_id")), rs.getString("upgrade_key"),
                        rs.getInt("target_tier"), rs.getLong("complete_at")));
            }
        }
        return out;
    }

    @Override
    public int deletePending(UUID islandId, String upgradeKey) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "DELETE FROM pending_upgrades WHERE island_id = ? AND upgrade_key = ?")) {
            st.setString(1, islandId.toString());
            st.setString(2, upgradeKey);
            return st.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Could not delete pending upgrade: " + e.getMessage());
            return 0;
        }
    }

    @Override
    public boolean completePending(PendingUpgrade p, @Nullable Integer radius) {
        try {
            return tx.inTransaction(t -> {
                Connection c = t.connection();
                // the island row first, in the same order as reservePending, so the two never deadlock
                String upgrades;
                try (PreparedStatement st = c.prepareStatement(
                        "SELECT upgrades FROM islands WHERE id = ?" + t.dialect().forUpdate())) {
                    st.setString(1, p.islandId().toString());
                    try (ResultSet rs = st.executeQuery()) {
                        upgrades = rs.next() ? rs.getString(1) : null;
                    }
                }
                try (PreparedStatement st = c.prepareStatement("DELETE FROM pending_upgrades"
                        + " WHERE island_id = ? AND upgrade_key = ? AND target_tier = ?")) {
                    st.setString(1, p.islandId().toString());
                    st.setString(2, p.upgradeKey());
                    st.setInt(3, p.targetTier());
                    if (st.executeUpdate() == 0) {
                        return false;
                    }
                }
                if (upgrades == null || Island.upgradeTierIn(upgrades, p.upgradeKey()) >= p.targetTier()) {
                    return true;
                }
                String sql = "UPDATE islands SET upgrades = ?" + (radius != null ? ", radius = ?" : "")
                        + ", version = version + 1 WHERE id = ?";
                try (PreparedStatement st = c.prepareStatement(sql)) {
                    int i = 1;
                    st.setString(i++, Island.withUpgradeTier(upgrades, p.upgradeKey(), p.targetTier()));
                    if (radius != null) {
                        st.setInt(i++, radius);
                    }
                    st.setString(i, p.islandId().toString());
                    st.executeUpdate();
                }
                return true;
            });
        } catch (SQLException e) {
            logger.severe("Could not finish upgrade " + p.upgradeKey() + " on island " + p.islandId() + ": "
                    + e.getMessage());
            return false;
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
            logger.severe("Could not load active profile for " + player + ": " + e.getMessage());
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
            logger.severe("Could not set active profile for " + player + ": " + e.getMessage());
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
            logger.severe("Could not load profile data " + profileId + "/" + playerUuid + ": " + e.getMessage());
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
            logger.severe("Could not save profile data " + profileId + "/" + playerUuid + ": " + e.getMessage());
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
            logger.severe("Could not load bank account " + accountId + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public BankOutcome bankTransaction(String accountId, int startingLevel, BankMutation work) {
        try {
            return bankTx.inTransaction(t -> {
                Connection c = t.connection();
                // lock first and create only when missing: an INSERT IGNORE on an existing row takes a shared
                // lock, and two of those both upgrading to the FOR UPDATE lock deadlock
                com.mystipixel.royalskyblock.bank.BankAccount locked = lockBankAccount(t, accountId);
                if (locked == null) {
                    try (PreparedStatement st = c.prepareStatement(t.dialect().insertIgnore() + " INTO bank_accounts "
                            + "(account_id, balance, level, last_interest, interest_floor) VALUES (?, 0, ?, 0, -1)")) {
                        st.setString(1, accountId);
                        st.setInt(2, startingLevel);
                        st.executeUpdate();
                    }
                    locked = lockBankAccount(t, accountId);
                    if (locked == null) {
                        throw new SQLException("bank account " + accountId + " vanished after it was created");
                    }
                }
                BankOutcome outcome = work.apply(locked);
                if (!outcome.written()) {
                    return outcome;
                }
                com.mystipixel.royalskyblock.bank.BankAccount updated = outcome.account();
                try (PreparedStatement st = c.prepareStatement("UPDATE bank_accounts SET balance = ?, level = ?, "
                        + "last_interest = ?, interest_floor = ? WHERE account_id = ?")) {
                    st.setDouble(1, updated.balance());
                    st.setInt(2, updated.level());
                    st.setLong(3, updated.lastInterest());
                    st.setDouble(4, updated.interestFloor());
                    st.setString(5, accountId);
                    st.executeUpdate();
                }
                try (PreparedStatement st = c.prepareStatement("INSERT INTO bank_txns "
                        + "(account_id, type, amount, balance_after, created_at, note) VALUES (?,?,?,?,?,?)")) {
                    st.setString(1, accountId);
                    st.setString(2, outcome.type());
                    st.setDouble(3, outcome.amount());
                    st.setDouble(4, updated.balance());
                    st.setLong(5, java.time.Instant.now().getEpochSecond());
                    st.setString(6, outcome.note() == null ? "" : outcome.note());
                    st.executeUpdate();
                }
                return outcome;
            });
        } catch (SQLException e) {
            if (e.getErrorCode() == MYSQL_LOCK_WAIT_TIMEOUT) {
                logger.warning("Bank account " + accountId + " is busy on another server; refused: " + e.getMessage());
            } else {
                logger.severe("Could not save bank account " + accountId + ": " + e.getMessage());
            }
            return BankOutcome.failure();
        }
    }

    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;

    // null when the account has no row yet
    private static com.mystipixel.royalskyblock.bank.BankAccount lockBankAccount(
            com.mystipixel.royalcommon.storage.Tx t, String accountId) throws SQLException {
        try (PreparedStatement st = t.connection().prepareStatement("SELECT balance, level, last_interest, "
                + "interest_floor FROM bank_accounts WHERE account_id = ?" + t.dialect().forUpdate())) {
            st.setString(1, accountId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? new com.mystipixel.royalskyblock.bank.BankAccount(accountId, rs.getDouble(1),
                        rs.getInt(2), rs.getLong(3), rs.getDouble(4)) : null;
            }
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
            logger.severe("Could not load bank transactions " + accountId + ": " + e.getMessage());
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
            logger.severe("Could not record coop payout " + fromProfile + " -> " + player + ": " + e.getMessage());
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
            logger.severe("Could not load coop payouts for " + player + ": " + e.getMessage());
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
            logger.severe("Could not clear coop payout " + fromProfile + " -> " + player + ": " + e.getMessage());
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
            logger.warning("Could not delete profile data " + profileId + "/" + playerUuid + ": " + e.getMessage());
        }
    }

    private boolean executeUpdate(String sql, String param) {
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, param);
            st.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.severe("DB update failed (" + sql + "): " + e.getMessage());
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
