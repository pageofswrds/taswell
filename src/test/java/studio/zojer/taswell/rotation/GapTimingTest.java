package studio.zojer.taswell.rotation;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link GapTiming} — pure JVM, no Minecraft classes involved (unlike {@link
 * studio.zojer.taswell.director.MusicDirector}, which delegates to this for its own gap
 * countdown and is verified in-game instead — see the task report).
 */
class GapTimingTest {
    private static final int TICKS_PER_SECOND = 20;

    @Test
    void fixedRangeReturnsExactSecondsInTicks() {
        int ticks = GapTiming.computeTicks(60, 60, TICKS_PER_SECOND, new Random(1));

        assertEquals(60 * TICKS_PER_SECOND, ticks);
    }

    @Test
    void drawsWithinConfiguredRange() {
        Random random = new Random(42);
        for (int i = 0; i < 200; i++) {
            int ticks = GapTiming.computeTicks(60, 300, TICKS_PER_SECOND, random);
            assertTrue(ticks >= 60 * TICKS_PER_SECOND, "below min: " + ticks);
            assertTrue(ticks <= 300 * TICKS_PER_SECOND, "above max: " + ticks);
        }
    }

    @Test
    void negativeMinClampsToZero() {
        // A single legal draw with min clamped to 0 must not throw and must stay in [0, max].
        int ticks = GapTiming.computeTicks(-30, 10, TICKS_PER_SECOND, new Random(7));

        assertTrue(ticks >= 0);
        assertTrue(ticks <= 10 * TICKS_PER_SECOND);
    }

    @Test
    void negativeMaxClampsToZero() {
        int ticks = GapTiming.computeTicks(-30, -10, TICKS_PER_SECOND, new Random(7));

        assertEquals(0, ticks);
    }

    @Test
    void bothNegativeNeverThrowsAndYieldsZero() {
        for (int trial = 0; trial < 50; trial++) {
            int ticks = GapTiming.computeTicks(-5, -1, TICKS_PER_SECOND, new Random(trial));
            assertEquals(0, ticks);
        }
    }

    @Test
    void minGreaterThanMaxIsSwappedAfterClamping() {
        // 300 as "min", 60 as "max" — same behavior as the un-swapped case, just arguments flipped.
        Random random = new Random(99);
        for (int i = 0; i < 50; i++) {
            int ticks = GapTiming.computeTicks(300, 60, TICKS_PER_SECOND, random);
            assertTrue(ticks >= 60 * TICKS_PER_SECOND, "below min: " + ticks);
            assertTrue(ticks <= 300 * TICKS_PER_SECOND, "above max: " + ticks);
        }
    }
}
