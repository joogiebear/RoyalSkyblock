package com.mystipixel.royalskyblock.perk;

import org.bukkit.potion.PotionEffectType;

/** One potion effect a perk grants while on your island. {@code amplifier} is 0-based (0 = level I). */
public record PerkEffect(PotionEffectType type, int amplifier) {
}
