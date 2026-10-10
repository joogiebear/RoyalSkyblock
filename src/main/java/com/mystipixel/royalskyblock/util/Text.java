package com.mystipixel.royalskyblock.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Text helper on Adventure that accepts the {@code &}-code strings used throughout the configs.
 */
public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();

    private Text() {
    }

    /** Parse an {@code &}-coded string into a Component (colours, formatting, hex via {@code &#rrggbb}). */
    public static Component color(String input) {
        return LEGACY.deserialize(input == null ? "" : input);
    }

    /**
     * The same colouring as {@link #color}, as a legacy {@code §}-formatted String for APIs that still take
     * one (eco's {@code MenuBuilder.setTitle}). Goes through the serializer so hex colours keep working.
     */
    public static String legacy(String input) {
        return SECTION.serialize(color(input));
    }
}
