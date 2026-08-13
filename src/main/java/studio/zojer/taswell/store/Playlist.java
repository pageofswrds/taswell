package studio.zojer.taswell.store;

import java.util.List;

/**
 * A named ordered list of track ids. Ids are opaque strings here — vanilla tracks use
 * the sound event id form ({@code taswell:track.<slug>}), local tracks use
 * {@code local:<filename>} — this store never validates them against the library.
 *
 * <p>An id starting with {@code builtin:} names a playlist synthesized by the library
 * (e.g. {@code builtin:c418}) rather than one a user created; {@link PlaylistStore}
 * never persists those (see its class doc).
 */
public record Playlist(String id, String name, List<String> trackIds) {
    public boolean builtin() {
        return id != null && id.startsWith("builtin:");
    }
}
