package studio.zojer.taswell.rotation;

/**
 * Ambient rotation's repeat behavior. Mirrors {@code TaswellConfig.repeatMode}'s opaque string
 * field ({@code cfg.repeatMode = mode.name()} on write, {@code RepeatMode.valueOf(...)} on
 * read) — pure JVM, no {@code net.minecraft.*}/{@code net.fabricmc.*} imports.
 */
public enum RepeatMode {
    /** Play through the playlist once; {@link RotationEngine#next} returns empty at the end. */
    OFF,
    /** Repeat the current track indefinitely. */
    ONE,
    /** Loop the whole playlist, wrapping back to the start once the end is reached. */
    PLAYLIST
}
