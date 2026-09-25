package com.mystipixel.royalskyblock.world.asp.loaders;

import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.loaders.SlimeLoader;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps the real loader and remembers how each world's most recent save went.
 *
 * <p>Needed because ASP hides save failures for loaded worlds. {@code AdvancedSlimePaperAPI.saveWorld}
 * on a loaded world runs {@code SlimeLevelInstance.save()}, whose background task calls
 * {@link SlimeLoader#saveWorld} inside {@code catch (Exception)}, logs "There was an issue saving world
 * {} asynchronously" and completes normally. The API call returns as if the blocks were on disk, so
 * nothing that trusts it can tell a full disk or a dead database from a good save. The loader is the
 * one place that sees the real outcome, so it records it here for {@code AspIslandWorldService} to
 * check once ASP says it is done.
 */
public final class SaveTrackingLoader implements SlimeLoader, AutoCloseable {

    /** One save's outcome. {@code sequence} orders saves across threads; {@code error} is null on success. */
    public record Outcome(long sequence, @Nullable IOException error) {
    }

    private final SlimeLoader delegate;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Outcome> lastSave = new ConcurrentHashMap<>();

    public SaveTrackingLoader(SlimeLoader delegate) {
        this.delegate = delegate;
    }

    /** A number no save has used yet: saves that finish after this call record a larger one. */
    public long mark() {
        return sequence.incrementAndGet();
    }

    /** The latest save of {@code worldName} to finish after {@code mark}, or null if none has. */
    public @Nullable Outcome outcomeSince(String worldName, long mark) {
        Outcome outcome = lastSave.get(worldName);
        return outcome != null && outcome.sequence() > mark ? outcome : null;
    }

    @Override
    public void saveWorld(String worldName, byte[] serializedWorld) throws IOException {
        try {
            delegate.saveWorld(worldName, serializedWorld);
            lastSave.put(worldName, new Outcome(sequence.incrementAndGet(), null));
        } catch (IOException e) {
            lastSave.put(worldName, new Outcome(sequence.incrementAndGet(), e));
            throw e;
        } catch (RuntimeException e) {
            lastSave.put(worldName, new Outcome(sequence.incrementAndGet(), new IOException(e.getMessage(), e)));
            throw e;
        }
    }

    @Override
    public void deleteWorld(String worldName) throws UnknownWorldException, IOException {
        delegate.deleteWorld(worldName);
        lastSave.remove(worldName);
    }

    @Override
    public byte[] readWorld(String worldName) throws UnknownWorldException, IOException {
        return delegate.readWorld(worldName);
    }

    @Override
    public boolean worldExists(String worldName) throws IOException {
        return delegate.worldExists(worldName);
    }

    @Override
    public List<String> listWorlds() throws IOException {
        return delegate.listWorlds();
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
