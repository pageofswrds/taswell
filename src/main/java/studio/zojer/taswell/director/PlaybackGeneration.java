package studio.zojer.taswell.director;

/**
 * Tracks which "playback attempt" is current, so a late or duplicate end-of-track signal can be
 * recognized as stale and ignored rather than corrupting {@link MusicDirector}'s state.
 *
 * <p>Two independent things can each observe a track ending: a local track's {@code onFinished}
 * callback (async — queued from a background decode thread, hopped to the client thread) and
 * {@code MusicDirector.tick()}'s {@code soundManager.isActive} poll (a backstop for both local
 * and vanilla tracks). Either can race a user-initiated change (skip/previous/playNow/pause)
 * that has already moved on to a different track — or race *each other*, since a local track is
 * now polled as a backstop too. Review finding: without this guard, a still-pending decode
 * failure for a track that was already skipped away from could fire its callback after a
 * newer track had already started, clearing the wrong track's state out from under it and
 * corrupting rotation (double-advance, or audio overlapping with what the director thinks is
 * current).
 *
 * <p>Pure JVM, no Minecraft imports — {@link MusicDirector} itself can't be unit-tested (its
 * constructor touches {@code FabricLoader} at class-init), so this is where the actually
 * testable half of that fix lives.
 *
 * <p>Not thread-safe by itself. Not a bug: {@link MusicDirector} only ever touches an instance
 * of this from the client thread — {@code tick()}'s poll runs there directly, and the async
 * callback's generation check is dispatched via {@code Minecraft.execute(...)} before it ever
 * reaches this class, so there is never concurrent access to guard against here.
 */
final class PlaybackGeneration {
    /** Never a real generation id — {@link #current} rests here whenever nothing is current. */
    private static final long NONE = 0L;

    private long counter;
    private long current = NONE;

    /**
     * Starts a new playback attempt, invalidating whatever was current before (any pending
     * signal for the old attempt will find {@link #endIfCurrent} returns {@code false} for it).
     * Returns the new generation id — the caller captures it and presents it back later via
     * {@link #endIfCurrent} when that specific attempt ends.
     */
    long start() {
        current = ++counter;
        return current;
    }

    /**
     * Invalidates the current attempt without starting a new one — used when a user-initiated
     * stop (pause) or a failed start leaves nothing current, but a signal for the just-stopped
     * attempt could still arrive later and must be recognized as stale.
     */
    void invalidate() {
        current = NONE;
    }

    /** The current generation id, or the {@link #NONE} sentinel if nothing is current. */
    long current() {
        return current;
    }

    /**
     * Reports whether {@code generation} still names the current attempt, and if so, ends it —
     * a second call with the same (or any other non-current) generation returns {@code false}.
     * This is what makes "poll and callback both detect the same end" safe: whichever gets here
     * first wins and the other is correctly told it's stale, regardless of ordering.
     */
    boolean endIfCurrent(long generation) {
        if (generation == NONE || generation != current) {
            return false;
        }
        current = NONE;
        return true;
    }
}
