package studio.zojer.taswell.director;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link PlaybackGeneration} in isolation — pure JVM, no {@link MusicDirector}
 * involved (its singleton can't be constructed outside a running game).
 */
class PlaybackGenerationTest {

    @Test
    void startReturnsIncreasingIdsAndUpdatesCurrent() {
        PlaybackGeneration playback = new PlaybackGeneration();

        long first = playback.start();
        long second = playback.start();

        assertNotEquals(first, second);
        assertEquals(second, playback.current());
    }

    @Test
    void freshInstanceHasNoCurrentGeneration() {
        PlaybackGeneration playback = new PlaybackGeneration();

        assertEquals(0L, playback.current());
    }

    @Test
    void endIfCurrentSucceedsExactlyOnceForTheGenerationThatStartedIt() {
        PlaybackGeneration playback = new PlaybackGeneration();
        long generation = playback.start();

        assertTrue(playback.endIfCurrent(generation), "first call should end the current generation");
        assertFalse(playback.endIfCurrent(generation), "second call with the same id must be stale");
        assertEquals(0L, playback.current());
    }

    /**
     * The core of the review fix: a local track's onFinished callback (async) and {@code
     * tick()}'s isActive poll can both observe the same track ending. Whichever calls {@code
     * endIfCurrent} first "wins"; the other — racing in, same generation — must be told it's
     * stale rather than double-processing the end.
     */
    @Test
    void endIfCurrentModelsPollAndCallbackRacingForTheSameEnd() {
        PlaybackGeneration playback = new PlaybackGeneration();
        long generation = playback.start();

        boolean pollWon = playback.endIfCurrent(generation);
        boolean callbackWon = playback.endIfCurrent(generation);

        assertTrue(pollWon);
        assertFalse(callbackWon);
    }

    @Test
    void endIfCurrentRejectsAStaleGenerationAfterANewOneHasStarted() {
        PlaybackGeneration playback = new PlaybackGeneration();
        long staleGeneration = playback.start();
        long currentGeneration = playback.start();

        assertFalse(playback.endIfCurrent(staleGeneration),
                "a callback for the track that was skipped away from must not end the new track");
        assertTrue(playback.endIfCurrent(currentGeneration));
    }

    @Test
    void invalidateMakesAnyPendingGenerationStale() {
        PlaybackGeneration playback = new PlaybackGeneration();
        long generation = playback.start();

        playback.invalidate();

        assertEquals(0L, playback.current());
        assertFalse(playback.endIfCurrent(generation),
                "a late signal for the paused/stopped attempt must not clear the remembered track");
    }

    @Test
    void endIfCurrentWithTheSentinelValueIsAlwaysFalse() {
        PlaybackGeneration playback = new PlaybackGeneration();

        // Nothing has started yet, so current() happens to already be 0 — but 0 must never be
        // treated as a legitimate generation, even by coincidence.
        assertEquals(0L, playback.current());
        assertFalse(playback.endIfCurrent(0L));
    }
}
