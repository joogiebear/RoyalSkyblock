package com.mystipixel.royalskyblock.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slicing behind {@code /is admin split-content}.
 *
 * <p>This rewrites configs an admin hand-wrote and cannot easily reconstruct, so the cases that get
 * tested are the ones that would quietly lose something: a banner swallowed by the first item, a
 * comment attached to the wrong side of a boundary, an indent guessed rather than verified.
 */
class ContentSplitterTest {

    private static final List<String> UPGRADES = List.of(
            "# ═══════════════════════════════",
            "#  RoyalSkyblock — Upgrades",
            "#  Each top-level key is one track.",
            "# ═══════════════════════════════",
            "",
            "size:",
            "  display-name: \"&aIsland Size\"",
            "  tiers:",
            "    1:",
            "      value: 84",
            "",
            "# Sanctuary is made entirely of effects.",
            "#   value is 0 on every tier.",
            "sanctuary:",
            "  effect: minions",
            "  tiers:",
            "    1:",
            "      value: 0",
            "");

    @Test
    @DisplayName("the file banner stays with the file, not with the first item")
    void bannerIsNotAdoptedByTheFirstItem() {
        Map<String, ContentSplitter.Block> blocks = ContentSplitter.sliceBlocks(UPGRADES, 0, null);

        assertEquals(List.of("size", "sanctuary"), List.copyOf(blocks.keySet()));
        assertTrue(blocks.get("size").comment().isEmpty(),
                "the banner documents every track — giving it to the first one loses it for the rest");
    }

    @Test
    @DisplayName("a comment above an item belongs to that item")
    void ownCommentTravelsWithItsItem() {
        Map<String, ContentSplitter.Block> blocks = ContentSplitter.sliceBlocks(UPGRADES, 0, null);

        assertIterableEquals(
                List.of("# Sanctuary is made entirely of effects.", "#   value is 0 on every tier."),
                blocks.get("sanctuary").comment());
        assertFalse(blocks.get("size").body().contains("# Sanctuary is made entirely of effects."),
                "the next item's comment must not be left on the end of the previous body");
    }

    @Test
    @DisplayName("a body keeps its own shape and loses its trailing blanks")
    void bodyIsCutCleanly() {
        Map<String, ContentSplitter.Block> blocks = ContentSplitter.sliceBlocks(UPGRADES, 0, null);

        assertIterableEquals(
                List.of("  display-name: \"&aIsland Size\"", "  tiers:", "    1:", "      value: 84"),
                blocks.get("size").body());
    }

    @Test
    @DisplayName("items under a container are found at their own depth, not deeper")
    void containerScoping() {
        List<String> perks = List.of(
                "enabled: true",
                "effect-refresh-seconds: 6",
                "",
                "perks:",
                "  haste:",
                "    name: \"&eHaste\"",
                "    effects:",
                "      haste: 0",
                "  scholar:",
                "    name: \"&bScholar\"");

        Map<String, ContentSplitter.Block> blocks = ContentSplitter.sliceBlocks(perks, 2, "perks");

        assertEquals(List.of("haste", "scholar"), List.copyOf(blocks.keySet()));
        assertIterableEquals(List.of("    name: \"&eHaste\"", "    effects:", "      haste: 0"),
                blocks.get("haste").body());
    }

    @Test
    @DisplayName("settings above the container are never mistaken for content")
    void settingsAreNotItems() {
        // enabled: and effect-refresh-seconds: sit at indent 0 with values, so they must not match —
        // splitting them out would produce perks named after the plugin's own switches.
        List<String> perks = List.of("enabled: true", "perks:", "  haste:", "    name: x");

        assertEquals(List.of("haste"), List.copyOf(ContentSplitter.sliceBlocks(perks, 2, "perks").keySet()));
    }

    @Test
    @DisplayName("a file with no container section splits into nothing")
    void missingContainerIsNotAnError() {
        List<String> settingsOnly = List.of("enabled: true", "effect-refresh-seconds: 6");

        assertTrue(ContentSplitter.sliceBlocks(settingsOnly, 2, "perks").isEmpty());
    }

    @Test
    @DisplayName("dedent refuses rather than guessing when a line is too shallow")
    void dedentRefusesBadIndentation() {
        assertNull(ContentSplitter.dedent(List.of("  ok: 1", " bad: 2"), 2),
                "emitting a differently-shaped file is worse than declining to split it");
        assertIterableEquals(List.of("ok: 1", ""), ContentSplitter.dedent(List.of("  ok: 1", ""), 2));
    }

    @Test
    @DisplayName("a rendered file is the header, the item's comment, then the body at column 0")
    void renderedFileShape() {
        Map<String, ContentSplitter.Block> blocks = ContentSplitter.sliceBlocks(UPGRADES, 0, null);

        String rendered = ContentSplitter.renderFile("# banner", blocks.get("sanctuary"), 0, 2);

        assertNotNull(rendered);
        assertEquals("""
                # banner

                # Sanctuary is made entirely of effects.
                #   value is 0 on every tier.

                effect: minions
                tiers:
                  1:
                    value: 0
                """, rendered);
    }

    @Test
    @DisplayName("comment alignment survives the shift")
    void commentAlignmentIsPreserved() {
        // The aligned documentation tables are exactly what a dump-and-reload would destroy, and the
        // reason this slices text at all — so a split that flattens them has failed at its one job.
        List<String> perks = List.of(
                "perks:",
                "  # name   shown in the menu",
                "  # icon   menu item",
                "  haste:",
                "    name: x");

        String rendered = ContentSplitter.renderFile("# h",
                ContentSplitter.sliceBlocks(perks, 2, "perks").get("haste"), 2, 4);

        assertTrue(rendered.contains("# name   shown in the menu"), "inner spacing must be untouched");
        assertFalse(rendered.contains("  # name"), "the block should be shifted to column 0");
    }
}
