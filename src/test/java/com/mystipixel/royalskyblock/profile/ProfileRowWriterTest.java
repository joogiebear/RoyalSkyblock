package com.mystipixel.royalskyblock.profile;

import com.mystipixel.royalskyblock.data.Storage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileRowWriterTest {

    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID OTHER_PROFILE = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();

    // stands in for the storage thread: holds tasks until the test runs them
    private static final class HeldExecutor implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.poll().run();
            }
        }
    }

    // Storage that records profile_data writes in order (Mockito can't instrument on this JDK)
    private static final class Recorder {
        record Call(String op, UUID profileId, UUID player, ProfileData data) {
        }

        final List<Call> calls = new CopyOnWriteArrayList<>();
        Consumer<ProfileData> beforeSave = data -> {
        };

        Storage storage() {
            return (Storage) Proxy.newProxyInstance(Storage.class.getClassLoader(), new Class<?>[]{Storage.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "saveProfileData" -> {
                            beforeSave.accept((ProfileData) args[2]);
                            calls.add(new Call("save", (UUID) args[0], (UUID) args[1], (ProfileData) args[2]));
                            yield true;
                        }
                        case "deleteProfileData" -> {
                            calls.add(new Call("delete", (UUID) args[0], (UUID) args[1], null));
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        List<ProfileData> saved() {
            return calls.stream().filter(c -> c.op().equals("save")).map(Call::data).toList();
        }
    }

    private static ProfileData row(int level) {
        return new ProfileData(new byte[]{(byte) level}, new byte[0], level, 0f, 20.0, 20, 5f);
    }

    @Test
    @DisplayName("a background save captures now and writes only when the storage thread runs it")
    void snapshotThenAsyncWrite() {
        Recorder storage = new Recorder();
        HeldExecutor background = new HeldExecutor();
        ProfileRowWriter writer = new ProfileRowWriter(storage.storage(), background);
        ProfileData snapshot = row(7);

        writer.saveLater(PROFILE, PLAYER, snapshot);
        assertTrue(storage.calls.isEmpty(), "nothing may be written on the calling thread");
        assertEquals(1, background.tasks.size());

        background.runAll();
        assertEquals(1, storage.calls.size());
        Recorder.Call call = storage.calls.get(0);
        assertEquals(PROFILE, call.profileId());
        assertEquals(PLAYER, call.player());
        assertSame(snapshot, call.data());
    }

    @Test
    @DisplayName("a queued background save never overwrites a quit or switch save made after its snapshot")
    void olderSnapshotNeverOverwritesNewer() {
        Recorder storage = new Recorder();
        HeldExecutor background = new HeldExecutor();
        ProfileRowWriter writer = new ProfileRowWriter(storage.storage(), background);
        ProfileData periodic = row(1);
        ProfileData quit = row(2);

        writer.saveLater(PROFILE, PLAYER, periodic);
        writer.saveNow(PROFILE, PLAYER, quit);
        background.runAll();

        assertEquals(List.of(quit), storage.saved());
    }

    @Test
    @DisplayName("of two queued background saves of one row, only the later is written")
    void laterBackgroundSaveWins() {
        Recorder storage = new Recorder();
        HeldExecutor background = new HeldExecutor();
        ProfileRowWriter writer = new ProfileRowWriter(storage.storage(), background);
        ProfileData first = row(1);
        ProfileData second = row(2);

        writer.saveLater(PROFILE, PLAYER, first);
        writer.saveLater(PROFILE, PLAYER, second);
        background.runAll();

        assertEquals(List.of(second), storage.saved());
    }

    @Test
    @DisplayName("a queued background save can't bring back a row deleted after it (coop payout)")
    void deleteSupersedesQueuedSave() {
        Recorder storage = new Recorder();
        HeldExecutor background = new HeldExecutor();
        ProfileRowWriter writer = new ProfileRowWriter(storage.storage(), background);

        writer.saveLater(PROFILE, PLAYER, row(1));
        writer.deleteNow(PROFILE, PLAYER);
        background.runAll();

        assertEquals(1, storage.calls.size());
        assertEquals("delete", storage.calls.get(0).op());
    }

    @Test
    @DisplayName("after a quit, queued saves from that session are skipped and the next session saves normally")
    void forgetOnQuit() {
        Recorder storage = new Recorder();
        HeldExecutor background = new HeldExecutor();
        ProfileRowWriter writer = new ProfileRowWriter(storage.storage(), background);
        ProfileData stale = row(1);
        ProfileData quit = row(2);
        ProfileData nextSession = row(3);

        writer.saveLater(PROFILE, PLAYER, stale);
        writer.saveNow(PROFILE, PLAYER, quit);
        writer.forget(PLAYER);
        assertEquals(0, writer.trackedRows());

        writer.saveLater(PROFILE, PLAYER, nextSession);   // rejoined before the storage thread caught up
        background.runAll();

        assertEquals(List.of(quit, nextSession), storage.saved());
    }

    @Test
    @DisplayName("rows of different profiles don't supersede each other")
    void rowsAreIndependent() {
        Recorder storage = new Recorder();
        HeldExecutor background = new HeldExecutor();
        ProfileRowWriter writer = new ProfileRowWriter(storage.storage(), background);
        ProfileData coop = row(1);
        ProfileData own = row(2);

        writer.saveLater(PROFILE, PLAYER, coop);
        writer.saveNow(OTHER_PROFILE, PLAYER, own);
        background.runAll();

        assertEquals(List.of(own, coop), storage.saved());
    }

    @Test
    @DisplayName("a quit save that arrives mid background write lands after it, not before")
    void quitDuringBackgroundWriteLandsLast() throws Exception {
        Recorder storage = new Recorder();
        HeldExecutor background = new HeldExecutor();
        ProfileRowWriter writer = new ProfileRowWriter(storage.storage(), background);
        ProfileData periodic = row(1);
        ProfileData quit = row(2);

        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        storage.beforeSave = data -> {
            if (data == periodic) {
                writing.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
        };

        writer.saveLater(PROFILE, PLAYER, periodic);
        Thread storageThread = new Thread(background::runAll);
        storageThread.start();
        assertTrue(writing.await(5, TimeUnit.SECONDS));

        Thread mainThread = new Thread(() -> writer.saveNow(PROFILE, PLAYER, quit));
        mainThread.start();
        mainThread.join(200);
        assertTrue(mainThread.isAlive(), "the quit save should wait for the write already in flight");

        release.countDown();
        storageThread.join(5000);
        mainThread.join(5000);

        assertEquals(List.of(periodic, quit), storage.saved());
    }
}
