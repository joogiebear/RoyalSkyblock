package com.mystipixel.royalskyblock.profile;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveRequestsTest {

    private static final UUID STEVE = UUID.randomUUID();
    private static final UUID ALEX = UUID.randomUUID();

    private final SaveRequests requests = new SaveRequests(1_000L);

    @Test
    void firstRequestSavesNow() {
        assertEquals(SaveRequests.Decision.NOW, requests.request(STEVE, 10_000L));
    }

    @Test
    void floodInsideTheCooldownBecomesOneTrailingSave() {
        assertEquals(SaveRequests.Decision.NOW, requests.request(STEVE, 10_000L));
        assertEquals(SaveRequests.Decision.DEFERRED, requests.request(STEVE, 10_100L));
        assertEquals(900L, requests.remainingMillis(STEVE, 10_100L));
        for (int i = 0; i < 100; i++) {
            assertEquals(SaveRequests.Decision.COALESCED, requests.request(STEVE, 10_200L + i));
        }

        assertTrue(requests.due(STEVE, 11_000L));
        assertFalse(requests.due(STEVE, 11_000L));
    }

    @Test
    void trailingSaveStartsANewCooldown() {
        requests.request(STEVE, 10_000L);
        requests.request(STEVE, 10_500L);
        requests.due(STEVE, 11_000L);

        assertEquals(SaveRequests.Decision.DEFERRED, requests.request(STEVE, 11_500L));
        assertEquals(500L, requests.remainingMillis(STEVE, 11_500L));
    }

    @Test
    void requestAfterTheCooldownSavesNow() {
        requests.request(STEVE, 10_000L);

        assertEquals(SaveRequests.Decision.NOW, requests.request(STEVE, 11_000L));
    }

    @Test
    void playersAreLimitedSeparately() {
        requests.request(STEVE, 10_000L);

        assertEquals(SaveRequests.Decision.NOW, requests.request(ALEX, 10_001L));
    }

    @Test
    void quitDropsThePendingSaveAndTheEntry() {
        requests.request(STEVE, 10_000L);
        requests.request(STEVE, 10_100L);

        requests.forget(STEVE);

        assertFalse(requests.due(STEVE, 11_000L));
        assertEquals(0, requests.tracked());
        assertEquals(SaveRequests.Decision.NOW, requests.request(STEVE, 11_100L));
    }

    @Test
    void aDeferredSaveThatNeverRanStopsBlockingNewOnes() {
        requests.request(STEVE, 10_000L);
        requests.request(STEVE, 10_100L);   // due at 11_000; its task was cancelled by a reload

        assertEquals(SaveRequests.Decision.COALESCED, requests.request(STEVE, 11_900L));
        assertEquals(SaveRequests.Decision.NOW, requests.request(STEVE, 12_000L));
    }
}
