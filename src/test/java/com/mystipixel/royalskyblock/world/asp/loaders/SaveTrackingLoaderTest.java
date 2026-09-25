package com.mystipixel.royalskyblock.world.asp.loaders;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SaveTrackingLoaderTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a failed save is visible after the fact, even when the caller never saw the exception")
    void failedSaveIsRecorded() throws Exception {
        SaveTrackingLoader loader = new SaveTrackingLoader(new RsbFileLoader(dir.toFile()));
        long mark = loader.mark();
        assertNull(loader.outcomeSince("island_a", mark), "nothing has saved yet");

        loader.saveWorld("island_a", new byte[]{1});
        SaveTrackingLoader.Outcome ok = loader.outcomeSince("island_a", mark);
        assertNotNull(ok);
        assertNull(ok.error());

        long second = loader.mark();
        SaveTrackingLoader failing = new SaveTrackingLoader(new FailingLoader(dir));
        long failMark = failing.mark();
        assertThrows(IOException.class, () -> failing.saveWorld("island_b", new byte[]{2}));
        assertNotNull(failing.outcomeSince("island_b", failMark).error());
        assertNull(loader.outcomeSince("island_a", second), "an older save does not count as a newer one");
    }

    /** RsbFileLoader is final, so the failing case is its own loader. */
    private static final class FailingLoader implements com.infernalsuite.asp.api.loaders.SlimeLoader {
        private final RsbFileLoader real;

        FailingLoader(Path dir) {
            this.real = new RsbFileLoader(dir.toFile());
        }

        @Override public byte[] readWorld(String n) throws com.infernalsuite.asp.api.exceptions.UnknownWorldException, IOException { return real.readWorld(n); }
        @Override public boolean worldExists(String n) { return real.worldExists(n); }
        @Override public java.util.List<String> listWorlds() { return real.listWorlds(); }
        @Override public void saveWorld(String n, byte[] d) throws IOException { throw new IOException("disk full"); }
        @Override public void deleteWorld(String n) throws com.infernalsuite.asp.api.exceptions.UnknownWorldException, IOException { real.deleteWorld(n); }
    }
}
