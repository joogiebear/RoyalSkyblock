package com.mystipixel.royalskyblock.listener;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Applies the island gamemode ({@code island.world-rules.gamemode}) when a player arrives on an
 * island, by world change or join. Gamerules are applied at world load; a gamemode can't be set on a
 * world. {@link com.mystipixel.royalskyblock.world.IslandWorldRules#applyGameMode} skips players with
 * {@code royalskyblock.playmode.bypass}.
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
