package com.mystipixel.royalskyblock.api;

import com.mystipixel.royalskyblock.hooks.CombatLevelSource;
import org.jetbrains.annotations.Nullable;

/**
 * A backend that can answer "what level is this player at X?" — skills and stats.
 *
 * <p>A provider rather than a source, because which skill and which stat get read are admin config
 * (<code>island-mobs.combat-skill</code>, <code>island-mobs.intimidation.stat</code>), and the
 * fallback used when the backend can't answer is the caller's decision. RoyalSkyblock asks for the
 * skill it was configured to ask for; the provider says whether it has one by that name.
 *
 * <p>Implementations live in extensions, one per backend — EcoSkills today, anything else later —
 * and are registered with {@link Integrations} from the extension's {@code onEnable}.
 */
public interface ProgressionProvider {

    /** Stable id, matching the config value that selects this backend (e.g. {@code "ecoskills"}). */
    String id();

    /** Whether the backing plugin is installed and usable right now. */
    boolean available();

    /**
     * A reader for a named skill, or {@code null} if this backend has no such skill.
     *
     * <p>Returning null is how a misconfigured skill id is reported — the caller warns and falls back
     * rather than every player silently reading as level {@code fallback} forever.
     */
    @Nullable CombatLevelSource skill(String skillId, int fallback);

    /** A reader for a named stat, or {@code null} if this backend has no such stat. */
    @Nullable CombatLevelSource stat(String statId, int fallback);
}
