package studio.zojer.taswell.rotation;

import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Picks the next track id to play, given a playlist and where rotation last left off. Pure JVM
 * — no {@code net.minecraft.*}/{@code net.fabricmc.*} imports — deterministic under a fixed
 * seed so shuffle behavior is unit-testable.
 *
 * <p>The caller (the director) is expected to resolve the active playlist's ids through the
 * library first, filtering out any that no longer resolve to a track (e.g. a local file deleted
 * mid-session), so this class only ever sees ids it should consider playable. {@code
 * lastTrackId} is the exception: it may legitimately name an id no longer present in {@code
 * playlist} (that same deletion case, applied to the just-finished track) — handled the same as
 * a {@code null} lastTrackId rather than thrown.
 */
public final class RotationEngine {
    private final Random random;

    public RotationEngine(long seed) {
        this.random = new Random(seed);
    }

    /**
     * @param playlist    ids to rotate through, in playlist order
     * @param lastTrackId the id most recently played, or {@code null} if rotation hasn't
     *                    started yet; need not be present in {@code playlist}
     * @param shuffle     whether to pick randomly rather than walk the list in order
     * @param mode        repeat behavior; {@link RepeatMode#ONE} short-circuits both ordered
     *                    and shuffle selection and simply repeats {@code lastTrackId}
     * @return the next track id, or empty when {@code playlist} is empty, or (ordered,
     * {@link RepeatMode#OFF}) the end of the playlist has been reached
     */
    public Optional<String> next(List<String> playlist, String lastTrackId, boolean shuffle, RepeatMode mode) {
        if (playlist == null || playlist.isEmpty()) {
            return Optional.empty();
        }
        if (mode == RepeatMode.ONE && lastTrackId != null) {
            return Optional.of(lastTrackId);
        }
        if (shuffle) {
            return Optional.of(pickShuffled(playlist, lastTrackId));
        }
        return nextOrdered(playlist, lastTrackId, mode);
    }

    /**
     * Rejection-samples until it draws something other than {@code lastTrackId}, guaranteeing
     * no consecutive repeat whenever the list has 2+ entries. A single-entry list has no other
     * choice and returns immediately (the loop condition is trivially false).
     */
    private String pickShuffled(List<String> playlist, String lastTrackId) {
        if (playlist.size() == 1) {
            return playlist.get(0);
        }
        String candidate;
        do {
            candidate = playlist.get(random.nextInt(playlist.size()));
        } while (candidate.equals(lastTrackId));
        return candidate;
    }

    private Optional<String> nextOrdered(List<String> playlist, String lastTrackId, RepeatMode mode) {
        int currentIndex = lastTrackId == null ? -1 : playlist.indexOf(lastTrackId);
        int nextIndex = currentIndex + 1;
        if (nextIndex >= playlist.size()) {
            if (mode == RepeatMode.PLAYLIST) {
                nextIndex = 0;
            } else {
                return Optional.empty();
            }
        }
        return Optional.of(playlist.get(nextIndex));
    }
}
