package studio.zojer.taswell.library;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import studio.zojer.taswell.track.Track;
import studio.zojer.taswell.track.TrackSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The C418 allowlist: vanilla Minecraft tracks the mod is permitted to schedule.
 *
 * <p>{@link #load()} is pure JVM — its code path never touches {@code net.minecraft.*}
 * or {@code net.fabricmc.*} (it only reads the classpath resource
 * {@code /assets/taswell/c418_tracks.json} via Gson), so it runs under plain JUnit
 * with no game environment. {@link #registerSoundEvents()} is the MC-touching half
 * (hence the imports below), called once from {@code Taswell.onInitializeClient()}.
 */
public final class VanillaTracks {
    private static final String ALLOWLIST_RESOURCE = "/assets/taswell/c418_tracks.json";

    private VanillaTracks() {
    }

    private record AllowlistEntry(String slug, String title, String path) {
    }

    /**
     * Parses the C418 allowlist off the classpath. Pure JVM: safe to call from unit
     * tests with no Minecraft classes on the classpath.
     */
    public static List<Track> load() {
        Gson gson = new Gson();
        try (InputStream in = VanillaTracks.class.getResourceAsStream(ALLOWLIST_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + ALLOWLIST_RESOURCE);
            }
            Type listType = new TypeToken<List<AllowlistEntry>>() {
            }.getType();
            List<AllowlistEntry> entries = gson.fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), listType);
            return entries.stream()
                    .map(e -> new Track(
                            e.slug(),
                            e.title(),
                            "C418",
                            TrackSource.VANILLA,
                            "taswell:track." + e.slug(),
                            null))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + ALLOWLIST_RESOURCE, e);
        }
    }

    /**
     * Registers one {@code SoundEvent} per allowlisted track, id
     * {@code taswell:track.<slug>}, resolved via {@code sounds.json} to the vanilla
     * asset path. Ships no audio: the .ogg files come from the launcher-downloaded
     * assets index, never from this mod's jar.
     */
    public static void registerSoundEvents() {
        for (Track t : load()) {
            Identifier id = Identifier.parse(t.vanillaSoundEventId());
            Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
        }
    }
}
