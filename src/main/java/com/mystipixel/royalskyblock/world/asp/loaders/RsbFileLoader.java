package com.mystipixel.royalskyblock.world.asp.loaders;

import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.loaders.SlimeLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A file-backed {@link SlimeLoader} storing each island as a {@code <name>.slime} file under a
 * directory. RoyalSkyblock ships its own loader because the ASP fork exposes the world API but not
 * its loader classes on the plugin classpath.
 */
public final class RsbFileLoader implements SlimeLoader {

    private static final FilenameFilter SLIME_FILTER = (dir, name) -> name.endsWith(".slime");

    private final File worldDir;

    public RsbFileLoader(File worldDir) {
        this.worldDir = worldDir;
        if (worldDir.exists() && !worldDir.isDirectory()) {
            throw new IllegalStateException("Slime world path exists but is not a directory: " + worldDir);
        }
        if (!worldDir.exists() && !worldDir.mkdirs()) {
            throw new IllegalStateException("Could not create slime world directory: " + worldDir);
        }
    }

    private File fileFor(String worldName) {
        return new File(worldDir, worldName + ".slime");
    }

    @Override
    public byte[] readWorld(String worldName) throws UnknownWorldException, IOException {
        if (!worldExists(worldName)) {
            throw new UnknownWorldException(worldName);
        }
        try (FileInputStream in = new FileInputStream(fileFor(worldName))) {
            return in.readAllBytes();
        }
    }

    @Override
    public boolean worldExists(String worldName) {
        return fileFor(worldName).exists();
    }

    @Override
    public List<String> listWorlds() {
        String[] names = worldDir.list(SLIME_FILTER);
        List<String> worlds = new ArrayList<>();
        if (names != null) {
            for (String name : names) {
                worlds.add(name.substring(0, name.length() - ".slime".length()));
            }
        }
        return worlds;
    }

    @Override
    public void saveWorld(String worldName, byte[] serializedWorld) throws IOException {
        try (FileOutputStream out = new FileOutputStream(fileFor(worldName))) {
            out.write(serializedWorld);
        }
    }

    @Override
    public void deleteWorld(String worldName) throws UnknownWorldException, IOException {
        File file = fileFor(worldName);
        if (!file.exists()) {
            throw new UnknownWorldException(worldName);
        }
        if (!file.delete()) {
            throw new IOException("Could not delete slime world file: " + file);
        }
    }
}
