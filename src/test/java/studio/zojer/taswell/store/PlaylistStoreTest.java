package studio.zojer.taswell.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistStoreTest {
    @Test
    void missingFileReturnsEmptyList(@TempDir Path dir) {
        Path f = dir.resolve("playlists.json");

        List<Playlist> loaded = PlaylistStore.load(f);

        assertTrue(loaded.isEmpty());
    }

    @Test
    void corruptPlaylistsIsQuarantinedAndDefaulted(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("playlists.json");
        Files.writeString(f, "{not valid json[");

        List<Playlist> loaded = PlaylistStore.load(f);

        assertTrue(loaded.isEmpty());
        assertTrue(Files.exists(dir.resolve("playlists.json.bad")));
        assertFalse(Files.exists(f));
    }

    @Test
    void saveThenLoadPreservesOrderAndUnknownTrackIds(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("playlists.json");
        List<Playlist> playlists = List.of(
                new Playlist("custom:chill", "Chill Mix", List.of(
                        "taswell:track.sweden",
                        "local:my-song.mp3",
                        "taswell:track.does-not-exist")),
                new Playlist("custom:another", "Another Mix", List.of(
                        "local:b.mp3",
                        "local:a.mp3"))
        );

        PlaylistStore.save(f, playlists);
        List<Playlist> loaded = PlaylistStore.load(f);

        assertEquals(playlists.size(), loaded.size());
        for (int i = 0; i < playlists.size(); i++) {
            assertEquals(playlists.get(i).id(), loaded.get(i).id());
            assertEquals(playlists.get(i).name(), loaded.get(i).name());
            assertEquals(playlists.get(i).trackIds(), loaded.get(i).trackIds());
        }
    }

    @Test
    void loadFiltersOutAnyBuiltinEntriesInTheFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("playlists.json");
        Files.writeString(f, """
                [
                  {"id": "builtin:c418", "name": "C418", "trackIds": []},
                  {"id": "custom:mine", "name": "Mine", "trackIds": ["local:a.mp3"]}
                ]
                """);

        List<Playlist> loaded = PlaylistStore.load(f);

        assertEquals(1, loaded.size());
        assertEquals("custom:mine", loaded.get(0).id());
    }

    @Test
    void saveNeverPersistsBuiltinPlaylists(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("playlists.json");
        List<Playlist> playlists = List.of(
                new Playlist("builtin:c418", "C418", List.of("taswell:track.sweden")),
                new Playlist("custom:mine", "Mine", List.of("local:a.mp3"))
        );

        PlaylistStore.save(f, playlists);
        List<Playlist> loaded = PlaylistStore.load(f);

        assertEquals(1, loaded.size());
        assertEquals("custom:mine", loaded.get(0).id());
    }

    @Test
    void builtinReflectsIdPrefix() {
        assertTrue(new Playlist("builtin:c418", "C418", List.of()).builtin());
        assertFalse(new Playlist("custom:mine", "Mine", List.of()).builtin());
    }
}
