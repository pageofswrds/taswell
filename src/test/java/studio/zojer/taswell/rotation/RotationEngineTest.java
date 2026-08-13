package studio.zojer.taswell.rotation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link RotationEngine} against hand-built id lists — pure JVM, no library, no
 * Minecraft classes, no filesystem.
 */
class RotationEngineTest {

    @Test
    void orderedModeReturnsElementAfterLastTrackId() {
        RotationEngine engine = new RotationEngine(42);
        List<String> playlist = List.of("a", "b", "c");

        assertEquals(Optional.of("b"), engine.next(playlist, "a", false, RepeatMode.PLAYLIST));
    }

    @Test
    void orderedModeWithNullLastTrackIdStartsAtTheBeginning() {
        RotationEngine engine = new RotationEngine(42);
        List<String> playlist = List.of("a", "b", "c");

        assertEquals(Optional.of("a"), engine.next(playlist, null, false, RepeatMode.PLAYLIST));
    }

    @Test
    void orderedModeWrapsToStartOnlyWhenRepeatIsPlaylist() {
        RotationEngine engine = new RotationEngine(42);
        List<String> playlist = List.of("a", "b", "c");

        assertEquals(Optional.of("a"), engine.next(playlist, "c", false, RepeatMode.PLAYLIST));
    }

    @Test
    void orderedModeReturnsEmptyAtEndWhenRepeatIsOff() {
        RotationEngine engine = new RotationEngine(42);
        List<String> playlist = List.of("a", "b", "c");

        assertTrue(engine.next(playlist, "c", false, RepeatMode.OFF).isEmpty());
    }

    @Test
    void orderedModeWithRepeatOffStillAdvancesBeforeTheEnd() {
        RotationEngine engine = new RotationEngine(42);
        List<String> playlist = List.of("a", "b", "c");

        assertEquals(Optional.of("b"), engine.next(playlist, "a", false, RepeatMode.OFF));
    }

    @Test
    void repeatOneReturnsLastTrackIdRegardlessOfOrderOrShuffle() {
        RotationEngine engine = new RotationEngine(42);
        List<String> playlist = List.of("a", "b", "c");

        assertEquals(Optional.of("b"), engine.next(playlist, "b", false, RepeatMode.ONE));
        assertEquals(Optional.of("b"), engine.next(playlist, "b", true, RepeatMode.ONE));
    }

    @Test
    void shuffleNeverRepeatsLastTrackIdConsecutivelyOverManyDraws() {
        RotationEngine engine = new RotationEngine(1234);
        List<String> playlist = List.of("a", "b", "c", "d", "e");

        String last = null;
        for (int i = 0; i < 200; i++) {
            Optional<String> next = engine.next(playlist, last, true, RepeatMode.PLAYLIST);
            assertTrue(next.isPresent());
            assertTrue(playlist.contains(next.get()));
            if (last != null) {
                assertNotEquals(last, next.get());
            }
            last = next.get();
        }
    }

    @Test
    void shuffleWithSingleEntryListMayRepeat() {
        RotationEngine engine = new RotationEngine(7);
        List<String> playlist = List.of("only");

        for (int i = 0; i < 5; i++) {
            assertEquals(Optional.of("only"), engine.next(playlist, "only", true, RepeatMode.PLAYLIST));
        }
    }

    @Test
    void emptyPlaylistReturnsEmptyOptional() {
        RotationEngine engine = new RotationEngine(42);

        assertTrue(engine.next(List.of(), "anything", true, RepeatMode.PLAYLIST).isEmpty());
        assertTrue(engine.next(List.of(), null, false, RepeatMode.OFF).isEmpty());
    }

    @Test
    void orderedModeWithLastTrackIdNotInListStillReturnsAValidTrack() {
        RotationEngine engine = new RotationEngine(42);
        List<String> playlist = List.of("a", "b", "c");

        Optional<String> next = engine.next(playlist, "deleted-track", false, RepeatMode.PLAYLIST);

        assertTrue(next.isPresent());
        assertTrue(playlist.contains(next.get()));
    }

    @Test
    void shuffleModeWithLastTrackIdNotInListStillReturnsAValidTrack() {
        RotationEngine engine = new RotationEngine(42);
        List<String> playlist = List.of("a", "b", "c");

        Optional<String> next = engine.next(playlist, "deleted-track", true, RepeatMode.PLAYLIST);

        assertTrue(next.isPresent());
        assertTrue(playlist.contains(next.get()));
    }

    @Test
    void sameSeedProducesSameShuffleSequence() {
        List<String> playlist = List.of("a", "b", "c", "d");
        RotationEngine e1 = new RotationEngine(99);
        RotationEngine e2 = new RotationEngine(99);

        String last1 = null;
        String last2 = null;
        for (int i = 0; i < 20; i++) {
            String n1 = e1.next(playlist, last1, true, RepeatMode.PLAYLIST).orElseThrow();
            String n2 = e2.next(playlist, last2, true, RepeatMode.PLAYLIST).orElseThrow();
            assertEquals(n1, n2);
            last1 = n1;
            last2 = n2;
        }
    }
}
