package studio.zojer.taswell.library;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import studio.zojer.taswell.track.Track;
import studio.zojer.taswell.track.TrackSource;

import java.io.InputStreamReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllowlistTest {
    @Test
    void allowlistParsesAndIsWellFormed() throws Exception {
        List<Track> tracks = VanillaTracks.load();
        assertFalse(tracks.isEmpty());
        for (Track t : tracks) {
            assertEquals(TrackSource.VANILLA, t.source());
            assertEquals("C418", t.artist());
            assertTrue(t.vanillaSoundEventId().startsWith("taswell:track."));
            assertNull(t.localFile());
        }
        // spot checks
        assertTrue(tracks.stream().anyMatch(t -> t.title().equals("Sweden")));
        assertTrue(tracks.stream().anyMatch(t -> t.title().equals("Taswell")));
    }

    @Test
    void soundsJsonCoversEveryAllowlistEntry() throws Exception {
        JsonObject sounds = JsonParser.parseReader(new InputStreamReader(
                AllowlistTest.class.getResourceAsStream("/assets/taswell/sounds.json"))).getAsJsonObject();
        for (Track t : VanillaTracks.load()) {
            String key = t.vanillaSoundEventId().substring("taswell:".length());
            assertTrue(sounds.has(key), "sounds.json missing " + key);
            JsonObject entry = sounds.getAsJsonObject(key).getAsJsonArray("sounds").get(0).getAsJsonObject();
            assertTrue(entry.get("name").getAsString().startsWith("minecraft:music"), key);
            assertTrue(entry.get("stream").getAsBoolean(), key);
        }
    }
}
