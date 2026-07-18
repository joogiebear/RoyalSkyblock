package com.mystipixel.royalskyblock.hooks;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;

/**
 * {@link CombatLevelSource} backed by EcoSkills, accessed by reflection so RoyalSkyblock carries no
 * compile-time dependency on it (EcoSkills' POM pulls unresolvable NMS submodules; reflection also keeps
 * the hook fully soft). {@link #valid()} reports whether the EcoSkills API classes are reachable.
 *
 * <p>The <em>skill</em> itself is resolved lazily on first use, not in the constructor: EcoSkills
 * registers its skills during its own enable, so the registry can still be empty when RoyalSkyblock
 * builds this at startup. By the time a mob actually spawns the registry is populated.
 */
public final class EcoSkillsCombatSource implements CombatLevelSource {

    private final String skillId;
    private final int fallback;

    private Object skillsInstance;   // Skills.INSTANCE
    private Method getByID;          // Skills.getByID(String) -> Skill?
    private Method getSkillLevel;    // static EcoSkillsAPI.getSkillLevel(OfflinePlayer, Skill) -> int
    private Object skill;            // resolved lazily
    private boolean setupOk;

    public EcoSkillsCombatSource(String skillId, int fallback) {
        this.skillId = skillId;
        this.fallback = fallback;
        try {
            Class<?> skillsClass = Class.forName("com.willfp.ecoskills.skills.Skills");
            this.skillsInstance = skillsClass.getField("INSTANCE").get(null);
            this.getByID = skillsClass.getMethod("getByID", String.class);
            Class<?> skillClass = Class.forName("com.willfp.ecoskills.skills.Skill");
            this.getSkillLevel = Class.forName("com.willfp.ecoskills.api.EcoSkillsAPI")
                    .getMethod("getSkillLevel", OfflinePlayer.class, skillClass);
            this.setupOk = true;
        } catch (Throwable t) {
            this.setupOk = false;
            Bukkit.getLogger().warning("[RoyalSkyblock] EcoSkills combat lookup unavailable: " + t);
        }
    }

    /** Whether the EcoSkills API reflection wired up (classes reachable). The skill resolves lazily. */
    public boolean valid() {
        return setupOk;
    }

    @Override
    public int levelOf(OfflinePlayer player) {
        if (!setupOk || player == null) {
            return fallback;
        }
        try {
            if (skill == null) {
                skill = getByID.invoke(skillsInstance, skillId);   // registry is populated by first spawn
            }
            if (skill == null) {
                return fallback;
            }
            Object result = getSkillLevel.invoke(null, player, skill);
            return result instanceof Integer i ? i : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
