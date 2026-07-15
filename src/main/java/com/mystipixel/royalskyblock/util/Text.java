package com.mystipixel.royalskyblock.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Small text helper shared across the plugin. Uses Adventure under the hood so we stay on Paper's
 * modern API, while still accepting the familiar {@code &}-code strings used throughout the configs
 * (matching the rest of the Royal suite).
 */
public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    /** Parse an {@code &}-coded string into a Component (colours, formatting, hex via {@code &#rrggbb}). */
    public static Component color(String input) {
        return LEGACY.deserialize(input == null ? "" : input);
    }
}
