package com.mystipixel.royalskyblock.world.asp.loaders;

import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.loaders.SlimeLoader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * A MySQL-backed {@link SlimeLoader} storing each island world as a blob row, so any server on a
 * network can load any island from the shared database. RoyalSkyblock ships its own loader because
 * the ASP fork exposes the world API but not its loader classes on the plugin classpath.
 *
 * <p>Uses its own small HikariCP pool (separate from the island metadata pool) since world blobs are
 * larger and read/written on their own cadence.
 */
public final class RsbMysqlLoader implements SlimeLoader, AutoCloseable {

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS rsb_slime_worlds ("
                    + "name VARCHAR(255) PRIMARY KEY, "
                    + "world MEDIUMBLOB NOT NULL)";
    private static final String SELECT_WORLD = "SELECT world FROM rsb_slime_worlds WHERE name = ?";
    private static final String EXISTS_WORLD = "SELECT 1 FROM rsb_slime_worlds WHERE name = ?";
    private static final String LIST_WORLDS = "SELECT name FROM rsb_slime_worlds";
    private static final String UPSERT_WORLD =
            "INSERT INTO rsb_slime_worlds (name, world) VALUES (?, ?) ON DUPLICATE KEY UPDATE world = VALUES(world)";
    private static final String DELETE_WORLD = "DELETE FROM rsb_slime_worlds WHERE name = ?";

    private final HikariDataSource dataSource;

    public RsbMysqlLoader(String host, int port, String database, String username, String password,
                          String properties, int poolSize) throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setPoolName("RoyalSkyblock-Slime");
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + (properties == null || properties.isBlank() ? "" : "?" + properties));
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(Math.max(1, poolSize));
        this.dataSource = new HikariDataSource(config);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(CREATE_TABLE);
        }
    }

    @Override
    public byte[] readWorld(String worldName) throws UnknownWorldException, IOException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_WORLD)) {
            statement.setString(1, worldName);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new UnknownWorldException(worldName);
                }
                return rs.getBytes("world");
            }
        } catch (SQLException e) {
            throw new IOException("Could not read slime world '" + worldName + "'", e);
        }
    }

    @Override
    public boolean worldExists(String worldName) throws IOException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(EXISTS_WORLD)) {
            statement.setString(1, worldName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IOException("Could not check slime world '" + worldName + "'", e);
        }
    }

    @Override
    public List<String> listWorlds() throws IOException {
        List<String> worlds = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LIST_WORLDS);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                worlds.add(rs.getString("name"));
            }
        } catch (SQLException e) {
            throw new IOException("Could not list slime worlds", e);
        }
        return worlds;
    }

    @Override
    public void saveWorld(String worldName, byte[] serializedWorld) throws IOException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT_WORLD)) {
            statement.setString(1, worldName);
            statement.setBytes(2, serializedWorld);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Could not save slime world '" + worldName + "'", e);
        }
    }

    @Override
    public void deleteWorld(String worldName) throws UnknownWorldException, IOException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_WORLD)) {
            statement.setString(1, worldName);
            if (statement.executeUpdate() == 0) {
                throw new UnknownWorldException(worldName);
            }
        } catch (SQLException e) {
            throw new IOException("Could not delete slime world '" + worldName + "'", e);
        }
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
