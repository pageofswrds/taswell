package studio.zojer.taswell.audio;

import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.zojer.taswell.track.Track;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * A non-positional {@link SoundSource#MUSIC} sound instance that streams a local mp3/wav
 * {@link Track} through the vanilla sound engine via {@code fabric-sound-api-v1}'s {@link
 * FabricSoundInstance#getAudioStream} hook.
 *
 * <p>MC-touching — verified against the 26.2 mapped sources, not unit-tested; exercised via the
 * in-game smoke test instead.
 *
 * <p>API-drift note: the brief's illustrative constructor call was written against {@code
 * ResourceLocation}; 26.2 Mojmap renames that to {@link Identifier} (same drift Task 2 hit).
 * {@code FabricSoundInstance.EMPTY_SOUND} is itself typed as an {@code Identifier} in this
 * version, which lines up with {@link AbstractSoundInstance}'s {@code (Identifier, SoundSource,
 * RandomSource)} constructor overload (there's also a {@code SoundEvent}-taking overload, not
 * used here). Also worth noting: in 26.2, vanilla's own {@code SoundInstance} interface already
 * extends {@code FabricSoundInstance} directly (an interface injection from fabric-sound-api-v1),
 * so the {@code implements FabricSoundInstance} clause below is redundant with what {@link
 * AbstractSoundInstance} already provides transitively — kept explicit anyway, per the brief's
 * interface description, since it documents intent and costs nothing.
 *
 * <p><b>Bigger deviation, found only by running it in-game:</b> the brief's pseudocode passes
 * {@code FabricSoundInstance.EMPTY_SOUND} (the identifier {@code fabric-sound-api-v1:empty})
 * directly as this instance's own identifier. That does <em>not</em> work — {@code
 * AbstractSoundInstance.resolve()} only special-cases vanilla's own {@code
 * SoundManager.INTENTIONALLY_EMPTY_SOUND_LOCATION} constant, not fabric-sound-api-v1's field; for
 * any other identifier it falls through to {@code soundManager.getSoundEvent(identifier)}, a
 * lookup against sound events assembled purely from loaded {@code sounds.json} files. Since
 * nothing declares a sound event at {@code fabric-sound-api-v1:empty}, that lookup returns
 * {@code null} and {@code SoundEngine.play()} bails out at {@code "Unable to play unknown
 * soundEvent"} — before ever reaching the streaming path that would call {@link
 * #getAudioStream}. Confirmed both by reading vanilla's {@code SoundEngine.play()} source and by
 * observing exactly that warning during the in-game smoke test.
 *
 * <p>The actual (upstream-documented) pattern — confirmed via fabric-api's own javadoc for
 * {@code EMPTY_SOUND} ("an empty sound which may be used as a placeholder in your sounds.json
 * file for sounds with custom audio streams") — is to declare <em>your own</em> sound event in
 * your mod's {@code sounds.json} whose {@code "name"} is {@code "fabric-sound-api-v1:empty"} and
 * {@code "stream": true}, then construct instances against that identifier. This mod declares
 * {@code taswell:local_track} in {@code src/main/resources/assets/taswell/sounds.json} for
 * exactly that purpose; {@link #TASWELL_LOCAL_TRACK} is passed to the superclass constructor
 * below instead of {@code FabricSoundInstance.EMPTY_SOUND} directly. The {@code "stream": true}
 * flag on that placeholder is what makes {@code SoundEngine.play()} take the streaming branch at
 * all (it drives {@code sound.shouldStream()}, independent of and prior to the mixin redirect to
 * {@link #getAudioStream}); the actual audio bytes at {@code fabric-sound-api-v1:empty}'s target
 * path are never read, since {@link #getAudioStream} fully replaces the stream Minecraft would
 * otherwise open. Not registering {@code taswell:local_track} in {@code BuiltInRegistries
 * .SOUND_EVENT} is deliberate too: {@code SoundManager.getSoundEvent} only ever consults its
 * {@code sounds.json}-built registry, never the built-in registry, and registering it there would
 * additionally trip an IDE-mode "missing subtitle" error {@code SoundManager.apply()} logs for
 * registry-backed events without a resolvable subtitle component.
 *
 * <p>Deviation beyond the brief's pseudocode: {@code fabric-sound-api-v1}'s {@code
 * SoundEngineMixin} redirects vanilla's {@code SoundBufferLibrary.getStream(...)} call to this
 * method, and vanilla's {@code SoundEngine.play()} consumes the resulting future with a bare
 * {@code .thenAccept(...)} — no {@code .exceptionally()} handler. An exceptionally-completed
 * future from {@link #getAudioStream} is therefore silently dropped by vanilla: no log, no
 * callback, nothing. That's fine for the mid-stream case (handled by {@link Mp3AudioStream}
 * once it exists), but a decode failure at <em>open</em> time (e.g. a corrupt file) never gets
 * as far as constructing an {@link Mp3AudioStream}, so nothing would fire {@code onFinished} or
 * leave a trace in the log. To keep "the director never stalls" true for this failure mode too,
 * the catch branch below logs the failure and fires {@code onFinished} itself before wrapping
 * and rethrowing (so the future still completes exceptionally for anything else watching it).
 */
public final class LocalTrackSoundInstance extends AbstractSoundInstance implements FabricSoundInstance {

    private static final Logger LOG = LoggerFactory.getLogger(LocalTrackSoundInstance.class);

    /**
     * This mod's own placeholder sound event — declared in {@code assets/taswell/sounds.json} as
     * {@code {"sounds": [{"name": "fabric-sound-api-v1:empty", "stream": true}]}} — used in place
     * of {@code FabricSoundInstance.EMPTY_SOUND} directly. See the class javadoc.
     */
    private static final Identifier TASWELL_LOCAL_TRACK = Identifier.fromNamespaceAndPath("taswell", "local_track");

    private final Track track;
    private final Runnable onFinished;

    public LocalTrackSoundInstance(Track track, Runnable onFinished) {
        super(TASWELL_LOCAL_TRACK, SoundSource.MUSIC, SoundInstance.createUnseededRandom());
        this.track = track;
        this.onFinished = onFinished;
        this.relative = true;
        this.attenuation = Attenuation.NONE; // non-positional music
        this.volume = 1.0f;
    }

    @Override
    public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, Identifier id, boolean repeatInstantly) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new Mp3AudioStream(Mp3Decoder.open(track.localFile()), onFinished);
            } catch (Exception e) {
                LOG.error("failed to open local track {}", track.localFile(), e);
                onFinished.run();
                throw new CompletionException(e);
            }
        }, Util.backgroundExecutor());
    }
}
