package com.mystipixel.royalskyblock.api;

import com.mystipixel.royalskyblock.hooks.IslandMobProvider;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry where extensions announce mob and progression backends, and where RoyalSkyblock looks
 * them up.
 *
 * <p>Register from an extension's {@code onEnable}, which eco runs before the host's
 * {@code handleEnable}. The host's services don't exist yet at that point, so registering must
 * only hand over an object; anything needing {@code islands()}, {@code worlds()} or
 * {@code storage()} belongs in the extension's {@code onAfterLoad}.
 *
 * <p>Ids are matched case-insensitively against config values ({@code island-mobs.provider}).
 * Registering an id twice replaces the first, so a server owner's extension can override a
 * built-in one.
 */
public final class Integrations {

    private final Map<String, IslandMobProvider> mobProviders = new LinkedHashMap<>();
    private final Map<String, ProgressionProvider> progression = new LinkedHashMap<>();

    private static String key(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    /** Register a mob backend. Keyed on the provider's own {@link IslandMobProvider#id()}. */
    public void registerMobProvider(IslandMobProvider provider) {
        mobProviders.put(key(provider.id()), provider);
    }

    /** The mob backend with this id, or null if nothing registered one. */
    public @Nullable IslandMobProvider mobProvider(String id) {
        return mobProviders.get(key(id));
    }

    /** Every registered mob backend id, in registration order. */
    public Collection<String> mobProviderIds() {
        return List.copyOf(mobProviders.keySet());
    }

    /** Register a skills/stats backend. Keyed on the provider's own {@link ProgressionProvider#id()}. */
    public void registerProgressionProvider(ProgressionProvider provider) {
        progression.put(key(provider.id()), provider);
    }

    /** The progression backend with this id, or null if nothing registered one. */
    public @Nullable ProgressionProvider progressionProvider(String id) {
        return progression.get(key(id));
    }

    /**
     * The first registered progression backend that reports itself usable, or null if there is none.
     * For callers that want whatever skills plugin the server runs rather than a named one.
     */
    public @Nullable ProgressionProvider anyProgressionProvider() {
        for (ProgressionProvider provider : progression.values()) {
            if (provider.available()) {
                return provider;
            }
        }
        return null;
    }

    /** Every registered progression backend id, in registration order. */
    public Collection<String> progressionProviderIds() {
        return List.copyOf(progression.keySet());
    }
}
