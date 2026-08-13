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
 *
 * <p><b>Duplicate ids in {@code playlist}.</b> {@code playlist} is expected to contain each id
 * at most once — every real caller (library-resolved playlists, ids keyed by unique track id) —
 * but nothing here rejects a hand-edited {@code playlists.json} with repeats, so both selection
 * modes are specified for that case rather than left to spin or crash:
 * <ul>
 * <li>Shuffle is unaffected — it draws by index, and always terminates (see {@link
 * #pickShuffled}).</li>
 * <li>Ordered mode positions purely from {@code playlist.indexOf(lastTrackId)}, which always
 * finds the <em>first</em> occurrence. A playlist like {@code [a, b, a, c]} therefore cycles
 * {@code a, b, a, b, ...} forever and never reaches {@code c} — stepping forward from the first
 * {@code a} always lands back on the first {@code a} once it loops around to the second one.
 * This is a known, documented limitation of tracking position by id rather than by index across
 * calls (out of scope for this signature); it is harmless for the ids this class actually
 * receives in practice, since those are never duplicated.</li>
 * </ul>
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
     * no consecutive repeat whenever the list has 2+ entries <em>and</em> at least one of them
     * differs from {@code lastTrackId}. Two cases skip the rejection loop entirely rather than
     * risk it — a single-entry list has no other choice, and a list where every entry equals
     * {@code lastTrackId} (e.g. a hand-edited playlist with the same id repeated) has no other
     * choice either; without this check that second case would reject every draw forever, an
     * unbounded loop on the client thread (found by review, not just reasoned about — see the
     * covering test). Either way this method always terminates.
     */
    private String pickShuffled(List<String> playlist, String lastTrackId) {
        if (playlist.size() == 1 || noEntryDiffersFrom(playlist, lastTrackId)) {
            return playlist.get(random.nextInt(playlist.size()));
        }
        String candidate;
        do {
            candidate = playlist.get(random.nextInt(playlist.size()));
        } while (candidate.equals(lastTrackId));
        return candidate;
    }

    private static boolean noEntryDiffersFrom(List<String> playlist, String lastTrackId) {
        if (lastTrackId == null) {
            return false;
        }
        for (String id : playlist) {
            if (!id.equals(lastTrackId)) {
                return false;
            }
        }
        return true;
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
