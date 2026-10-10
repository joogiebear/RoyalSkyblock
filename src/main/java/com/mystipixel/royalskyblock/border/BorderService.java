package com.mystipixel.royalskyblock.border;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.island.Island;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Island borders that scale with island size, in an admin-set colour (blue/red/green) or off. Uses
 * per-player world borders (Paper): non-bypass players on an island get a border at its radius;
 * players with {@code royalskyblock.bypass} get none. The island world's own border stays wide open.
 *
 * <p>The colour is faked with an imperceptible perpetual lerp: static is blue, lerping outward is
 * green, lerping inward is red.
 *
 * <p>Only borders this service applied are ever cleared: other plugins may set per-player borders in
 * their own worlds.
 */
public final class BorderService implements Listener {

    private static final long LERP_SECONDS = 100_000_000L; // ~3 years; 2-block delta => ~0 drift
    private static final double LERP_DELTA = 2.0;

    private final RoyalSkyblockPlugin plugin;
    private BorderColor color = BorderColor.BLUE;
    private boolean enabled = true;

    // players carrying a border this service applied; any other per-player border is left alone
    private final Set<UUID> ours = new HashSet<>();

    public BorderService(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        enabled = plugin.conf().getBoolean("island.border.enabled", true);
        color = BorderColor.from(plugin.conf().getString("island.border.color", "blue"));
    }

    public BorderColor color() {
        return color;
    }

    public boolean active() {
        return enabled && color != BorderColor.OFF;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> apply(event.getPlayer())); // after the respawn teleport
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ours.remove(event.getPlayer().getUniqueId());
    }

    /** Re-apply borders to everyone in a world (e.g. after an island resize). */
    public void applyToWorld(World world) {
        for (Player player : world.getPlayers()) {
            apply(player);
        }
    }

    /** Re-apply borders to every online player (e.g. after /is reload). */
    public void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player);
        }
    }

    // Not setWorldBorder(null) unconditionally: leaving an island must drop only our border, not one
    // another plugin set for the destination world.
    private void release(Player player) {
        if (ours.remove(player.getUniqueId())) {
            player.setWorldBorder(null);
        }
    }

    public void apply(Player player) {
        World world = player.getWorld();
        Island island = plugin.islands().getIslandByWorld(world);
        boolean bypass = player.hasPermission("royalskyblock.bypass");
        boolean debug = plugin.conf().getBoolean("island.border.debug", false);

        if (island == null || bypass || !active()) {
            release(player);
            if (debug) {
                plugin.getLogger().info("[border] " + player.getName() + " world=" + world.getName()
                        + " island=" + (island == null ? "null" : "yes") + " bypass=" + bypass + " -> no border");
            }
            return;
        }
        ConfigurationSection paste = plugin.conf().getConfigurationSection("island.paste");
        double cx = (paste != null ? paste.getInt("x", 0) : 0) + 0.5;
        double cz = (paste != null ? paste.getInt("z", 0) : 0) + 0.5;
        double size = Math.max(1.0, island.radius() * 2.0);

        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(cx, cz);
        border.setWarningDistance(Math.max(0, plugin.conf().getInt("island.border.warning-blocks", 2)));
        border.setDamageAmount(0.0);
        border.setDamageBuffer(0.0);
        switch (color) {
            case GREEN -> {
                border.setSize(size);
                border.setSize(size + LERP_DELTA, LERP_SECONDS); // lerp outward -> green
            }
            case RED -> {
                border.setSize(size + LERP_DELTA);
                border.setSize(size, LERP_SECONDS); // lerp inward toward the true edge -> red
            }
            default -> border.setSize(size); // BLUE: static
        }
        player.setWorldBorder(border);
        ours.add(player.getUniqueId());
        if (debug) {
            plugin.getLogger().info("[border] " + player.getName() + " world=" + world.getName()
                    + " -> " + color + " border size=" + size + " center=" + cx + "," + cz + " radius=" + island.radius());
        }
    }
}
