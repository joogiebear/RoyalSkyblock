package com.mystipixel.royalskyblock.hooks;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;

/**
 * Reads an EcoSkills <em>stat</em> (e.g. {@code intimidation}) for a player, by reflection — same
 * approach as {@link EcoSkillsCombatSource}, so RoyalSkyblock keeps no compile-time dependency on
 * EcoSkills and runs fine without it.
 *
 * <p>Uses {@code getStatLevel}, which includes bonuses from modifiers, so a stat granted by a talisman
 * (Talismans' {@code add_stat} effect) is counted. The stat is resolved lazily on first use: EcoSkills
 * registers its stats during its own enable, so the registry can still be empty at RoyalSkyblock startup.
 */
public final class EcoSkillsStatSource implements CombatLevelSource {

    private final String statId;
    private final int fallback;

    private Object statsInstance;   // Stats.INSTANCE
    private Method getByID;         // Stats.getByID(String) -> Stat?
    private Method getStatLevel;    // static EcoSkillsAPI.getStatLevel(OfflinePlayer, Stat) -> int
    private Object stat;            // resolved lazily
    private boolean setupOk;

    public EcoSkillsStatSource(String statId, int fallback) {
        this.statId = statId;
        this.fallback = fallback;
        try {
            Class<?> statsClass = Class.forName("com.willfp.ecoskills.stats.Stats");
            this.statsInstance = statsClass.getField("INSTANCE").get(null);
            this.getByID = statsClass.getMethod("getByID", String.class);
            Class<?> statClass = Class.forName("com.willfp.ecoskills.stats.Stat");
            this.getStatLevel = Class.forName("com.willfp.ecoskills.api.EcoSkillsAPI")
                    .getMethod("getStatLevel", OfflinePlayer.class, statClass);
            this.setupOk = true;
        } catch (Throwable t) {
            this.setupOk = false;
            Bukkit.getLogger().warning("[RoyalSkyblock] EcoSkills stat lookup unavailable: " + t);
        }
    }

    public boolean valid() {
        return setupOk;
    }

    @Override
    public int levelOf(OfflinePlayer player) {
        if (!setupOk || player == null) {
            return fallback;
        }
        try {
            if (stat == null) {
                stat = getByID.invoke(statsInstance, statId);
            }
            if (stat == null) {
                return fallback;
            }
            Object result = getStatLevel.invoke(null, player, stat);
            return result instanceof Integer i ? i : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
