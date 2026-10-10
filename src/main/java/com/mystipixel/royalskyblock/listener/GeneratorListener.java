package com.mystipixel.royalskyblock.listener;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.island.Island;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;

/**
 * Turns the vanilla cobblestone generator into a tiered ore generator on island worlds only. LOW
 * priority and respects cancellation, so protection plugins have the final say.
 */
public final class GeneratorListener implements Listener {

    private final RoyalSkyblockPlugin plugin;

    public GeneratorListener(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        Material formed = event.getNewState().getType();
        if (!plugin.generators().handles(formed)) {
            return;
        }
        Island island = plugin.islands().getIslandByWorld(event.getBlock().getWorld());
        if (island == null) {
            return;                                  // not an island world, leave vanilla alone
        }
        Material rolled = plugin.generators().roll(island);
        if (rolled != formed) {
            event.getNewState().setType(rolled);
        }
    }
}
