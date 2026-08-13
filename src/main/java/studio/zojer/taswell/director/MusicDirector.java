package studio.zojer.taswell.director;

/**
 * Gate for vanilla ambient music. {@link #vanillaSuppressed()} is the single kill switch
 * {@code MusicManagerMixin} routes through — a constant {@code true} for now (Task 6: total
 * silence until the director exists), rewritten in Task 7 to drive taswell's own playback and
 * to admit a future "disable mod" toggle without touching the mixin again.
 */
public final class MusicDirector {
    private MusicDirector() {
    }

    public static boolean vanillaSuppressed() {
        return true;
    }
}
