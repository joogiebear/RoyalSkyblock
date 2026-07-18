package com.mystipixel.royalskyblock.listener;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Disables hunger everywhere players are (islands + hub) when {@code gameplay.disable-hunger} is on.
 *
 * <p>There is no vanilla gamerule or Paper option to switch hunger off (peaceful difficulty does it,
 * but it also stops hostile/EcoMobs spawning). The reliable way is to intercept the food-change event:
 * to drop below full, the server fires {@link FoodLevelChangeEvent}, so pinning the new level to 20
 * (and topping saturation) means the bar never moves. Join/respawn top-offs cover a player who was
 * already low, since a perfectly still player generates no food event to correct them.
 *
 * <p>The toggle is read live from config, so {@code /is reload} turns it on or off without a restart.
 */
public final class HungerListener implements Listener {

    private static final int FULL_FOOD = 20;
    private static final float FULL_SATURATION = 20f;

    private final RoyalSkyblockPlugin plugin;

    public HungerListener(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("gameplay.disable-hunger", true);
    }

    // HIGH + ignoreCancelled=false: we want the final say on the level regardless of other plugins.
    @EventHandler(priority = EventPriority.HIGH)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!enabled() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        event.setFoodLevel(FULL_FOOD);
        player.setSaturation(FULL_SATURATION);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        topOff(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        topOff(event.getPlayer());
    }

    private void topOff(Player player) {
        if (!enabled()) {
            return;
        }
        player.setFoodLevel(FULL_FOOD);
        player.setSaturation(FULL_SATURATION);
        player.setExhaustion(0f);
    }
}
