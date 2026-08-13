package studio.zojer.taswell.rotation;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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

    /**
     * Review finding: a playlist where every entry equals {@code lastTrackId} (a hand-edited
     * {@code playlists.json}, never deduped by {@code Library.resolve}) used to spin the
     * rejection-sampling loop forever — a hard freeze if reached from {@code
     * MusicDirector.tick()} on the client thread. Wrapped in {@code assertTimeoutPreemptively}
     * so a regression fails fast instead of hanging the whole test run.
     */
    @Test
    void shuffleWithAllEntriesEqualToLastTrackIdTerminatesAndReturnsThatId() {
        RotationEngine engine = new RotationEngine(42);
        List<String> playlist = List.of("a", "a", "a");

        Optional<String> next = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> engine.next(playlist, "a", true, RepeatMode.PLAYLIST));

        assertEquals(Optional.of("a"), next);
    }

    /** Same all-duplicates termination guarantee, exercised many times for extra confidence. */
    @Test
    void shuffleWithAllEntriesEqualToLastTrackIdTerminatesOverManyDraws() {
        RotationEngine engine = new RotationEngine(2026);
        List<String> playlist = List.of("x", "x");

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int i = 0; i < 200; i++) {
                assertEquals(Optional.of("x"), engine.next(playlist, "x", true, RepeatMode.PLAYLIST));
            }
        });
    }

    /**
     * Documents the known, out-of-scope-to-fully-fix limitation from the class javadoc: ordered
     * mode positions via {@code indexOf(lastTrackId)}, which always finds the first occurrence.
     * A playlist with a duplicate id therefore cycles between the duplicate and whatever
     * immediately follows its first occurrence, and never reaches anything after a later
     * occurrence of that id. This test locks in that documented behavior rather than leaving it
     * as an unverified claim in a comment.
     */
    @Test
    void orderedModeWithDuplicateIdsCyclesAndNeverReachesEntryAfterALaterDuplicate() {
        RotationEngine engine = new RotationEngine(42);
        List<String> playlist = List.of("a", "b", "a", "c");

        String last = "a";
        for (int i = 0; i < 10; i++) {
            String next = engine.next(playlist, last, false, RepeatMode.PLAYLIST).orElseThrow();
            assertNotEquals("c", next, "should never reach the entry after the second 'a'");
            assertTrue(next.equals("a") || next.equals("b"));
            last = next;
        }
    }

    /**
     * The flip side of the duplicate-id caveat: when ids are genuinely unique (the case every
     * real caller is in — see the class javadoc), ordered mode is guaranteed to visit every one
     * of them, in order, before it ever repeats.
     */
    @Test
    void orderedModeVisitsEveryUniqueIdBeforeRepeatingWhenIdsAreUnique() {
        RotationEngine engine = new RotationEngine(42);
        List<String> playlist = List.of("a", "b", "c", "d");

        Set<String> visited = new LinkedHashSet<>();
        String last = null;
        for (int i = 0; i < playlist.size(); i++) {
            String next = engine.next(playlist, last, false, RepeatMode.PLAYLIST).orElseThrow();
            assertTrue(visited.add(next), "repeated " + next + " before visiting every id: " + visited);
            last = next;
        }
        assertEquals(Set.copyOf(playlist), visited);
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
