package com.mystipixel.royalskyblock.hooks;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Island mobs at or below a player's ignore level don't target them. The Intimidation talismans only
 * grant the {@code intimidation} stat; this listener is what acts on it.
 *
 * <p>Ignore level is {@code min(combat level, intimidation stat)} by default, the formula in the
 * talismans' descriptions. Only mobs RoyalSkyblock spawned (they carry the tier tag) are affected.
 */
public final class IslandMobTargetingBridge implements Listener {

    private final RoyalSkyblockPlugin plugin;
    private final CombatLevelSource combat;
    private final CombatLevelSource intimidation;
    private final NamespacedKey levelKey;

    public IslandMobTargetingBridge(RoyalSkyblockPlugin plugin, CombatLevelSource combat,
                                    CombatLevelSource intimidation) {
        this.plugin = plugin;
        this.combat = combat;
        this.intimidation = intimidation;
        this.levelKey = new NamespacedKey(plugin, "island_mob_level");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!plugin.conf().getBoolean("island-mobs.intimidation.enabled", true)) {
            return;
        }
        if (!(event.getTarget() instanceof Player player)) {
            return;
        }
        Integer mobLevel = event.getEntity().getPersistentDataContainer()
                .get(levelKey, PersistentDataType.INTEGER);
        if (mobLevel == null) {
            return;                              // not an island mob we spawned
        }
        if (mobLevel <= ignoreLevel(player)) {
            event.setCancelled(true);            // too weak to pick a fight with this player
            clearTarget(event.getEntity());
        }
    }

    // Second line of defence for mobs that re-acquire a target without the target event, mainly witches
    // (Raiders that throw potions). Projectiles resolve back to whoever fired them.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!plugin.conf().getBoolean("island-mobs.intimidation.enabled", true)) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Entity source = event.getDamager();
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            source = shooter;                    // witch potion, skeleton arrow, ...
        }
        Integer mobLevel = source.getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER);
        if (mobLevel == null) {
            return;
        }
        if (mobLevel <= ignoreLevel(player)) {
            event.setCancelled(true);
            clearTarget(source);
        }
    }

    private void clearTarget(Entity entity) {
        if (entity instanceof Mob mob && mob.getTarget() instanceof Player) {
            mob.setTarget(null);
        }
    }

    // min(combat level, intimidation stat), or the raw stat when the cap is turned off
    private int ignoreLevel(Player player) {
        int level = intimidation.levelOf(player);
        if (plugin.conf().getBoolean("island-mobs.intimidation.cap-to-combat-level", true)) {
            level = Math.min(level, combat.levelOf(player));
        }
        return level;
    }
}
