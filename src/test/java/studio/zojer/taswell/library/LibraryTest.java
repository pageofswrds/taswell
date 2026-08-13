package studio.zojer.taswell.library;

import org.junit.jupiter.api.Test;
import studio.zojer.taswell.store.Playlist;
import studio.zojer.taswell.track.Track;
import studio.zojer.taswell.track.TrackSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link Library} against small hand-built {@link Track} fixtures — no
 * jaudiotagger, no filesystem, no Minecraft classes involved. Pure JVM.
 */
class LibraryTest {

    /**
     * Builds a vanilla fixture with {@code id} set to the sound-event-id form
     * ({@code "taswell:track." + slug}) — matching {@link VanillaTracks#load()}'s
     * contract that vanilla track id and vanillaSoundEventId are the same string.
     */
    private static Track vanilla(String slug, String title) {
        String soundEventId = "taswell:track." + slug;
        return new Track(soundEventId, title, "C418", TrackSource.VANILLA, soundEventId, null);
    }

    private static Track local(String fileName, String title, String artist) {
        return new Track("local:" + fileName, title, artist, TrackSource.LOCAL, null,
                Path.of("/music/" + fileName));
    }

    private static Library sampleLibrary() {
        List<Track> vanilla = List.of(
                vanilla("sweden", "Sweden"),
                vanilla("taswell", "Taswell"),
                vanilla("clark", "Clark"));
        Library library = new Library(vanilla);
        library.setLocal(List.of(
                local("alpha.mp3", "Alpha Song", "Alice"),
                local("beta.mp3", "Beta Tune", "Bob")));
        return library;
    }

    @Test
    void allReturnsVanillaAndLocalTracks() {
        Library library = sampleLibrary();

        List<Track> all = library.all();

        assertEquals(5, all.size());
        assertTrue(all.stream().anyMatch(t -> t.id().equals("taswell:track.sweden")));
        assertTrue(all.stream().anyMatch(t -> t.id().equals("local:alpha.mp3")));
    }

    @Test
    void byIdFindsVanillaAndLocalTracksAndIsEmptyForUnknown() {
        Library library = sampleLibrary();

        assertEquals("Sweden", library.byId("taswell:track.sweden").map(Track::title).orElse(null));
        assertEquals("Alpha Song", library.byId("local:alpha.mp3").map(Track::title).orElse(null));
        assertTrue(library.byId("does-not-exist").isEmpty());
    }

    @Test
    void builtinC418ContainsAllVanillaIds() {
        Library library = sampleLibrary();

        Playlist c418 = library.builtins().stream()
                .filter(p -> p.id().equals("builtin:c418"))
                .findFirst().orElseThrow();

        assertEquals("C418", c418.name());
        assertEquals(List.of("taswell:track.sweden", "taswell:track.taswell", "taswell:track.clark"),
                c418.trackIds());
    }

    @Test
    void builtinLocalContainsAllLocalIds() {
        Library library = sampleLibrary();

        Playlist local = library.builtins().stream()
                .filter(p -> p.id().equals("builtin:local"))
                .findFirst().orElseThrow();

        assertEquals("My Music", local.name());
        assertEquals(List.of("local:alpha.mp3", "local:beta.mp3"), local.trackIds());
    }

    @Test
    void builtinAllContainsEveryTrackId() {
        Library library = sampleLibrary();

        Playlist everything = library.builtins().stream()
                .filter(p -> p.id().equals("builtin:all"))
                .findFirst().orElseThrow();

        assertEquals("Everything", everything.name());
        assertEquals(5, everything.trackIds().size());
        assertTrue(everything.trackIds().contains("taswell:track.sweden"));
        assertTrue(everything.trackIds().contains("local:alpha.mp3"));
    }

    @Test
    void builtinC418IdsAreTheSoundEventIdFormPerPersistenceContract() {
        // Persistence contract (Task 3): "vanilla = the sound event id
        // (taswell:track.sweden)". byId must resolve a playlist id in exactly that
        // form without translation.
        Library library = sampleLibrary();

        Optional<Track> sweden = library.byId("taswell:track.sweden");

        assertTrue(sweden.isPresent());
        assertEquals(sweden.get().id(), sweden.get().vanillaSoundEventId());
    }

    @Test
    void builtinsAreAllMarkedBuiltin() {
        Library library = sampleLibrary();

        for (Playlist p : library.builtins()) {
            assertTrue(p.builtin(), p.id() + " should be builtin");
        }
    }

    @Test
    void resolveOfPlaylistNamingAVanishedFileYieldsNullTrackEntry() {
        Library library = sampleLibrary();
        Playlist playlist = new Playlist("custom:mix", "Mix", List.of(
                "taswell:track.sweden",
                "local:alpha.mp3",
                "local:vanished.mp3"));

        List<Library.Entry> entries = library.resolve(playlist);

        assertEquals(3, entries.size());

        assertEquals("taswell:track.sweden", entries.get(0).trackId());
        assertEquals("Sweden", entries.get(0).track().title());

        assertEquals("local:alpha.mp3", entries.get(1).trackId());
        assertEquals("Alpha Song", entries.get(1).track().title());

        assertEquals("local:vanished.mp3", entries.get(2).trackId());
        assertNull(entries.get(2).track());
    }

    @Test
    void searchFindsSwedenByPartialCaseInsensitiveTitle() {
        Library library = sampleLibrary();

        List<Track> results = library.search("swed");

        assertEquals(1, results.size());
        assertEquals("Sweden", results.get(0).title());
    }

    @Test
    void searchMatchesArtistAndFilenameToo() {
        Library library = sampleLibrary();

        List<Track> byArtist = library.search("alice");
        assertEquals(1, byArtist.size());
        assertEquals("Alpha Song", byArtist.get(0).title());

        List<Track> byFilename = library.search("beta.mp3");
        assertEquals(1, byFilename.size());
        assertEquals("Beta Tune", byFilename.get(0).title());
    }

    @Test
    void searchFiltersViewOnlyAllIsUnchanged() {
        Library library = sampleLibrary();
        int before = library.all().size();

        List<Track> results = library.search("swed");

        assertEquals(1, results.size());
        assertEquals(before, library.all().size());
        assertEquals(5, library.all().size());
    }

    @Test
    void searchWithNoMatchesReturnsEmptyList() {
        Library library = sampleLibrary();

        assertTrue(library.search("nonexistent-xyz").isEmpty());
    }

    @Test
    void setLocalSwapsResultsInAndReplacesPreviousLocalTracks() {
        Library library = sampleLibrary();

        library.setLocal(List.of(local("gamma.mp3", "Gamma", "Gary")));

        assertEquals(4, library.all().size());
        assertFalse(library.byId("local:alpha.mp3").isPresent());
        assertTrue(library.byId("local:gamma.mp3").isPresent());
    }
}
