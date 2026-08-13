package studio.zojer.taswell.director;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.zojer.taswell.track.Track;
import studio.zojer.taswell.track.TrackSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises {@link TrackStartedNotifier} in isolation — pure JVM, no {@link MusicDirector}
 * involved (its singleton can't be constructed outside a running game).
 */
class TrackStartedNotifierTest {
    private static final Logger LOG = LoggerFactory.getLogger(TrackStartedNotifierTest.class);

    private static Track fixture() {
        return new Track("taswell:track.sweden", "Sweden", "C418", TrackSource.VANILLA,
                "taswell:track.sweden", null);
    }

    @Test
    void everyListenerIsCalledWithTheTrack() {
        List<Track> seenByFirst = new ArrayList<>();
        List<Track> seenBySecond = new ArrayList<>();
        Track track = fixture();

        TrackStartedNotifier.notifyAll(List.of(seenByFirst::add, seenBySecond::add), track, LOG);

        assertEquals(List.of(track), seenByFirst);
        assertEquals(List.of(track), seenBySecond);
    }

    /**
     * Review finding: an uncaught listener exception used to propagate straight out of {@code
     * MusicDirector.notifyTrackStarted} — reached from {@code tick()}, so it would have crashed
     * the client's tick loop. A throwing listener must be isolated: later listeners still run,
     * and the call into this method must never itself throw.
     */
    @Test
    void aThrowingListenerDoesNotStopLaterListenersOrPropagate() {
        List<Track> seenByThird = new ArrayList<>();
        Track track = fixture();
        Consumer<Track> throwing = t -> {
            throw new IllegalStateException("boom");
        };

        assertDoesNotThrow(() ->
                TrackStartedNotifier.notifyAll(List.of(throwing, throwing, seenByThird::add), track, LOG));

        assertEquals(List.of(track), seenByThird);
    }

    @Test
    void noListenersDoesNothing() {
        assertDoesNotThrow(() -> TrackStartedNotifier.notifyAll(List.of(), fixture(), LOG));
    }
}
