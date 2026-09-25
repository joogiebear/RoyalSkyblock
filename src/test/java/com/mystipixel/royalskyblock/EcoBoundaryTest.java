package com.mystipixel.royalskyblock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps eco and libreforge types confined to the files that already adapt them.
 *
 * <p>eco stays a hard dependency, but every eco release is a chance for a signature to move. The
 * fewer files that touch eco, the smaller an eco bump is to review, and the gameplay code (islands,
 * profiles, bank, upgrades, storage) stays plain Bukkit. A new file that needs eco should normally go
 * through one of these adapters; if it genuinely cannot, add it here deliberately.
 */
class EcoBoundaryTest {

    private static final Pattern ECO_IMPORT = Pattern.compile("^\\s*import\\s+com\\.willfp\\.", Pattern.MULTILINE);

    /** Paths relative to the package root, i.e. under {@code com/mystipixel/royalskyblock/}. */
    private static final Set<String> ALLOWED = Set.of(
            // Plugin base: extends LibreforgePlugin, owns lifecycle, reload, extensions and bStats.
            "RoyalSkyblockPlugin.kt",
            // Commands are eco PluginCommands.
            "command/BankCommand.java",
            "command/CommandIsland.kt",
            // Menus render through eco's Menu API.
            "gui/EcoMenuFactory.java",
            "gui/GuiManager.java",
            // eco items, profiles and placeholders.
            "hooks/EcoHook.java",
            "hooks/EcoProfileBridge.java",
            "hooks/EcoProfileResolver.kt",
            "island/StarterIslandBuilder.java",
            // The optional storage.type: eco backend.
            "data/EcoStorage.java",
            // libreforge triggers, conditions, holders and effect chains.
            "libreforge/EcoPlaceholders.kt",
            "libreforge/IslandConditions.kt",
            "libreforge/IslandTriggers.kt",
            "libreforge/LevelRewardChains.kt",
            "libreforge/MenuChains.kt",
            "libreforge/RoyalHolder.kt",
            "libreforge/RoyalHolders.kt",
            "libreforge/RoyalTrigger.kt"
    );

    @Test
    @DisplayName("only the eco adapter files import com.willfp")
    void ecoImportsStayInAdapters() throws IOException {
        Set<String> importing = ecoImporters();

        Set<String> unexpected = new TreeSet<>(importing);
        unexpected.removeAll(ALLOWED);
        assertTrue(unexpected.isEmpty(), "eco/libreforge imported outside the adapter files: " + unexpected
                + " — route it through an existing adapter, or add the file to EcoBoundaryTest.ALLOWED on purpose");

        // A stale entry would let a later file quietly reuse the exemption, so keep the list exact.
        Set<String> stale = new TreeSet<>(ALLOWED);
        stale.removeAll(importing);
        assertTrue(stale.isEmpty(), "EcoBoundaryTest.ALLOWED lists files that no longer import eco: " + stale);
    }

    private static Set<String> ecoImporters() throws IOException {
        Set<String> result = new TreeSet<>();
        for (String lang : new String[]{"java", "kotlin"}) {
            Path root = Path.of("src/main", lang, "com/mystipixel/royalskyblock");
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    if (ECO_IMPORT.matcher(Files.readString(file)).find()) {
                        result.add(root.relativize(file).toString().replace('\\', '/'));
                    }
                }
            }
        }
        assertTrue(!result.isEmpty(), "found no eco imports at all — is the test running from the module directory?");
        return result;
    }
}
