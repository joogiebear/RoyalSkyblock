package com.mystipixel.royalskyblock.hooks;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * {@link IslandMobProvider} backed by EcoMobs, accessed by reflection.
 *
 * <p>Reflection rather than a compile dependency because the registry lookup ({@code EcoMobs.getByID})
 * is inherited from libreforge's {@code RegistrableCategory}, which isn't resolvable from EcoMobs'
 * published artifacts — and reflection keeps the hook fully soft. It looks a mob up by id and spawns it
 * with {@code SpawnReason.COMMAND} (RoyalSkyblock is the deliberate spawner; vanilla monster spawning is
 * expected to be off on islands via the island gamerules), returning the spawned entity to be tagged.
 */
public final class EcoMobsIslandMobProvider implements IslandMobProvider {

    @Override
    public String id() {
        return "ecomobs";
    }

    @Override
    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("EcoMobs");
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Entity spawn(String mobId, Location location) {
        try {
            Class<?> ecoMobs = Class.forName("com.willfp.ecomobs.mob.EcoMobs");
            Object registry = ecoMobs.getField("INSTANCE").get(null);
            Object mob = ecoMobs.getMethod("getByID", String.class).invoke(registry, mobId);
            if (mob == null) {
                return null;                     // no such mob configured in EcoMobs
            }
            Class<?> spawnReason = Class.forName("com.willfp.ecomobs.mob.SpawnReason");
            Object command = Enum.valueOf((Class) spawnReason, "COMMAND");
            Class<?> ecoMob = Class.forName("com.willfp.ecomobs.mob.EcoMob");
            Object living = ecoMob.getMethod("spawn", Location.class, spawnReason).invoke(mob, location, command);
            if (living == null) {
                return null;
            }
            Class<?> livingMob = Class.forName("com.willfp.ecomobs.mob.LivingMob");
            Object entity = livingMob.getMethod("getEntity").invoke(living);
            return entity instanceof Entity e ? e : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
