package com.mystipixel.royalskyblock.simulation;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The growth model decides what a player is handed for time they weren't playing, so it needs to be
 * pinned rather than eyeballed: too generous and offline farming beats playing, too stingy and the
 * catch-up is pointless. These tests fix the boundaries and the long-run average.
 */
class GrowthModelTest {

    private static final double STAGE = 130;      // config default: ~seconds per growth stage

    /** Deterministic uniforms so a test can't flake on a bad roll. */
    private static DoubleSupplier fixed(double value) {
        return () -> value;
    }

    private static DoubleSupplier seeded(long seed) {
        Random r = new Random(seed);
        return r::nextDouble;
    }

    @Test
    void noOfflineTimeGrowsNothing() {
        assertEquals(0, GrowthModel.stagesGrown(0, STAGE, 7, seeded(1)));
        assertEquals(0, GrowthModel.stagesGrown(-5, STAGE, 7, seeded(1)));
    }

    @Test
    void neverExceedsTheStagesRemaining() {
        // A year away: a crop can still only reach ripe. This is what makes the max-offline cap a
        // minion concern rather than a crop one.
        int grown = GrowthModel.stagesGrown(365L * 24 * 3600, STAGE, 7, seeded(2));
        assertEquals(7, grown);
    }

    @Test
    void alreadyRipeCropIsLeftAlone() {
        assertEquals(0, GrowthModel.stagesGrown(100_000, STAGE, 0, seeded(3)));
    }

    /** u -> 0 makes each stage cost ~0s, the cheapest possible draw: the budget bounds the result. */
    @Test
    void cheapestRollsAreStillCappedByStagesRemaining() {
        assertEquals(4, GrowthModel.stagesGrown(10, STAGE, 4, fixed(0.0)));
    }

    /**
     * u -> 1 is the worst draw the model can produce: a stage costs -ln(1.1e-16) * 130 ~ 4771s.
     * It must stay finite and bounded rather than looping on a NaN/infinite budget — the guard in
     * stagesGrown exists for exactly this input.
     */
    @Test
    void mostExpensiveRollIsFiniteAndBounded() {
        DoubleSupplier worst = fixed(Math.nextDown(1.0));
        assertEquals(0, GrowthModel.stagesGrown(1_000, STAGE, 7, worst),
                "1000s can't buy a ~4771s stage");
        assertEquals(2, GrowthModel.stagesGrown(10_000, STAGE, 7, worst),
                "10000s buys exactly two ~4771s stages");
    }

    /**
     * Mean cost per stage should track seconds-per-stage: over a long window the average stages
     * grown ~ offline / secondsPerStage. This is the property that makes the config value mean what
     * its comment claims.
     */
    @Test
    void averageRateMatchesSecondsPerStage() {
        long offline = 1300;                       // 10 stages' worth at STAGE=130
        DoubleSupplier rng = seeded(42);
        int total = 0;
        int runs = 4000;
        for (int i = 0; i < runs; i++) {
            total += GrowthModel.stagesGrown(offline, STAGE, Integer.MAX_VALUE, rng);
        }
        double mean = (double) total / runs;
        assertTrue(mean > 9.0 && mean < 11.0,
                "expected ~10 stages per " + offline + "s at " + STAGE + "s/stage, got " + mean);
    }

    /**
     * The reason for rolling per stage instead of dividing: a field planted together must not ripen
     * in lockstep. Identical inputs should produce a spread of outcomes.
     */
    @Test
    void identicalCropsDoNotGrowInLockstep() {
        DoubleSupplier rng = seeded(7);
        boolean sawDifferent = false;
        int first = GrowthModel.stagesGrown(400, STAGE, 7, rng);
        for (int i = 0; i < 200 && !sawDifferent; i++) {
            if (GrowthModel.stagesGrown(400, STAGE, 7, rng) != first) {
                sawDifferent = true;
            }
        }
        assertTrue(sawDifferent, "every crop grew the same amount — the field would ripen in lockstep");
    }

    /** A quick hub round-trip should usually be worth nothing, not a guaranteed stage. */
    @Test
    void shortTripsUsuallyGrowNothing() {
        DoubleSupplier rng = seeded(11);
        int zero = 0;
        int runs = 1000;
        for (int i = 0; i < runs; i++) {
            if (GrowthModel.stagesGrown(10, STAGE, 7, rng) == 0) {
                zero++;
            }
        }
        // P(no growth in 10s at 130s/stage) = e^(-10/130) ~ 0.926
        assertTrue(zero > runs * 0.85, "10s away grew a stage too often: " + (runs - zero) + "/" + runs);
    }

    @Test
    void zeroSecondsPerStageIsTreatedAsDisabledRatherThanInfiniteGrowth() {
        assertEquals(0, GrowthModel.stagesGrown(10_000, 0, 7, seeded(5)));
    }
}
