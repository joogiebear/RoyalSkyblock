package com.mystipixel.royalskyblock.listener;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.profile.Profile;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces who may alter an island. Each island is its own world, so protection is a per-world role
 * check. Non-island worlds are ignored, and {@code royalskyblock.bypass} skips every check. Coop members
 * are not protected from each other; everything here is about visitors, including taking things
 * (entities, item frames, pickups, trampling) that never fire a block event.
 *
 * <p>{@code island.protection.visitor-mode}:
 * <ul>
 *   <li>{@code read-only} (default): a visitor may walk around, open doors, press buttons and ride
 *       things, but cannot take or change anything.</li>
 *   <li>{@code strict}: every interaction is refused.</li>
 * </ul>
 */
public final class ProtectionListener implements Listener {

    private static final long MESSAGE_COOLDOWN_MS = 2500L;

    // Blocks a visitor may not right-click even in read-only mode because the click takes or consumes
    // something (inventories are refused separately). Crafting table, anvil etc. hold nothing, so they're absent.
    private static final Set<Material> TAKEABLE = Set.of(
            Material.COMPOSTER,             // right-click at level 8 yields the bone meal
            Material.BEEHIVE,               // bottle or shears takes the honey
            Material.BEE_NEST,
            Material.SWEET_BERRY_BUSH,      // right-click harvests
            Material.CAVE_VINES,            // glow berries
            Material.CAVE_VINES_PLANT,
            Material.RESPAWN_ANCHOR,        // consumes glowstone / sets spawn
            Material.CAKE,
            Material.FLOWER_POT
    );

    // Blocks a right-click changes without taking anything (re-timing a repeater can break a farm). Signs
    // are matched by tag.
    private static final Set<Material> CHANGEABLE = Set.of(
            Material.REPEATER,
            Material.COMPARATOR,
            Material.NOTE_BLOCK,            // right-click re-tunes it
            Material.DAYLIGHT_DETECTOR,     // right-click inverts it
            Material.REDSTONE_WIRE,         // right-click toggles dot/cross shape
            Material.DRAGON_EGG             // any click teleports it, possibly off the island
    );

    private final RoyalSkyblockPlugin plugin;
    private final ConcurrentHashMap<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    public ProtectionListener(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    // True for non-island worlds, bypass holders, and members who can build.
    private boolean canBuild(Player player, World world) {
        if (player.hasPermission("royalskyblock.bypass")) {
            return true;
        }
        Island island = plugin.islands().getIslandByWorld(world);
        if (island == null) {
            return true; // not an island world, RoyalSkyblock doesn't govern it
        }
        Profile profile = plugin.profiles().getProfile(island.profileId());
        if (profile == null) {
            return true; // orphaned island, don't trap anyone
        }
        return profile.roleOf(player.getUniqueId()).canBuild();
    }

    // Members may only edit inside the island's radius: the visible border is client-side and can be
    // bypassed. Same square as the level scan.
    private boolean canBuildAt(Player player, Block block) {
        if (!canBuild(player, block.getWorld())) {
            return false;
        }
        if (player.hasPermission("royalskyblock.bypass")) {
            return true;
        }
        Island island = plugin.islands().getIslandByWorld(block.getWorld());
        if (island == null) {
            return true;
        }
        ConfigurationSection paste = plugin.conf().getConfigurationSection("island.paste");
        int cx = paste != null ? paste.getInt("x", 0) : 0;
        int cz = paste != null ? paste.getInt("z", 0) : 0;
        int r = Math.max(1, island.radius());
        return Math.abs(block.getX() - cx) <= r && Math.abs(block.getZ() - cz) <= r;
    }

    private boolean strictMode() {
        return "strict".equalsIgnoreCase(
                plugin.conf().getString("island.protection.visitor-mode", "read-only"));
    }

    // the message cooldown is per session
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastMessage.remove(event.getPlayer().getUniqueId());
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
        if (!canBuildAt(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!canBuildAt(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    // Right-clicks, and trampling: farmland is destroyed by a PHYSICAL interact, not a break.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        if (canBuild(event.getPlayer(), block.getWorld())) {
            return;
        }

        if (event.getAction() == Action.PHYSICAL) {
            // trampling farmland, and treading on turtle eggs
            if (block.getType() == Material.FARMLAND || block.getType() == Material.TURTLE_EGG) {
                event.setCancelled(true);
            }
            return;                                     // pressure plates are harmless; leave them
        }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && block.getType() == Material.DRAGON_EGG) {
            event.setCancelled(true);                   // punching the egg teleports it too
            deny(event.getPlayer());
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (strictMode() || holdsItems(block) || TAKEABLE.contains(block.getType())
                || CHANGEABLE.contains(block.getType()) || Tag.ALL_SIGNS.isTagged(block.getType())) {
            event.setCancelled(true);
            deny(event.getPlayer());
            return;
        }
        // read-only: doors, buttons, levers and workbenches stay usable, but never with an item in hand.
        // Using an item on a block is how a visitor lights TNT, bone-meals, strips logs or drops a spawn egg.
        if (event.getItem() != null) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    private boolean holdsItems(Block block) {
        return block.getState() instanceof InventoryHolder;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!canBuildAt(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!canBuildAt(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    // backstop for sign edits that reach the editor by any route the interact check missed
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (!canBuild(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    // TNT lit by a visitor's flaming arrow: the one ignition that is not an interact
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTntPrime(TNTPrimeEvent event) {
        Player primer = resolvePlayer(event.getPrimingEntity());
        if (primer != null && !canBuild(primer, event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    // placing boats, minecarts, armour stands and end crystals
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && !canBuild(player, event.getEntity().getWorld())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && !canBuild(player, event.getEntity().getWorld())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    // reeling in someone's animal with a fishing rod, e.g. into the void
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY && event.getCaught() != null
                && !(event.getCaught() instanceof Player)
                && !canBuild(event.getPlayer(), event.getCaught().getWorld())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    // right-clicking an entity (rotating a frame, trading, leashing); refused for visitors in both modes
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!canBuild(event.getPlayer(), event.getRightClicked().getWorld())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    // armour stand manipulation has its own event
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (!canBuild(event.getPlayer(), event.getRightClicked().getWorld())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    // Damaging anything on someone's island: animals, pets, and item frames / armour stands whose contents
    // drop when hit. Projectiles resolve to their shooter.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageEntity(EntityDamageByEntityEvent event) {
        Player attacker = resolvePlayer(event.getDamager());
        if (attacker == null) {
            return;
        }
        if (event.getEntity() instanceof Enemy) {
            return; // hostile mobs attack visitors too; refusing the swing back left them defenceless
        }
        if (!canBuild(attacker, event.getEntity().getWorld())) {
            event.setCancelled(true);
            deny(attacker);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Player remover = resolvePlayer(event.getRemover());
        if (remover == null) {
            return;
        }
        if (!canBuild(remover, event.getEntity().getWorld())) {
            event.setCancelled(true);
            deny(remover);
        }
    }

    // picking items up, e.g. standing at a grinder or minion and collecting its output
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!canBuild(player, event.getItem().getWorld())) {
            event.setCancelled(true);
            // no message: pickup fires constantly near drops
        }
    }

    // the player behind a damage source, following a projectile back to its shooter
    private Player resolvePlayer(org.bukkit.entity.Entity source) {
        if (source instanceof Player player) {
            return player;
        }
        if (source instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }
        return null;
    }
}
