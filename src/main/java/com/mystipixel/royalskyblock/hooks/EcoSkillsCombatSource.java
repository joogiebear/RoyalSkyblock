package com.mystipixel.royalskyblock.hooks;

import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;

/**
 * {@link CombatLevelSource} backed by EcoSkills, accessed by reflection so RoyalSkyblock carries no
 * compile-time dependency on it (EcoSkills' published POM pulls unresolvable NMS submodules, and
 * reflection keeps the hook fully soft). Resolves the configured skill once via
 * {@code Skills.getByID(id)} and reads a player's level via {@code EcoSkillsAPI.getSkillLevel}.
 * {@link #valid()} is false if EcoSkills or the skill isn't present, in which case callers fall back.
 */
public final class EcoSkillsCombatSource implements CombatLevelSource {

    private final int fallback;
    private Object skill;                 // com.willfp.ecoskills.skills.Skill
    private Method getSkillLevel;         // static EcoSkillsAPI.getSkillLevel(OfflinePlayer, Skill) -> int

    public EcoSkillsCombatSource(String skillId, int fallback) {
        this.fallback = fallback;
        try {
            Class<?> skillsClass = Class.forName("com.willfp.ecoskills.skills.Skills");
            Object skillsInstance = skillsClass.getField("INSTANCE").get(null);
            this.skill = skillsClass.getMethod("getByID", String.class).invoke(skillsInstance, skillId);

            Class<?> skillClass = Class.forName("com.willfp.ecoskills.skills.Skill");
            Class<?> apiClass = Class.forName("com.willfp.ecoskills.api.EcoSkillsAPI");
            this.getSkillLevel = apiClass.getMethod("getSkillLevel", OfflinePlayer.class, skillClass);
        } catch (Throwable t) {
            this.skill = null;
            this.getSkillLevel = null;
        }
    }

    /** Whether EcoSkills and the configured skill both resolved. */
    public boolean valid() {
        return skill != null && getSkillLevel != null;
    }

    @Override
    public int levelOf(OfflinePlayer player) {
        if (!valid() || player == null) {
            return fallback;
        }
        try {
            Object result = getSkillLevel.invoke(null, player, skill);
            return result instanceof Integer i ? i : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
