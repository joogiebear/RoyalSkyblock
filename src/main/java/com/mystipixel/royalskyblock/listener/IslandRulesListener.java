package com.mystipixel.royalskyblock.listener;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Enforces the configured island gamemode ({@code island.world-rules.gamemode}) whenever a player
 * arrives on an island — on a world change or on join if they log in on one. The gamerules half of
 * the world rules is applied at world load from {@code IslandManager}; this listener only handles the
 * per-player gamemode, since it can't be set on a world.
 *
 * <p>Both handlers delegate to {@link com.mystipixel.royalskyblock.world.IslandWorldRules#applyGameMode},
 * which no-ops off islands, when enforcement is disabled, or for players with
 * {@code royalskyblock.playmode.bypass} — so builders/admins keep whatever gamemode they choose.
 */
public final class IslandRulesListener implements Listener {

    private final RoyalSkyblockPlugin plugin;

    public IslandRulesListener(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        plugin.worldRules().applyGameMode(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        plugin.worldRules().applyGameMode(event.getPlayer());
    }
}
