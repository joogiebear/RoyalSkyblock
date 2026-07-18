package com.mystipixel.royalskyblock.hooks;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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
