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
 * Enforces who may alter an island. Because each island is its own world, protection is a simple
 * per-world role check — no region geometry. Non-island worlds (hub, farming) are ignored entirely,
 * and {@code royalskyblock.bypass} (default op) skips every check.
 *
 * <p><b>Co-op members are deliberately unprotected from each other.</b> Inviting someone onto your
 * island is the trust decision; the plugin does not second-guess it. Everything here is about
 * <em>visitors</em> — people who are on the island but not part of it.
 *
 * <p>Blocking block edits is the easy half and was never enough on its own. The profitable grief is
 * not breaking things, it is <em>taking</em> them, and none of that goes through a block event: item
 * frames and armour stands are entities, dropped items are entities, and a trampled crop arrives as
 * a {@code PHYSICAL} interact rather than a break. Those paths are covered here too.
 *
 * <p>Two policies, set by {@code island.protection.visitor-mode}:
 * <ul>
 *   <li>{@code read-only} (default) — a visitor may walk around, open doors, press buttons and ride
 *       things, but cannot take anything or change anything. This is what makes visiting worth
 *       doing; an island nobody can move around is not much of a showcase.</li>
 *   <li>{@code strict} — every interaction is refused.</li>
 * </ul>
 */
public final class ProtectionListener implements Listener {

    private static final long MESSAGE_COOLDOWN_MS = 2500L;

    /**
     * Blocks a visitor may not right-click even in read-only mode, beyond anything holding an
     * inventory (which is refused wholesale).
     *
     * <p>Every entry is something a right-click <em>takes</em> or <em>consumes</em>, which is the
     * line read-only draws. Interfaces that hold nothing — crafting table, anvil, grindstone,
     * stonecutter, loom — are deliberately absent: a visitor using one cannot remove anything from
     * the island.
     */
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

    /**
     * Blocks a right-click <em>changes</em> without taking anything: a visitor re-timing a repeater or
     * flipping a comparator can silently break a farm, and a sign edit is vandalism. Signs are matched
     * by tag rather than listed here.
     */
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

    /**
     * {@link #canBuild} for a specific block: members may only edit inside the island's radius.
     *
     * <p>The border players see is per-player, client-side and does no damage, so it never stopped a
     * member who pearled, boated or modded their way past it from building outside — which skipped
     * the island-size upgrade entirely. This is the server-side half of that border. It uses the same
     * square as the level scan: within {@code radius} blocks of the paste point on X and Z, which is
     * every block at least half inside the border.
     */
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

    /** The message cooldown is per session; keeping it for everyone ever seen was a slow leak. */
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

    // ------------------------------------------------------------------ blocks

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

    /**
     * Right-clicks, and trampling.
     *
     * <p>Trampling is the reason this does not simply filter to right-clicks: farmland is destroyed
     * by a {@code PHYSICAL} interact, not a break, so a visitor could otherwise sprint across a farm
     * and ruin it without firing a single event this class was watching.
     */
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
            // Trampling farmland, and treading on turtle eggs.
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
        // read-only: doors, gates, buttons, levers, beds and workbenches stay usable — but never with
        // the item in hand. Using an item on a block is how a visitor lights TNT or fire (flint and
        // steel, fire charge), bone-meals, tills, strips logs, waxes copper, dyes a sign or drops a
        // spawn egg, and none of that is a block place. Denying only the item keeps the door opening
        // when they happen to be holding something.
        if (event.getItem() != null) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    /** True if the block stores items — chests, barrels, furnaces, hoppers, lecterns, campfires. */
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

    /** Backstop for sign edits that reach the editor by any route the interact check missed. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (!canBuild(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    /** TNT lit by a visitor's flaming arrow — the one ignition that is not an interact. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTntPrime(TNTPrimeEvent event) {
        Player primer = resolvePlayer(event.getPrimingEntity());
        if (primer != null && !canBuild(primer, event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ entities

    /** Placing boats, minecarts, armour stands and end crystals: clutter at best, lag at worst. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && !canBuild(player, event.getEntity().getWorld())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    /** Hanging item frames and paintings. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && !canBuild(player, event.getEntity().getWorld())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    /** Reeling in someone's animal with a fishing rod, e.g. off the edge into the void. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY && event.getCaught() != null
                && !(event.getCaught() instanceof Player)
                && !canBuild(event.getPlayer(), event.getCaught().getWorld())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    /**
     * Right-clicking an entity: rotating an item frame, trading with a villager, leashing an animal,
     * putting a saddle on something. Refused for visitors in both modes — none of it is looking.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!canBuild(event.getPlayer(), event.getRightClicked().getWorld())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    /** Taking armour or held items off an armour stand has its own event and its own bypass. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (!canBuild(event.getPlayer(), event.getRightClicked().getWorld())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    /**
     * Damaging anything on someone's island — their animals, their pets, and the item frames and
     * armour stands whose contents drop when hit.
     *
     * <p>Hitting an item frame is the classic theft: it is neither a block break nor an interact, so
     * without this a visitor could empty a display wall while every other guard here sat idle.
     * Projectiles are resolved to their shooter, because an arrow is the obvious way around a check
     * that only looks at melee.
     */
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

    /** Breaking a painting or item frame outright. */
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

    /**
     * Picking items up off the floor.
     *
     * <p>Otherwise a visitor can stand at a grinder or a minion and collect its output indefinitely,
     * which is theft that never touches a block or an inventory.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!canBuild(player, event.getItem().getWorld())) {
            event.setCancelled(true);
            // No message: pickup fires constantly near drops and would drown the player in it.
        }
    }

    /** The player behind a damage source, following a projectile back to whoever fired it. */
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
