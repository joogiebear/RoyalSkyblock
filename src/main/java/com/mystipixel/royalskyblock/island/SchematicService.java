package com.mystipixel.royalskyblock.island;

import com.mystipixel.royalskyblock.message.Reason;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Island schematic pasting/saving behind an interface, so {@code com.sk89q.worldedit.*} is only linked
 * when WorldEdit/FAWE is present ({@code WorldEditSchematics}); otherwise {@code NoOpSchematics}.
 */
public interface SchematicService {

    /** Whether a WorldEdit/FAWE backend is available. */
    boolean isAvailable();

    /**
     * Paste {@code schematics/<name>.schem} at {@code (x,y,z)}. Returns {@code false} (the caller then
     * uses the code generator) if unavailable, the file is missing, or the paste fails.
     */
    boolean tryPasteSchematic(World world, int x, int y, int z, String name);

    /** Save the player's WorldEdit selection to {@code schematics/<name>.schem}. Null on success, else why not. */
    @Nullable Reason saveSelection(Player player, String name);
}
