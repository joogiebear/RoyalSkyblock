package com.mystipixel.royalskyblock.hooks;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.island.Island;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Mob;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

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
 * <p><b>Why reflection instead of a compile dependency.</b> Every published
 * {@code com.willfp:EcoMobs} artifact is an empty two-entry stub containing no classes — verified
 * across 2026.27, .29, .30, .31 and .32 — so there is nothing to compile against. A build can appear
 * to work when a real jar has been hand-installed into the developer's local Maven repository, but
 * that is invisible to CI and to anyone else cloning the project. The shipped plugin jar does contain
 * {@code com.willfp.ecomobs.event.EcoMobSpawnEvent}, so the event is resolved and registered at
 * runtime instead. This matches how {@link EcoSkillsCombatSource} already hooks EcoSkills, for the
 * same reason.
 *
 * <p><b>Version independence.</b> The attributes are looked up by their stable registry key
 * ({@code minecraft:max_health} / {@code minecraft:attack_damage}) rather than the {@code Attribute}
 * enum constant, whose Java name churned across Bukkit versions (the {@code GENERIC_} prefix).
 */
public final class EcoMobsStrengthBridge implements Listener {

    private static final String EVENT_CLASS = "com.willfp.ecomobs.event.EcoMobSpawnEvent";

    private static final Attribute MAX_HEALTH = attribute("max_health");
    private static final Attribute ATTACK_DAMAGE = attribute("attack_damage");

    private final RoyalSkyblockPlugin plugin;
    private final Method getMob;
    private final Method getEntity;

    private EcoMobsStrengthBridge(RoyalSkyblockPlugin plugin, Method getMob, Method getEntity) {
        this.plugin = plugin;
        this.getMob = getMob;
        this.getEntity = getEntity;
    }

    /**
     * Resolve EcoMobs' spawn event and register the bridge against it. Returns false when EcoMobs
     * isn't present or its event isn't shaped the way we expect; the caller logs and carries on, and
     * island mobs simply aren't scaled.
     *
     * <p>Registered at HIGH rather than MONITOR: MONITOR is for observers and this mutates the mob.
     * HIGH still runs after EcoMobs has finished configuring the entity.
     */
    @SuppressWarnings("unchecked")
    public static boolean register(RoyalSkyblockPlugin plugin) {
        try {
            Plugin ecoMobs = Bukkit.getPluginManager().getPlugin("EcoMobs");
            if (ecoMobs == null) {
                return false;
            }
            Class<?> eventClass = Class.forName(EVENT_CLASS, false, ecoMobs.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(eventClass)) {
                return false;
            }
            Method getMob = eventClass.getMethod("getMob");
            // Resolve getEntity() off the DECLARED return type (EcoMobs' public mob interface) rather
            // than the runtime object's class, which may be a non-public implementation that would
            // reject the call.
            Method getEntity = getMob.getReturnType().getMethod("getEntity");

            EcoMobsStrengthBridge bridge = new EcoMobsStrengthBridge(plugin, getMob, getEntity);
            Bukkit.getPluginManager().registerEvent(
                    (Class<? extends Event>) eventClass,
                    bridge,
                    EventPriority.HIGH,
                    (listener, event) -> bridge.handle(event),
                    plugin);
            return true;
        } catch (Throwable notEcoMobs) {
            return false;
        }
    }

    private void handle(Event event) {
        if (!plugin.conf().getBoolean("ecomobs.enabled", true)) {
            return;
        }
        Mob entity;
        try {
            Object mob = getMob.invoke(event);
            if (mob == null) {
                return;
            }
            if (!(getEntity.invoke(mob) instanceof Mob resolved)) {
                return;
            }
            entity = resolved;
        } catch (Throwable failed) {
            return;                              // EcoMobs changed shape — degrade to no scaling
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

    private static Attribute attribute(String key) {
        try {
            return Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
        } catch (Throwable t) {
            return null;                         // very old/odd API — the scale() guard handles null
        }
    }

    /** {@code 1 + level*scale}, clamped to {@code [1, max-multiplier]}. */
    private double multiplier(double level, String scaleKey, double scaleDefault) {
        double scale = plugin.conf().getDouble("ecomobs." + scaleKey, scaleDefault);
        double max = plugin.conf().getDouble("ecomobs.max-multiplier", 5.0);
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
