package com.mystipixel.royalskyblock.upgrade;

import com.mystipixel.royalskyblock.currency.Cost;
import com.mystipixel.royalskyblock.data.Storage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpgradeTickHostOnlyTest {

    private static final Cost FREE = new Cost("", 0);

    private static UpgradeDef def(String key, UpgradeEffect effect) {
        return new UpgradeDef(key, key, "stone", "", effect, List.of(
                new UpgradeTier(1, 75, FREE, FREE, 10, List.of()),
                new UpgradeTier(2, 100, FREE, FREE, 10, List.of())));
    }

    // a store with fixed due rows that records what the tick asked of it
    private static final class FakeStore {
        final List<PendingUpgrade> due;
        final Set<PendingUpgrade> finishedElsewhere;
        final List<Collection<UUID>> askedFor = new ArrayList<>();
        final List<String> calls = new ArrayList<>();

        FakeStore(List<PendingUpgrade> due, Set<PendingUpgrade> finishedElsewhere) {
            this.due = due;
            this.finishedElsewhere = finishedElsewhere;
        }

        @SuppressWarnings("unchecked")
        Storage storage() {
            return (Storage) Proxy.newProxyInstance(Storage.class.getClassLoader(), new Class<?>[]{Storage.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "duePending" -> {
                            Collection<UUID> ids = (Collection<UUID>) args[1];
                            askedFor.add(ids);
                            yield due.stream().filter(p -> ids.contains(p.islandId())).toList();
                        }
                        case "completePending" -> {
                            PendingUpgrade p = (PendingUpgrade) args[0];
                            calls.add("complete " + p.upgradeKey() + " radius " + args[1]);
                            yield !finishedElsewhere.contains(p);
                        }
                        case "deletePending" -> {
                            calls.add("delete " + args[1]);
                            yield 1;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    @Test
    void onlyTheHostedIslandsAreAskedFor() {
        UUID hosted = UUID.randomUUID();
        UUID elsewhere = UUID.randomUUID();
        FakeStore store = new FakeStore(List.of(
                new PendingUpgrade(hosted, "size", 1, 0L),
                new PendingUpgrade(elsewhere, "size", 1, 0L)), Set.of());
        List<PendingUpgrade> done = UpgradeManager.claimDue(store.storage(), 1_000L, Set.of(hosted),
                Map.of("size", def("size", UpgradeEffect.RADIUS)));
        assertEquals(List.of(Set.of(hosted)), store.askedFor);
        assertEquals(List.of(new PendingUpgrade(hosted, "size", 1, 0L)), done);
        assertEquals(List.of("complete size radius 75"), store.calls);
    }

    @Test
    void anUpgradeFinishedElsewhereIsNotReportedAgain() {
        UUID island = UUID.randomUUID();
        PendingUpgrade raced = new PendingUpgrade(island, "size", 2, 0L);
        PendingUpgrade mine = new PendingUpgrade(island, "guest-limit", 1, 0L);
        FakeStore store = new FakeStore(List.of(raced, mine), Set.of(raced));
        List<PendingUpgrade> done = UpgradeManager.claimDue(store.storage(), 1_000L, Set.of(island), Map.of(
                "size", def("size", UpgradeEffect.RADIUS),
                "guest-limit", def("guest-limit", UpgradeEffect.GUEST_SLOTS)));
        // only what this call finished gets applied and announced
        assertEquals(List.of(mine), done);
        assertEquals(List.of("complete size radius 100", "complete guest-limit radius null"), store.calls);
    }

    @Test
    void aTimerForARemovedTrackIsDropped() {
        UUID island = UUID.randomUUID();
        FakeStore store = new FakeStore(List.of(new PendingUpgrade(island, "gone", 1, 0L)), Set.of());
        List<PendingUpgrade> done = UpgradeManager.claimDue(store.storage(), 1_000L, Set.of(island), Map.of());
        assertEquals(List.of(), done);
        assertEquals(List.of("delete gone"), store.calls);
    }
}
