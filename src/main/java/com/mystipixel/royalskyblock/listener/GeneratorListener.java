package com.mystipixel.royalskyblock.listener;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.island.Island;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;

/**
 * Turns the vanilla cobblestone generator into a tiered ore generator on islands.
 *
 * <p>Only formations on an island world are touched, so a server running other worlds alongside keeps
 * vanilla behaviour there. Runs at LOW priority and respects cancellation, leaving protection plugins
 * the final say on whether the block forms at all.
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
            return;                                  // not an island world — leave vanilla alone
        }
        Material rolled = plugin.generators().roll(island);
        if (rolled != formed) {
            event.getNewState().setType(rolled);
        }
    }
}
