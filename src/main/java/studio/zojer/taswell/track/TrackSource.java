package studio.zojer.taswell.track;

/**
 * Where a {@link Track}'s audio comes from.
 */
public enum TrackSource {
    /** A vanilla asset, referenced by sound event id. Ships no audio. */
    VANILLA,
    /** A user-supplied MP3 on local disk. */
    LOCAL
}
