package com.mystipixel.royalskyblock.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Locale;
import java.util.Properties;

/**
 * How {@link SqlStorage} connects, read from the {@code storage} section of config.yml. A record so tests
 * can open storages without a plugin.
 *
 * @param dataSourceProps extra Hikari data source properties (the SQLite pragmas), possibly empty
 * @param serverId this server's name in leases and hand-off rows ({@code ServerId.resolve})
 */
public record SqlSettings(SqlStorage.Type type, String jdbcUrl, String driver, String username, String password,
                          int poolSize, Properties dataSourceProps, String serverId, File dataFolder) {

    public static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";
    public static final String SQLITE_DRIVER = "org.sqlite.JDBC";

    public static SqlSettings fromConfig(ConfigurationSection storage, String serverId, File dataFolder) {
        if (storage == null) {
            storage = new YamlConfiguration();
        }
        String rawType = storage.getString("type", "SQLITE").toUpperCase(Locale.ROOT);
        if ("MYSQL".equals(rawType)) {
            ConfigurationSection my = storage.getConfigurationSection("mysql");
            if (my == null) {
                my = storage.createSection("mysql");
            }
            String url = "jdbc:mysql://" + my.getString("host", "localhost") + ":" + my.getInt("port", 3306)
                    + "/" + my.getString("database", "royalskyblock") + "?" + my.getString("properties", "useSSL=false");
            return new SqlSettings(SqlStorage.Type.MYSQL, url, MYSQL_DRIVER, my.getString("username", "root"),
                    my.getString("password", ""), Math.max(1, my.getInt("pool-size", 10)), new Properties(),
                    serverId, dataFolder);
        }
        File databaseFile = new File(dataFolder, storage.getString("sqlite-file", "islands.db"));
        return new SqlSettings(SqlStorage.Type.SQLITE, "jdbc:sqlite:" + databaseFile.getAbsolutePath(),
                SQLITE_DRIVER, null, null, SqliteSettings.POOL_SIZE, SqliteSettings.properties(), serverId, dataFolder);
    }
}
