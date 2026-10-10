package com.mystipixel.royalskyblock.world.asp.loaders;

import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.loaders.SlimeLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * A file-backed {@link SlimeLoader} storing each island as {@code <name>.slime} under a directory.
 * Shipped here because the ASP fork doesn't expose its loader classes on the plugin classpath.
 */
public final class RsbFileLoader implements SlimeLoader {

    private static final FilenameFilter SLIME_FILTER = (dir, name) -> name.endsWith(".slime");
    private static final String TEMP_SUFFIX = ".slime.tmp";

    private final File worldDir;

    public RsbFileLoader(File worldDir) {
        this.worldDir = worldDir;
        if (worldDir.exists() && !worldDir.isDirectory()) {
            throw new IllegalStateException("Slime world path exists but is not a directory: " + worldDir);
        }
        if (!worldDir.exists() && !worldDir.mkdirs()) {
            throw new IllegalStateException("Could not create slime world directory: " + worldDir);
        }
        // temp files left by a crash mid-save; the real .slime beside each is the last good copy
        File[] stale = worldDir.listFiles((dir, name) -> name.endsWith(TEMP_SUFFIX));
        if (stale != null) {
            for (File file : stale) {
                file.delete();
            }
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

    // Write to a temp file, flush, then rename into place, so a crash never truncates the only copy. Each
    // save gets its own temp file, so overlapping saves each produce a complete file.
    @Override
    public void saveWorld(String worldName, byte[] serializedWorld) throws IOException {
        Path target = fileFor(worldName).toPath();
        Path temp = Files.createTempFile(worldDir.toPath(), worldName + ".", TEMP_SUFFIX);
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(serializedWorld);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
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
