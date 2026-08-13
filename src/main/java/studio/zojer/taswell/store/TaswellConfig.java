package studio.zojer.taswell.store;

/**
 * The mod's persisted settings. Plain mutable JSON-shaped bean (not a record): Gson
 * round-trips it directly, and callers mutate a loaded instance in place before saving.
 *
 * <p>Pure JVM — no {@code net.minecraft.*} or {@code net.fabricmc.*} imports. See
 * {@link ConfigStore} for load/save + corrupt-file recovery.
 */
public final class TaswellConfig {
    /** Absolute path to the folder scanned for local MP3s, or {@code null} to use the default. */
    public String musicFolder = null;
    public int minGapSeconds = 60;
    public int maxGapSeconds = 300;
    public boolean hudEnabled = true;
    /** A playlist id: {@code builtin:<name>} or {@code custom:<name>}. */
    public String activePlaylistId = "builtin:c418";
    public boolean shuffle = true;
    /** Repeat behavior; opaque string here (values interpreted by the playback layer). */
    public String repeatMode = "PLAYLIST";
}
