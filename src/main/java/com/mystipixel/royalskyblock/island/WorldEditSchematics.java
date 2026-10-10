package com.mystipixel.royalskyblock.island;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import org.jetbrains.annotations.Nullable;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * WorldEdit / FastAsyncWorldEdit implementation of {@link SchematicService}. Only instantiated when
 * WorldEdit is on the classpath. Reads/writes {@code .schem} files under {@code schematics/}.
 */
public final class WorldEditSchematics implements SchematicService {

    private final RoyalSkyblockPlugin plugin;

    public WorldEditSchematics(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private File schematicsDir() {
        File dir = new File(plugin.getDataFolder(), "schematics");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    // The file behind a schematic name, or null. Accepts .schematic too: ClipboardFormats.findByFile
    // detects the format from the contents.
    private @Nullable File resolve(String name) {
        for (String extension : new String[]{".schem", ".schematic"}) {
            File candidate = new File(schematicsDir(), name + extension);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    /** Whether a name resolves to a file, so the config check can report a typo at boot. */
    public boolean exists(String name) {
        return name != null && !name.isBlank() && resolve(name) != null;
    }

    @Override
    public boolean tryPasteSchematic(World bukkitWorld, int x, int y, int z, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        File file = resolve(name);
        if (file == null) {
            // say so: otherwise the caller silently falls back to the built-in generator
            plugin.getLogger().warning("Starter schematic '" + name + "' not found: looked for '"
                    + name + ".schem' and '" + name + ".schematic' in the schematics folder. Using the "
                    + "built-in generator instead.");
            return false;
        }
        try {
            com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat format =
                    com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats.findByFile(file);
            if (format == null) {
                plugin.getLogger().warning("Unknown schematic format: " + file.getName());
                return false;
            }
            com.sk89q.worldedit.extent.clipboard.Clipboard clipboard;
            try (FileInputStream in = new FileInputStream(file);
                 com.sk89q.worldedit.extent.clipboard.io.ClipboardReader reader = format.getReader(in)) {
                clipboard = reader.read();
            }
            com.sk89q.worldedit.world.World weWorld =
                    com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(bukkitWorld);
            try (com.sk89q.worldedit.EditSession session =
                         com.sk89q.worldedit.WorldEdit.getInstance().newEditSession(weWorld)) {
                com.sk89q.worldedit.function.operation.Operation op =
                        new com.sk89q.worldedit.session.ClipboardHolder(clipboard)
                                .createPaste(session)
                                .to(com.sk89q.worldedit.math.BlockVector3.at(x, y, z))
                                .ignoreAirBlocks(true)
                                .build();
                com.sk89q.worldedit.function.operation.Operations.complete(op);
            }
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to paste schematic '" + name + "': " + t.getMessage());
            return false;
        }
    }

    @Override
    public String saveSelection(Player player, String name) {
        // the name becomes a file path; "../" in it would write outside the schematics folder
        if (!name.matches("[A-Za-z0-9_-]{1,64}")) {
            return "Schematic names may only use letters, digits, _ and - (up to 64 characters).";
        }
        try {
            com.sk89q.worldedit.entity.Player actor = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(player);
            com.sk89q.worldedit.LocalSession session =
                    com.sk89q.worldedit.WorldEdit.getInstance().getSessionManager().get(actor);
            com.sk89q.worldedit.world.World weWorld =
                    com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(player.getWorld());
            com.sk89q.worldedit.regions.Region region = session.getSelection(weWorld);

            com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard clipboard =
                    new com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard(region);
            clipboard.setOrigin(region.getMinimumPoint());
            try (com.sk89q.worldedit.EditSession editSession =
                         com.sk89q.worldedit.WorldEdit.getInstance().newEditSession(weWorld)) {
                com.sk89q.worldedit.function.operation.ForwardExtentCopy copy =
                        new com.sk89q.worldedit.function.operation.ForwardExtentCopy(
                                editSession, region, clipboard, region.getMinimumPoint());
                copy.setCopyingEntities(false);
                com.sk89q.worldedit.function.operation.Operations.complete(copy);
            }

            File file = new File(schematicsDir(), name + ".schem");
            try (FileOutputStream out = new FileOutputStream(file);
                 com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter writer =
                         com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(out)) {
                writer.write(clipboard);
            }
            return null;
        } catch (com.sk89q.worldedit.IncompleteRegionException e) {
            return "Make a WorldEdit selection first (//wand, then left/right-click two corners).";
        } catch (Throwable t) {
            return "Couldn't save schematic: " + t.getMessage();
        }
    }
}
