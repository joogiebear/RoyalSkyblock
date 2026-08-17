package com.mystipixel.royalskyblock.hooks;

import com.mystipixel.royalskyblock.api.ProgressionProvider;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

/**
 * EcoSkills as a {@link ProgressionProvider}.
 *
 * <p>A thin adapter over {@link EcoSkillsCombatSource} and {@link EcoSkillsStatSource}, which already
 * do the reflective work. It exists so that "which skills plugin" becomes a registry lookup rather
 * than an {@code isPluginEnabled("EcoSkills")} branch inside the plugin's enable — the same shape a
 * MythicMobs or AureliumSkills backend would take, in its own extension.
 *
 * <p>This class still ships inside RoyalSkyblock as the built-in. It is registered only when nothing
 * else has claimed the {@code ecoskills} id, so an extension can replace it without this having to be
 * removed first.
 */
public final class EcoSkillsProgression implements ProgressionProvider {

    public static final String ID = "ecoskills";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("EcoSkills");
    }

    /**
     * A reader for a skill, or null if EcoSkills can't be reached.
     *
     * <p>Null means "no backend", not "no such skill". The skill id itself resolves lazily inside the
     * source — EcoSkills registers its skills during its own enable, which can be after this is built —
     * so whether {@code combat} exists genuinely cannot be answered yet, and pretending otherwise here
     * would report a healthy config as broken at every startup.
     */
    @Override
    public @Nullable CombatLevelSource skill(String skillId, int fallback) {
        EcoSkillsCombatSource source = new EcoSkillsCombatSource(skillId, fallback);
        return source.valid() ? source : null;
    }

    @Override
    public @Nullable CombatLevelSource stat(String statId, int fallback) {
        EcoSkillsStatSource source = new EcoSkillsStatSource(statId, fallback);
        return source.valid() ? source : null;
    }
}
