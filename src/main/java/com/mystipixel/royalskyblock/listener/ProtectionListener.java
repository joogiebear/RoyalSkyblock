package com.mystipixel.royalskyblock.listener;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.profile.Profile;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces who may alter an island. Because each island is its own world, protection is a simple
 * per-world role check — no region geometry. In an island world, only members who {@link
 * com.mystipixel.royalskyblock.island.IslandRole#canBuild() can build} may break/place/interact with
 * blocks; visitors are blocked. Non-island worlds (hub, overworld) are ignored entirely.
 *
 * <p>Players with {@code royalskyblock.bypass} (default op) skip all checks.
 */
public final class ProtectionListener implements Listener {

    private static final long MESSAGE_COOLDOWN_MS = 2500L;

    private final RoyalSkyblockPlugin plugin;
    private final ConcurrentHashMap<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    public ProtectionListener(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @return true if {@code player} may build in {@code world}. True for non-island worlds, for
     *         bypass-permission holders, and for island members who can build; false otherwise.
     */
    private boolean canBuild(Player player, World world) {
        if (player.hasPermission("royalskyblock.bypass")) {
            return true;
        }
        Island island = plugin.islands().getIslandByWorld(world);
        if (island == null) {
            return true; // not an island world — RoyalSkyblock doesn't govern it
        }
        Profile profile = plugin.profiles().getProfile(island.profileId());
        if (profile == null) {
            return true; // orphaned island — don't trap anyone
        }
        return profile.roleOf(player.getUniqueId()).canBuild();
    }

    private void deny(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last == null || now - last > MESSAGE_COOLDOWN_MS) {
            plugin.messages().send(player, "protection.cannot-build");
            lastMessage.put(player.getUniqueId(), now);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!canBuild(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!canBuild(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        // Guard right-clicks on blocks: containers, doors, buttons, redstone, farmland, etc.
        if (event.getClickedBlock() == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!canBuild(event.getPlayer(), event.getClickedBlock().getWorld())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!canBuild(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!canBuild(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }
}
