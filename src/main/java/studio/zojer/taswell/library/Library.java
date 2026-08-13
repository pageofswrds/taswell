package studio.zojer.taswell.library;

import studio.zojer.taswell.store.Playlist;
import studio.zojer.taswell.track.Track;
import studio.zojer.taswell.track.TrackSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The unified catalog: vanilla (C418) tracks plus whatever local tracks the last folder
 * scan turned up (see {@link LibraryScanner}). Pure JVM — no
 * {@code net.minecraft.*}/{@code net.fabricmc.*} imports — so it runs under plain JUnit.
 *
 * <p>Vanilla tracks are fixed at construction; local tracks are mutable via
 * {@link #setLocal(List)}, swapped in wholesale whenever the folder is rescanned.
 */
public final class Library {
    private final List<Track> vanilla;
    private List<Track> local;

    public Library(List<Track> vanilla) {
        this.vanilla = List.copyOf(vanilla);
        this.local = List.of();
    }

    /** Replaces the local track list wholesale, e.g. with fresh {@link LibraryScanner#scan} results. */
    public void setLocal(List<Track> tracks) {
        this.local = List.copyOf(tracks);
    }

    /** Every track in the catalog: vanilla first, then local. */
    public List<Track> all() {
        List<Track> combined = new ArrayList<>(vanilla.size() + local.size());
        combined.addAll(vanilla);
        combined.addAll(local);
        return List.copyOf(combined);
    }

    public Optional<Track> byId(String trackId) {
        return all().stream().filter(t -> t.id().equals(trackId)).findFirst();
    }

    /**
     * Resolves a playlist's track ids against the catalog. An id with no matching track
     * (e.g. a local file deleted since the playlist was saved) yields an {@link Entry}
     * with a {@code null} track — the caller renders that as a greyed "missing" row —
     * rather than throwing or silently dropping the entry.
     */
    public List<Entry> resolve(Playlist playlist) {
        return playlist.trackIds().stream()
                .map(id -> new Entry(id, byId(id).orElse(null)))
                .toList();
    }

    /** The three catalog-synthesized playlists: all vanilla, all local, and everything. */
    public List<Playlist> builtins() {
        return List.of(
                new Playlist("builtin:c418", "C418", vanilla.stream().map(Track::id).toList()),
                new Playlist("builtin:local", "My Music", local.stream().map(Track::id).toList()),
                new Playlist("builtin:all", "Everything", all().stream().map(Track::id).toList()));
    }

    /**
     * Case-insensitive substring match over title, artist, and (for local tracks) the
     * filename. Returns a filtered view over the catalog; {@link #all()} is unaffected.
     */
    public List<Track> search(String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        return all().stream().filter(t -> matches(t, needle)).toList();
    }

    private static boolean matches(Track t, String needle) {
        if (contains(t.title(), needle) || contains(t.artist(), needle)) {
            return true;
        }
        if (t.source() == TrackSource.LOCAL && t.localFile() != null) {
            return contains(t.localFile().getFileName().toString(), needle);
        }
        return false;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** One resolved playlist row. {@code track} is {@code null} when the id no longer resolves. */
    public record Entry(String trackId, Track track) {
    }
}
