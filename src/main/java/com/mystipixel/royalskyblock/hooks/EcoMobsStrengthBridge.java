package com.mystipixel.royalskyblock.hooks;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.island.Island;
import com.willfp.ecomobs.event.EcoMobSpawnEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Scales an EcoMobs custom mob's health and damage by the level of the island it spawns on.
 *
 * <p><b>Why a code bridge instead of EcoMobs config.</b> EcoMobs evaluates its spawn conditions
 * against the spawn <em>location</em> ({@code conditions.areMet(location.toDispatcher(), ...)}), with
 * no player in context. libreforge's placeholder conditions resolve via {@code dispatcher.get<Player>()},
 * so {@code %royalskyblock_island_level%} can't resolve at an EcoMobs spawn — the config route is a
 * dead end. Here we read the island level straight from the mob's world after it has spawned, which
 * needs no player at all.
 *
 * <p><b>Soft dependency.</b> This class references EcoMobs types, so it is only instantiated and
 * registered when EcoMobs is actually installed (guarded in {@code onEnable}). If EcoMobs is absent,
 * the class is never loaded and RoyalSkyblock is unaffected.
 *
 * <p><b>Version independence.</b> The attributes are looked up by their stable registry key
 * ({@code minecraft:max_health} / {@code minecraft:attack_damage}) rather than the {@code Attribute}
 * enum constant, whose Java name churned across Bukkit versions (the {@code GENERIC_} prefix). The
 * registry keys have been stable since the attribute rename, so this compiles and resolves the same
 * whatever paper-api version the plugin is built against.
 */
public final class EcoMobsStrengthBridge implements Listener {

    private static final Attribute MAX_HEALTH = attribute("max_health");
    private static final Attribute ATTACK_DAMAGE = attribute("attack_damage");

    private final RoyalSkyblockPlugin plugin;

    public EcoMobsStrengthBridge(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    private static Attribute attribute(String key) {
        try {
            return Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
        } catch (Throwable t) {
            return null;                         // very old/odd API — the scale() guard handles null
        }
    }

    // HIGH, not MONITOR: MONITOR is for observers, and we mutate the mob. Runs after EcoMobs has
    // finished configuring the entity (the event carries an already-set-up LivingMob).
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawn(EcoMobSpawnEvent event) {
        if (!plugin.getConfig().getBoolean("ecomobs.enabled", true)) {
            return;
        }
        Mob entity = event.getMob().getEntity();
        if (entity == null) {
            return;
        }
        Island island = plugin.islands().getIslandByWorld(entity.getWorld());
        if (island == null || island.level() <= 0) {
            return;                              // not on an island, or level 0 — leave the mob as-is
        }
        double level = island.level();
        scale(entity, MAX_HEALTH,
                multiplier(level, "health-scale-per-level", 0.002), true);
        scale(entity, ATTACK_DAMAGE,
                multiplier(level, "damage-scale-per-level", 0.0015), false);
    }

    /** {@code 1 + level*scale}, clamped to {@code [1, max-multiplier]}. */
    private double multiplier(double level, String scaleKey, double scaleDefault) {
        double scale = plugin.getConfig().getDouble("ecomobs." + scaleKey, scaleDefault);
        double max = plugin.getConfig().getDouble("ecomobs.max-multiplier", 5.0);
        return Math.max(1.0, Math.min(max, 1.0 + level * scale));
    }

    private void scale(Mob entity, Attribute attr, double factor, boolean isHealth) {
        if (attr == null || factor <= 1.0) {
            return;
        }
        AttributeInstance inst = entity.getAttribute(attr);
        if (inst == null) {
            return;                              // passive mob with no attack damage, etc.
        }
        double scaled = inst.getBaseValue() * factor;
        inst.setBaseValue(scaled);
        if (isHealth) {
            entity.setHealth(scaled);            // spawn at the new full health, not the old value
        }
    }
}
