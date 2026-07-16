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
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Island borders that scale with island size and come in three admin-set colours (blue/red/green) or
 * off. Uses <b>per-player</b> world borders (Paper): a non-bypass player standing on an island gets a
 * colored border at the island's radius; players with {@code royalskyblock.bypass} (admins) get none, so
 * they pass through freely. The island world's own border is kept wide open so it never enforces anyone.
 *
 * <p>The colour is faked via an imperceptible perpetual lerp — static borders render blue, a border
 * lerping outward renders green, and one lerping inward renders red.
 */
public final class BorderService implements Listener {

    private static final long LERP_SECONDS = 100_000_000L; // ~3 years; 2-block delta => ~0 drift
    private static final double LERP_DELTA = 2.0;

    private final RoyalSkyblockPlugin plugin;
    private BorderColor color = BorderColor.BLUE;
    private boolean enabled = true;

    public BorderService(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("island.border.enabled", true);
        color = BorderColor.from(plugin.getConfig().getString("island.border.color", "blue"));
    }

    public BorderColor color() {
        return color;
    }

    public boolean active() {
        return enabled && color != BorderColor.OFF;
    }

    // ── events ─────────────────────────────────────────────────────────────────────

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

    // ── application ────────────────────────────────────────────────────────────────

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

    public void apply(Player player) {
        if (!active()) {
            player.setWorldBorder(null);
            return;
        }
        World world = player.getWorld();
        Island island = plugin.islands().getIslandByWorld(world);
        boolean bypass = player.hasPermission("royalskyblock.bypass");
        boolean debug = plugin.getConfig().getBoolean("island.border.debug", false);
        if (island == null || bypass) {
            player.setWorldBorder(null); // not an island world, or an admin who bypasses
            if (debug) {
                plugin.getLogger().info("[border] " + player.getName() + " world=" + world.getName()
                        + " island=" + (island == null ? "null" : "yes") + " bypass=" + bypass + " -> no border");
            }
            return;
        }
        ConfigurationSection paste = plugin.getConfig().getConfigurationSection("island.paste");
        double cx = (paste != null ? paste.getInt("x", 0) : 0) + 0.5;
        double cz = (paste != null ? paste.getInt("z", 0) : 0) + 0.5;
        double size = Math.max(1.0, island.radius() * 2.0);

        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(cx, cz);
        border.setWarningDistance(Math.max(0, plugin.getConfig().getInt("island.border.warning-blocks", 2)));
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
            default -> border.setSize(size); // BLUE: static -> blue
        }
        player.setWorldBorder(border);
        if (debug) {
            plugin.getLogger().info("[border] " + player.getName() + " world=" + world.getName()
                    + " -> " + color + " border size=" + size + " center=" + cx + "," + cz + " radius=" + island.radius());
        }
    }
}
