package com.mystipixel.royalskyblock.api;

import com.mystipixel.royalskyblock.hooks.IslandMobProvider;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where an extension announces what it can supply, and where RoyalSkyblock looks for it.
 *
 * <p>Support for a third-party plugin — a mob backend, a skills backend — is an extension that
 * registers here, not a branch inside this plugin's enable. Adding MythicMobs support becomes a new
 * jar; it changes nothing in RoyalSkyblock and nothing in any other extension.
 *
 * <h2>When to register</h2>
 *
 * <p>From an extension's {@code onEnable}, which eco runs <b>before</b> the host's {@code handleEnable}.
 * That ordering is the reason a registry works at all: everything registered by an extension is in
 * place by the time RoyalSkyblock reads it to start island mob spawning.
 *
 * <p>The corollary is that the host's services do <em>not</em> exist yet at that moment. Registering
 * must therefore hand over an object and touch nothing — anything needing {@code islands()},
 * {@code worlds()} or {@code storage()} belongs in the extension's {@code onAfterLoad}.
 *
 * <p>This registry is built when the plugin is constructed rather than in {@code handleEnable},
 * precisely so that it exists before the first extension enables.
 *
 * <h2>Ids</h2>
 *
 * <p>Ids are matched case-insensitively against config values (<code>island-mobs.provider</code>), so
 * an admin typing {@code EcoMobs} gets the provider registered as {@code ecomobs}. Registering an id
 * twice replaces the first: an extension shipped by a server owner is meant to be able to override a
 * built-in one without having to remove it.
 */
public final class Integrations {

    private final Map<String, IslandMobProvider> mobProviders = new LinkedHashMap<>();
    private final Map<String, ProgressionProvider> progression = new LinkedHashMap<>();

    private static String key(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    // ── mob backends ───────────────────────────────────────────────────────────

    /** Register a mob backend. Keyed on the provider's own {@link IslandMobProvider#id()}. */
    public void registerMobProvider(IslandMobProvider provider) {
        mobProviders.put(key(provider.id()), provider);
    }

    /** The mob backend with this id, or null if nothing registered one. */
    public @Nullable IslandMobProvider mobProvider(String id) {
        return mobProviders.get(key(id));
    }

    /** Every registered mob backend id, in registration order — for diagnostics and error messages. */
    public Collection<String> mobProviderIds() {
        return List.copyOf(mobProviders.keySet());
    }

    // ── progression backends ───────────────────────────────────────────────────

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
     *
     * <p>For callers that want "whatever skills plugin this server runs" rather than a named one. With
     * a single backend installed — the normal case — this saves an admin configuring a name to select
     * the only option available.
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
