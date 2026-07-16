package com.mystipixel.royalskyblock.border;

import java.util.Locale;

/**
 * The island border's appearance. Minecraft colours a world border by its motion, so we fake a colour
 * with an imperceptible perpetual lerp: static = blue, growing = green, shrinking = red.
 */
public enum BorderColor {
    OFF, BLUE, RED, GREEN;

    public static BorderColor from(String raw) {
        if (raw == null) {
            return BLUE;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BLUE;
        }
    }
}
