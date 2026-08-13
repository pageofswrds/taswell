package studio.zojer.taswell.director;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.zojer.taswell.TaswellPaths;
import studio.zojer.taswell.audio.LocalTrackSoundInstance;
import studio.zojer.taswell.library.Library;
import studio.zojer.taswell.library.LibraryScanner;
import studio.zojer.taswell.library.VanillaTracks;
import studio.zojer.taswell.rotation.RepeatMode;
import studio.zojer.taswell.rotation.RotationEngine;
import studio.zojer.taswell.store.ConfigStore;
import studio.zojer.taswell.store.Playlist;
import studio.zojer.taswell.store.PlaylistStore;
import studio.zojer.taswell.store.TaswellConfig;
import studio.zojer.taswell.track.Track;
import studio.zojer.taswell.track.TrackSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Owns the mod's ambient rotation: what's playing, when the next track starts, and the
 * user-facing controls (skip, pause, playlist/shuffle/repeat toggles) a future HUD/config UI
 * drives. Singleton — {@link #get()} — constructed lazily on first access and lives for the
 * client process, same lifetime as {@code Minecraft.getInstance()}.
 *
 * <p>MC-touching (playback, {@code Minecraft.getInstance()}, the sound manager) — not unit
 * tested; the pure selection logic it delegates to lives in {@link RotationEngine}, which is.
 * Verified in-game instead (see the task report).
 *
 * <p>{@link #vanillaSuppressed()} is the same kill switch {@code MusicManagerMixin} (Task 6)
 * routes through — now that this director exists, vanilla ambient music stays suppressed
 * unconditionally: this class is the sole source of ambient music from here on.
 */
public final class MusicDirector {
    private static final Logger LOG = LoggerFactory.getLogger(MusicDirector.class);
    private static final int TICKS_PER_SECOND = 20;
    /** {@link #previous()}'s "still basically just started" window, in ticks (see brief). */
    private static final int PREVIOUS_RESTART_WINDOW_TICKS = 60;
    /** Gap before repeating the same track under {@link RepeatMode#ONE} — short, not silent. */
    private static final int REPEAT_ONE_GAP_TICKS = 20;

    private static final MusicDirector INSTANCE = new MusicDirector();

    public static MusicDirector get() {
        return INSTANCE;
    }

    /**
     * Gate for vanilla ambient music. Kept as a static constant per Task 6's contract (the
     * mixin calls this without touching the director instance) — this director now owns all
     * ambient music, so vanilla stays suppressed unconditionally; there's no "disable mod"
     * toggle yet (Task 6's stub anticipated one, out of this task's scope).
     */
    public static boolean vanillaSuppressed() {
        return true;
    }

    private final Library library;
    private final RotationEngine rotationEngine;
    private final Random gapRandom = new Random();
    private final List<Consumer<Track>> trackStartedListeners = new CopyOnWriteArrayList<>();

    private TaswellConfig config;
    private List<Playlist> customPlaylists;

    /** Null when idle (in the gap between tracks, or paused with nothing remembered). */
    private SoundInstance currentInstance;
    /** Null exactly when {@code currentInstance} is — except across a pause, see {@link #togglePause()}. */
    private Track currentTrack;
    private int gapTicksRemaining;
    private boolean paused;
    private int ticksSinceCurrentStarted;

    /** Rotation continuity: what {@link RotationEngine#next} should treat as "just played". */
    private String lastTrackId;
    /** 1-deep history for {@link #previous()}. */
    private String previousTrackId;

    private MusicDirector() {
        this.library = new Library(VanillaTracks.load());
        this.config = ConfigStore.load(TaswellPaths.configFile());
        this.customPlaylists = new ArrayList<>(PlaylistStore.load(TaswellPaths.playlistsFile()));
        this.rotationEngine = new RotationEngine(System.nanoTime());
        this.gapTicksRemaining = randomGapTicks();
    }

    /**
     * Kicks off the local-folder scan on {@link Util#backgroundExecutor()}, posting the result
     * back to the client thread via {@code Minecraft.getInstance().execute(...)} — the initial
     * scan can touch many files (ID3 reads per track) so it must never run inline on the client
     * thread. Called once from {@code Taswell.onInitializeClient()}; harmless to call again
     * (e.g. a future "rescan" button) since {@link Library#setLocal} replaces wholesale.
     */
    public void scanMusicFolderAsync() {
        Path folder = musicFolder();
        Util.backgroundExecutor().execute(() -> {
            List<Track> scanned = LibraryScanner.scan(folder);
            Minecraft.getInstance().execute(() -> {
                library.setLocal(scanned);
                LOG.debug("taswell: scanned {} local track(s) from {}", scanned.size(), folder);
            });
        });
    }

    /**
     * Called every client tick ({@code ClientTickEvents.END_CLIENT_TICK}). While paused, or
     * while a track is playing, there's nothing to do here: a local track's end arrives via its
     * {@code onFinished} callback (see {@link #buildInstance}), and a vanilla track's end has no
     * such callback, so it's polled here via {@code soundManager.isActive} — but only while a
     * vanilla instance is actually current, never for local tracks (whose liveness is the
     * callback's job, not this poll's). When idle, count down the gap; at zero, advance.
     */
    public void tick() {
        if (paused) {
            return;
        }
        if (currentTrack != null) {
            ticksSinceCurrentStarted++;
            if (currentTrack.source() == TrackSource.VANILLA && currentInstance != null
                    && !Minecraft.getInstance().getSoundManager().isActive(currentInstance)) {
                onTrackEnded();
            }
            return;
        }
        if (gapTicksRemaining > 0) {
            gapTicksRemaining--;
            return;
        }
        advance();
    }

    /**
     * Called from a local track's {@code onFinished} callback (hopped to the client thread
     * already) or from {@link #tick}'s vanilla-liveness poll. Never called for a user-initiated
     * stop (pause, skip, playNow) — those manage state directly; per Task 5's finding, {@code
     * LocalTrackSoundInstance} does not fire {@code onFinished} on stop-without-EOF, so this
     * method would never see one anyway, but the guard below is cheap insurance against a stray
     * late callback racing a state change.
     */
    private void onTrackEnded() {
        if (currentTrack == null) {
            return;
        }
        currentInstance = null;
        currentTrack = null;
        gapTicksRemaining = repeatMode() == RepeatMode.ONE ? REPEAT_ONE_GAP_TICKS : randomGapTicks();
    }

    /** Picks and plays the next track per rotation; missing/null library entries are skipped. */
    private void advance() {
        List<String> ids = resolvedActivePlaylistIds();
        if (ids.isEmpty()) {
            gapTicksRemaining = randomGapTicks();
            return;
        }
        Optional<String> nextId = rotationEngine.next(ids, lastTrackId, config.shuffle, repeatMode());
        if (nextId.isEmpty()) {
            // RepeatMode.OFF, end of playlist reached: stay idle rather than stopping forever
            // on a zero gap — a future setActivePlaylist/setShuffle/cycleRepeat call will let
            // the next tick's advance() try again.
            gapTicksRemaining = randomGapTicks();
            return;
        }
        play(nextId.get());
    }

    private void play(String trackId) {
        Optional<Track> trackOpt = library.byId(trackId);
        if (trackOpt.isEmpty()) {
            LOG.warn("taswell: rotation picked unknown track id {} — skipping", trackId);
            gapTicksRemaining = randomGapTicks();
            return;
        }
        Track track = trackOpt.get();
        stopCurrentInstance();

        SoundInstance instance = buildInstance(track);
        SoundEngine.PlayResult result = Minecraft.getInstance().getSoundManager().play(instance);
        if (result == SoundEngine.PlayResult.NOT_STARTED) {
            LOG.warn("taswell: failed to start track {} ({}) — skipping", track.id(), track.title());
            currentInstance = null;
            currentTrack = null;
            gapTicksRemaining = randomGapTicks();
            return;
        }

        currentInstance = instance;
        currentTrack = track;
        // Only overwrite the remembered "previous" when this is genuinely a different track —
        // repeats (RepeatMode.ONE) and restarts (togglePause/previous's own restart branch)
        // must not clobber it with the track that's simply playing again.
        if (lastTrackId != null && !lastTrackId.equals(trackId)) {
            previousTrackId = lastTrackId;
        }
        lastTrackId = trackId;
        ticksSinceCurrentStarted = 0;
        gapTicksRemaining = 0;
        notifyTrackStarted(track);
    }

    private SoundInstance buildInstance(Track track) {
        if (track.source() == TrackSource.VANILLA) {
            Identifier id = Identifier.parse(track.vanillaSoundEventId());
            SoundEvent event = BuiltInRegistries.SOUND_EVENT.getValue(id);
            if (event == null) {
                throw new IllegalStateException(
                        "no SoundEvent registered for " + track.vanillaSoundEventId()
                                + " — VanillaTracks.registerSoundEvents() should have run at client init");
            }
            return SimpleSoundInstance.forMusic(event);
        }
        return new LocalTrackSoundInstance(track, () -> Minecraft.getInstance().execute(this::onTrackEnded));
    }

    private void stopCurrentInstance() {
        if (currentInstance != null) {
            Minecraft.getInstance().getSoundManager().stop(currentInstance);
            currentInstance = null;
        }
    }

    /** Plays {@code trackId} immediately, interrupting whatever's current. Rotation continuity is untouched beyond {@code lastTrackId} becoming this id — an "interjection", not a rotation pick. */
    public void playNow(String trackId) {
        paused = false;
        play(trackId);
    }

    /** Skips to whatever {@link RotationEngine} says comes after the currently-playing (or just-played) track. */
    public void next() {
        paused = false;
        advance();
    }

    /**
     * Restarts the current track if it began less than {@link #PREVIOUS_RESTART_WINDOW_TICKS}
     * ago, otherwise steps back to the remembered previous track (1-deep history only — a
     * second consecutive {@code previous()} call restarts rather than stepping further back).
     * If nothing is currently loaded (idle in the gap), falls back to the remembered previous
     * track, or a fresh {@link #advance()} if there's no history yet — this fallback isn't
     * spelled out by the brief's two named cases, but avoids doing nothing on a button press.
     */
    public void previous() {
        paused = false;
        if (currentTrack == null) {
            if (previousTrackId != null) {
                play(previousTrackId);
            } else {
                advance();
            }
            return;
        }
        if (ticksSinceCurrentStarted < PREVIOUS_RESTART_WINDOW_TICKS && previousTrackId != null) {
            play(previousTrackId);
        } else {
            play(currentTrack.id());
        }
    }

    /**
     * v1 semantics: pause stops the current instance outright and remembers {@code
     * currentTrack}; unpausing restarts that track from the beginning. Minecraft's sound engine
     * has no mid-stream pause/resume for a streamed instance, and the spec cut seek support, so
     * there is no true "resume where it left off" here — documented honestly rather than faked.
     */
    public void togglePause() {
        if (paused) {
            paused = false;
            if (currentTrack != null) {
                play(currentTrack.id());
            }
            // else: idle (mid-gap) — tick() resumes counting down on its own.
        } else {
            paused = true;
            stopCurrentInstance();
            // currentTrack is deliberately left set — that's what "remembers" it for unpause.
        }
    }

    public void setActivePlaylist(String id) {
        config.activePlaylistId = id;
        saveConfig();
    }

    public void setShuffle(boolean shuffle) {
        config.shuffle = shuffle;
        saveConfig();
    }

    public void cycleRepeat() {
        RepeatMode[] modes = RepeatMode.values();
        RepeatMode nextMode = modes[(repeatMode().ordinal() + 1) % modes.length];
        config.repeatMode = nextMode.name();
        saveConfig();
    }

    public Optional<Track> nowPlaying() {
        return Optional.ofNullable(currentTrack);
    }

    public boolean isPaused() {
        return paused;
    }

    /** Registered listeners are notified (client thread) every time a new track starts — the HUD's hook. */
    public void onTrackStarted(Consumer<Track> listener) {
        trackStartedListeners.add(listener);
    }

    private void notifyTrackStarted(Track track) {
        LOG.debug("taswell: now playing {} — {} ({})", track.title(), track.artist(), track.id());
        for (Consumer<Track> listener : trackStartedListeners) {
            listener.accept(track);
        }
    }

    private void saveConfig() {
        ConfigStore.save(TaswellPaths.configFile(), config);
    }

    private RepeatMode repeatMode() {
        try {
            return RepeatMode.valueOf(config.repeatMode);
        } catch (IllegalArgumentException | NullPointerException e) {
            return RepeatMode.PLAYLIST;
        }
    }

    private Path musicFolder() {
        return config.musicFolder != null ? Path.of(config.musicFolder) : TaswellPaths.defaultMusicDir();
    }

    private Playlist activePlaylist() {
        String id = config.activePlaylistId;
        return library.builtins().stream().filter(p -> p.id().equals(id)).findFirst()
                .or(() -> customPlaylists.stream().filter(p -> p.id().equals(id)).findFirst())
                .orElseGet(() -> new Playlist(id, id, List.of()));
    }

    private List<String> resolvedActivePlaylistIds() {
        return library.resolve(activePlaylist()).stream()
                .filter(entry -> entry.track() != null)
                .map(Library.Entry::trackId)
                .toList();
    }

    private int randomGapTicks() {
        int min = Math.min(config.minGapSeconds, config.maxGapSeconds);
        int max = Math.max(config.minGapSeconds, config.maxGapSeconds);
        int seconds = min == max ? min : min + gapRandom.nextInt(max - min + 1);
        return seconds * TICKS_PER_SECOND;
    }
}
