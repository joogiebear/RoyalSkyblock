package com.mystipixel.royalskyblock.api;

import com.mystipixel.royalskyblock.hooks.CombatLevelSource;
import org.jetbrains.annotations.Nullable;

/**
 * A skills/stats backend that answers "what level is this player at X?". Which skill and stat are
 * read is admin config ({@code island-mobs.combat-skill}, {@code island-mobs.intimidation.stat}).
 * Implementations live in extensions and register with {@link Integrations} from {@code onEnable}.
 */
public interface ProgressionProvider {

    /** Stable id, matching the config value that selects this backend (e.g. {@code "ecoskills"}). */
    String id();

    /** Whether the backing plugin is installed and usable right now. */
    boolean available();

    /**
     * A reader for a named skill, or {@code null} if this backend has no such skill. Returning null lets
     * the caller warn about a misconfigured skill id and fall back.
     */
    @Nullable CombatLevelSource skill(String skillId, int fallback);

    /** A reader for a named stat, or {@code null} if this backend has no such stat. */
    @Nullable CombatLevelSource stat(String statId, int fallback);
}
