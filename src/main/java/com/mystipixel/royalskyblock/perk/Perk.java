package com.mystipixel.royalskyblock.perk;

import org.bukkit.Material;

import java.util.List;

/**
 * A level-gated perk: unlocked when your island reaches {@code requiredLevel}. Grants {@code effects}
 * (potion effects applied while on your island) and/or runs {@code unlockCommands} once, the first time
 * the island reaches the level. Perks are opt-in (disabled by default).
 */
public record Perk(String id, String name, Material icon, int requiredLevel,
                   List<String> description, List<PerkEffect> effects, List<String> unlockCommands) {
}
