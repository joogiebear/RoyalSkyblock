package com.mystipixel.royalskyblock.island;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Island schematic pasting/saving, abstracted from WorldEdit so the WE-referencing implementation is
 * only loaded when WorldEdit/FAWE is present. The plugin picks {@code WorldEditSchematics} when WE is
 * on the classpath and {@code NoOpSchematics} otherwise (guarded by a {@code Class.forName} check), so
 * a server without WorldEdit never links {@code com.sk89q.worldedit.*}.
 */
public interface SchematicService {

    /** Whether a WorldEdit/FAWE backend is available. */
    boolean isAvailable();

    /**
     * Paste the named {@code schematics/<name>.schem} at {@code (x,y,z)}. Returns {@code false} — so the
     * caller falls back to the code generator — if unavailable, the file is missing, or the paste fails.
     */
    boolean tryPasteSchematic(World world, int x, int y, int z, String name);

    /** Save the player's WorldEdit selection to {@code schematics/<name>.schem}. Null = success, else error. */
    @Nullable String saveSelection(Player player, String name);
}
