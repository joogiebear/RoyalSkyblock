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
 * Island borders that scale with island size and come in three admin-set colours (blue/red/green) or
 * off. Uses <b>per-player</b> world borders (Paper): a non-bypass player standing on an island gets a
 * colored border at the island's radius; players with {@code royalskyblock.bypass} (admins) get none, so
 * they pass through freely. The island world's own border is kept wide open so it never enforces anyone.
 *
 * <p>The colour is faked via an imperceptible perpetual lerp — static borders render blue, a border
 * lerping outward renders green, and one lerping inward renders red.
 *
 * <p><b>Scope.</b> This service only ever touches borders it applied itself. A per-player border is a
 * shared resource — a hub plugin, a minigame, or an eco extension running its own world may have set
 * one — so a world with no island is left exactly as it was found rather than cleared.
 */
public final class BorderService implements Listener {

    private static final long LERP_SECONDS = 100_000_000L; // ~3 years; 2-block delta => ~0 drift
    private static final double LERP_DELTA = 2.0;

    private final RoyalSkyblockPlugin plugin;
    private BorderColor color = BorderColor.BLUE;
    private boolean enabled = true;

    /**
     * Players currently carrying a border this service applied.
     *
     * <p>The reason this set exists: a per-player border is not owned by whoever looks at it. Clearing
     * one unconditionally in a world that isn't an island — a hub, a minigame world, another plugin's
     * world — throws away a border somebody else set, and does it on every join and every world change.
     * Remembering which ones are ours is what lets the island border be removed when a player leaves an
     * island without touching a border we did not put there.
     */
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ours.remove(event.getPlayer().getUniqueId()); // a border does not survive the session anyway
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

    /**
     * Take back a border this service applied, and leave any other alone.
     *
     * <p>Not simply {@code setWorldBorder(null)}: a player walking from an island into a world managed
     * by something else must lose the <em>island's</em> border without that other world's being reset
     * from under it.
     */
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
            default -> border.setSize(size); // BLUE: static -> blue
        }
        player.setWorldBorder(border);
        ours.add(player.getUniqueId());
        if (debug) {
            plugin.getLogger().info("[border] " + player.getName() + " world=" + world.getName()
                    + " -> " + color + " border size=" + size + " center=" + cx + "," + cz + " radius=" + island.radius());
        }
    }
}
