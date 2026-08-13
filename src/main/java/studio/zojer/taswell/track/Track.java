package studio.zojer.taswell.track;

import java.nio.file.Path;

/**
 * A single playable track, either a vanilla C418 asset or a local MP3.
 *
 * <p>For {@link TrackSource#VANILLA} tracks, {@code localFile} is {@code null} and
 * {@code vanillaSoundEventId} holds the registered sound event id
 * ({@code taswell:track.<slug>}). For {@link TrackSource#LOCAL} tracks the reverse
 * holds: {@code vanillaSoundEventId} is {@code null} and {@code localFile} points at
 * the MP3 on disk.
 *
 * <p>Duration is deliberately not modeled here (no seek bar).
 */
public record Track(
        String id,
        String title,
        String artist,
        TrackSource source,
        String vanillaSoundEventId,
        Path localFile
) {
}
