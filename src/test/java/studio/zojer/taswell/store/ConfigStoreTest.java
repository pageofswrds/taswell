package studio.zojer.taswell.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreTest {
    @Test
    void corruptConfigIsQuarantinedAndDefaulted(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config.json");
        Files.writeString(f, "{nope");
        TaswellConfig cfg = ConfigStore.load(f);
        assertEquals("builtin:c418", cfg.activePlaylistId);
        assertTrue(Files.exists(dir.resolve("config.json.bad")));
        assertFalse(Files.exists(f));
    }

    @Test
    void loadStillReturnsDefaultsWhenQuarantineItselfFails(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config.json");
        Files.writeString(f, "{nope");
        // Block the quarantine move: make "config.json.bad" an existing non-empty
        // directory, so Files.move(f, bad, REPLACE_EXISTING) fails.
        Path bad = dir.resolve("config.json.bad");
        Files.createDirectory(bad);
        Files.writeString(bad.resolve("occupied.txt"), "x");

        TaswellConfig cfg = assertDoesNotThrow(() -> ConfigStore.load(f));

        assertEquals("builtin:c418", cfg.activePlaylistId);
        // Quarantine couldn't complete, so the corrupt file is left in place rather
        // than lost.
        assertTrue(Files.exists(f));
    }

    @Test
    void missingFileReturnsDefaults(@TempDir Path dir) {
        Path f = dir.resolve("config.json");

        TaswellConfig cfg = ConfigStore.load(f);

        assertNull(cfg.musicFolder);
        assertEquals(60, cfg.minGapSeconds);
        assertEquals(300, cfg.maxGapSeconds);
        assertTrue(cfg.hudEnabled);
        assertEquals("builtin:c418", cfg.activePlaylistId);
        assertTrue(cfg.shuffle);
        assertEquals("PLAYLIST", cfg.repeatMode);
    }

    @Test
    void saveThenLoadRoundTripsAllFields(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config.json");
        TaswellConfig cfg = new TaswellConfig();
        cfg.musicFolder = "/Users/david/Music/taswell";
        cfg.minGapSeconds = 30;
        cfg.maxGapSeconds = 120;
        cfg.hudEnabled = false;
        cfg.activePlaylistId = "custom:my-mix";
        cfg.shuffle = false;
        cfg.repeatMode = "TRACK";

        ConfigStore.save(f, cfg);
        TaswellConfig loaded = ConfigStore.load(f);

        assertEquals(cfg.musicFolder, loaded.musicFolder);
        assertEquals(cfg.minGapSeconds, loaded.minGapSeconds);
        assertEquals(cfg.maxGapSeconds, loaded.maxGapSeconds);
        assertEquals(cfg.hudEnabled, loaded.hudEnabled);
        assertEquals(cfg.activePlaylistId, loaded.activePlaylistId);
        assertEquals(cfg.shuffle, loaded.shuffle);
        assertEquals(cfg.repeatMode, loaded.repeatMode);
    }

    @Test
    void saveWithNullMusicFolderRoundTripsToDefault(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config.json");
        TaswellConfig cfg = new TaswellConfig();
        // musicFolder left null -> "use default"

        ConfigStore.save(f, cfg);
        TaswellConfig loaded = ConfigStore.load(f);

        assertNull(loaded.musicFolder);
    }
}
