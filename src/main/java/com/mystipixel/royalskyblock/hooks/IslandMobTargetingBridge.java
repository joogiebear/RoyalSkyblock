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
 * The "targeting bridge" the Intimidation talismans are written against: island mobs at or below a
 * player's Intimidation stat simply ignore them.
 *
 * <p>The Talismans configs only <em>grant</em> the {@code intimidation} stat ({@code add_stat}) — the
 * stat itself has no effects, so without this nothing acts on it and mobs target normally. Here we read
 * the tier RoyalSkyblock stamped on each mob when it spawned and cancel the targeting when the mob is
 * weak enough to be cowed.
 *
 * <p>Ignore level is {@code min(combat level, intimidation stat)} by default — the formula written in the
 * talismans' own descriptions — so the cap can't exceed what the player's Combat actually supports.
 * Only mobs RoyalSkyblock spawned are affected (they carry the tier tag); vanilla and other plugins'
 * mobs are untouched, which matches the items' "private-island mobs" wording.
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
        if (!plugin.getConfig().getBoolean("island-mobs.intimidation.enabled", true)) {
            return;
        }
        if (!(event.getTarget() instanceof Player player)) {
            return;
        }
        Integer mobLevel = event.getEntity().getPersistentDataContainer()
                .get(levelKey, PersistentDataType.INTEGER);
        if (mobLevel == null) {
            return;                              // not an island mob we spawned — leave it alone
        }
        if (mobLevel <= ignoreLevel(player)) {
            event.setCancelled(true);            // too weak to pick a fight with this player
            clearTarget(event.getEntity());
        }
    }

    /**
     * Second line of defence: an intimidated mob can't damage the player even if it re-acquired a
     * target through a path that doesn't fire the target event. Witches are the reason this exists —
     * they're Raiders that re-target aggressively and attack with thrown splash potions, so cancelling
     * targeting alone doesn't reliably stop them. Projectiles resolve back to whoever fired them.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("island-mobs.intimidation.enabled", true)) {
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

    /** {@code min(combat level, intimidation stat)} — or the raw stat when the cap is turned off. */
    private int ignoreLevel(Player player) {
        int level = intimidation.levelOf(player);
        if (plugin.getConfig().getBoolean("island-mobs.intimidation.cap-to-combat-level", true)) {
            level = Math.min(level, combat.levelOf(player));
        }
        return level;
    }
}
