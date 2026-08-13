package studio.zojer.taswell.director;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.WinScreen;
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
import studio.zojer.taswell.rotation.GapTiming;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
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
    /**
     * Guards against a stale end-of-track signal corrupting state — see its class javadoc for
     * the race this exists to close (a local track's async {@code onFinished} callback and
     * {@code tick()}'s {@code isActive} poll can both observe the same track ending, and either
     * can race a user-initiated skip/pause/playNow that's already moved on).
     */
    private final PlaybackGeneration playback = new PlaybackGeneration();

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
    /**
     * The last {@code activePlaylistId} a warning was already logged for — {@link
     * #activePlaylist()} falls back to an empty synthesized playlist whenever the configured id
     * doesn't resolve (a stale/hand-edited/deleted-playlist id), and is called on every {@link
     * #advance()}; without this guard the same warning would repeat every rotation cycle for the
     * rest of the session instead of once.
     */
    private String lastWarnedUnresolvedPlaylistId;
    /**
     * Track ids that failed to start this session (spec §3: "allowlist path failing to resolve
     * ... that track drops from the library for the session"). Populated in {@link #play} on a
     * {@link SoundEngine.PlayResult#NOT_STARTED} result — most commonly a vanilla allowlist entry
     * whose {@code sounds.json} mapping doesn't resolve to a real asset (a future MC version
     * moving/renaming the underlying ogg). {@link #resolvedActivePlaylistIds} filters these out.
     *
     * <p>Without this, a failing track wedges the <em>whole playlist</em> rather than just
     * itself: {@link #play} only advances {@link #lastTrackId} on success, so a {@code
     * NOT_STARTED} result leaves it unchanged; in ordered mode {@link RotationEngine#next}
     * treats an unchanged/not-found {@code lastTrackId} the same as "rotation hasn't started
     * yet" and restarts from index 0 — which, unfiltered, is the same failing track every time.
     * Confirmed live (see the task report): a bogus allowlist entry retried every gap cycle
     * forever, logging a fresh warning each time, and no other C418 track ever played.
     */
    private final Set<String> failedTrackIds = new HashSet<>();

    private MusicDirector() {
        this.library = new Library(VanillaTracks.load());
        this.config = ConfigStore.load(TaswellPaths.configFile());
        this.customPlaylists = new ArrayList<>(PlaylistStore.load(TaswellPaths.playlistsFile()));
        this.rotationEngine = new RotationEngine(System.nanoTime());
        this.gapTicksRemaining = randomGapTicks();
    }

    /**
     * Re-reads custom playlists from disk, replacing {@link #customPlaylists} wholesale — the
     * fix for a real bug: this director previously loaded {@code playlists.json} exactly once,
     * in its constructor, and never again, so any playlist created or edited in a session (via
     * {@link studio.zojer.taswell.ui.PlayerScreen}, which keeps and persists its own separate
     * in-memory copy) was invisible to {@link #activePlaylist()} until the game restarted — a
     * newly-created active playlist would resolve to nothing and silently degrade to the
     * warn-once empty-playlist fallback in {@link #activePlaylist()}'s {@code orElseGet}.
     *
     * <p>Called from {@link studio.zojer.taswell.ui.PlayerScreen} wherever its own copy is
     * persisted or the active selection changes (see its {@code savePlaylists()} and {@code
     * onPlaylistSelected}) — client-thread-only, like every other method here, and trivial: a
     * single {@link PlaylistStore#load} call, same shape as the constructor's own initial load.
     */
    public void reloadPlaylists() {
        this.customPlaylists = new ArrayList<>(PlaylistStore.load(TaswellPaths.playlistsFile()));
        LOG.debug("taswell: reloaded {} custom playlist(s) from disk", customPlaylists.size());
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
            List<Track> scanned;
            try {
                scanned = LibraryScanner.scan(folder);
            } catch (RuntimeException e) {
                // Mirrors PlayerScreen.onRefresh's guard: LibraryScanner.scan doesn't throw for
                // ordinary cases (a missing folder yields an empty list), but this is the mod's
                // very first scan — called once from Taswell.onInitializeClient() — so an
                // unusual failure here (e.g. an IO error mid-scan) must not crash mod init or
                // leave the library silently unset; leave it empty (its constructor default)
                // and let a later Refresh in the PlayerScreen retry.
                LOG.warn("taswell: initial scan of {} failed — starting with an empty local library", folder, e);
                scanned = null;
            }
            List<Track> result = scanned;
            Minecraft.getInstance().execute(() -> {
                if (result != null) {
                    library.setLocal(result);
                    LOG.debug("taswell: scanned {} local track(s) from {}", result.size(), folder);
                }
            });
        });
    }

    /**
     * Called every client tick ({@code ClientTickEvents.END_CLIENT_TICK}). While paused, or
     * while a track is playing, there's nothing to do here beyond a liveness poll: a local
     * track's end normally arrives via its {@code onFinished} callback (see {@link
     * #buildInstance}), and a vanilla track's end has no such callback at all — but {@code
     * soundManager.isActive} is polled for <em>both</em> here as a backstop, not just vanilla.
     * A local track's callback can be lost (e.g. {@code SoundEngine.stopAll()} from an audio
     * device change or a resource reload tears the channel down without a stream EOF), which
     * would otherwise wedge rotation silently for the rest of the session; {@link
     * #playback}'s generation check makes it safe for the poll and the callback to race —
     * whichever gets to {@link #onTrackEnded} first wins, the other is recognized as stale.
     * When idle, count down the gap; at zero, advance.
     *
     * <p>Checked before anything else: {@link #isCredits()}, {@code MusicManagerMixin}'s own
     * escape hatch for the End-credits {@link WinScreen} (vanilla's scripted credits track is
     * allowed to play there, unsuppressed). Without a matching hold here, this director would
     * keep advancing/starting tracks underneath the credits track (talking over it), and
     * wouldn't stop whatever was already playing the moment credits begin. Entering credits
     * stops the current instance outright (via {@link #stopIfPlayingForCredits()}) and this
     * method returns without counting down the gap or advancing, so nothing new starts until
     * credits end and a normal tick resumes the gap countdown where it left off.
     */
    public void tick() {
        if (isCredits()) {
            stopIfPlayingForCredits();
            return;
        }
        if (paused) {
            return;
        }
        if (currentTrack != null) {
            ticksSinceCurrentStarted++;
            if (currentInstance != null
                    && !Minecraft.getInstance().getSoundManager().isActive(currentInstance)) {
                onTrackEnded(playback.current());
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
     * already, carrying the generation id captured when that track's {@link #play} started it)
     * or from {@link #tick}'s liveness poll (which always passes {@link #playback}'s
     * live-at-that-moment current generation). {@code generation} is checked against {@link
     * #playback} before touching any state: a stale generation means either a newer track has
     * since started (this is a signal for a track that's no longer current — e.g. a still-
     * pending decode failure for a track {@link #next()} already skipped away from) or the
     * other detection path already processed this exact end — either way, acting on it here
     * would clear the wrong track's state or double-advance rotation. Never legitimately called
     * for a user-initiated stop (pause, skip, playNow) — those manage state directly and
     * invalidate {@link #playback} themselves — so in practice this only ever fires once per
     * genuine end, from whichever of the two detectors got there first.
     */
    private void onTrackEnded(long generation) {
        if (!playback.endIfCurrent(generation)) {
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
            stopToSilence();
            gapTicksRemaining = randomGapTicks();
            return;
        }
        Optional<String> nextId = rotationEngine.next(ids, lastTrackId, config.shuffle, repeatMode());
        if (nextId.isEmpty()) {
            // RepeatMode.OFF, end of playlist reached: stay idle rather than stopping forever
            // on a zero gap — a future setActivePlaylist/setShuffle/cycleRepeat call will let
            // the next tick's advance() try again.
            stopToSilence();
            gapTicksRemaining = randomGapTicks();
            return;
        }
        play(nextId.get());
    }

    /**
     * Stops whatever's currently loaded and clears it, without picking a replacement. Only
     * matters when {@link #advance()} is reached with something still playing — normally true
     * only for a user-initiated {@link #next()} (the natural end-of-track path via {@link
     * #onTrackEnded} already clears {@code currentTrack} before {@link #tick} ever calls {@link
     * #advance()}) — and the active playlist has since become empty, or ordered/repeat-off
     * rotation has reached its end: without this, {@link #next()} would silently no-op and leave
     * the old track audibly playing, which looks like the skip button did nothing. Stopping to
     * silence here is a deliberate, acceptable fallback — better than a skip that appears broken.
     */
    private void stopToSilence() {
        if (currentTrack != null) {
            stopCurrentInstance();
            currentTrack = null;
            playback.invalidate();
        }
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

        long generation = playback.start();
        SoundInstance instance = buildInstance(track, generation);
        SoundEngine.PlayResult result = Minecraft.getInstance().getSoundManager().play(instance);
        if (result == SoundEngine.PlayResult.NOT_STARTED) {
            LOG.warn("taswell: failed to start track {} ({}) — dropping it from rotation for the "
                    + "rest of this session", track.id(), track.title());
            failedTrackIds.add(trackId);
            currentInstance = null;
            currentTrack = null;
            playback.invalidate();
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

    /**
     * @param generation this specific play attempt's id from {@link #playback} — captured by
     *                    the local-track callback below so a late/duplicate signal for it can
     *                    be recognized as stale by {@link #onTrackEnded(long)} even after a
     *                    newer track has since started.
     */
    private SoundInstance buildInstance(Track track, long generation) {
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
        return new LocalTrackSoundInstance(track,
                () -> Minecraft.getInstance().execute(() -> onTrackEnded(generation)));
    }

    private void stopCurrentInstance() {
        if (currentInstance != null) {
            Minecraft.getInstance().getSoundManager().stop(currentInstance);
            currentInstance = null;
        }
    }

    /** Mirrors {@code MusicManagerMixin}'s own check — see that class's javadoc for why. */
    private static boolean isCredits() {
        return Minecraft.getInstance().gui.screen() instanceof WinScreen;
    }

    /**
     * Entering credits stops whatever this director has currently loaded — same stop +
     * {@link PlaybackGeneration#invalidate()} shape as {@link #stopToSilence()}, so a late
     * callback/poll for the just-stopped instance can't sneak in and advance rotation while
     * credits are showing — but guarded on {@code currentInstance != null} rather than {@code
     * currentTrack != null}: a paused, remembered-for-resume track ({@link #togglePause()}
     * already stopped its instance and left {@code currentTrack} set on purpose, for unpause to
     * restart) must not be cleared out just because credits happen to show while paused.
     */
    private void stopIfPlayingForCredits() {
        if (currentInstance != null) {
            LOG.debug("taswell: credits screen active — stopping {} ({})",
                    currentTrack.title(), currentTrack.id());
            stopCurrentInstance();
            currentTrack = null;
            playback.invalidate();
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
     * Steps back to the remembered previous track if the current one began less than {@link
     * #PREVIOUS_RESTART_WINDOW_TICKS} ago, otherwise restarts the current track (1-deep history
     * only — a second consecutive {@code previous()} call, now within the restart window of the
     * track it just stepped back to, restarts that track rather than stepping further back).
     * This is the same convention most media players use: an early press means "I meant the one
     * before this," a later press means "start this one over." If nothing is currently loaded
     * (idle in the gap), falls back to the remembered previous track, or a fresh {@link
     * #advance()} if there's no history yet — this fallback isn't spelled out by the brief's two
     * named cases, but avoids doing nothing on a button press.
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
            // A late signal for the just-stopped instance (a local track's callback, or the
            // vanilla poll if it were still running) must not reach onTrackEnded and clear
            // currentTrack out from under the pause — it's deliberately left set below, that's
            // what "remembers" it for unpause.
            playback.invalidate();
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

    /**
     * The library instance this director plays from. Added for Task 9 (PlayerScreen): the
     * director already held this privately for its own rotation logic; the screen needs it too
     * (browsing the catalog, resolving playlists, feeding a rescan's result back in) and there
     * was no accessor. Trivial getter — no behavior change.
     */
    public Library library() {
        return library;
    }

    /** Persisted shuffle flag. Added for Task 9: the screen needs this for the sidebar toggle's initial state. */
    public boolean isShuffle() {
        return config.shuffle;
    }

    /** Persisted active playlist id. Added for Task 9: seeds the sidebar's initial selection. */
    public String activePlaylistId() {
        return config.activePlaylistId;
    }

    /**
     * Persisted HUD toggle. Added for Task 10 (now-playing HUD): the director already holds
     * {@link TaswellConfig} privately and persists it; there was no accessor for this flag.
     * Trivial getter — no behavior change.
     */
    public boolean hudEnabled() {
        return config.hudEnabled;
    }

    /** Registered listeners are notified (client thread) every time a new track starts — the HUD's hook. */
    public void onTrackStarted(Consumer<Track> listener) {
        trackStartedListeners.add(listener);
    }

    private void notifyTrackStarted(Track track) {
        LOG.debug("taswell: now playing {} — {} ({})", track.title(), track.artist(), track.id());
        TrackStartedNotifier.notifyAll(trackStartedListeners, track, LOG);
    }

    private void saveConfig() {
        ConfigStore.save(TaswellPaths.configFile(), config);
    }

    /** Widened from {@code private} to {@code public} for Task 9 (PlayerScreen's repeat-cycle button label). Same body, no behavior change. */
    public RepeatMode repeatMode() {
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
                .orElseGet(() -> {
                    // A stale/deleted/hand-edited activePlaylistId that resolves to nothing:
                    // degrade to an empty playlist (advance()'s empty-ids branch then just holds
                    // idle) rather than throw. Warn once per distinct bad id, not every rotation
                    // cycle — activePlaylist() is called from resolvedActivePlaylistIds() on
                    // every advance(), and the id doesn't change on its own between advances.
                    if (!Objects.equals(id, lastWarnedUnresolvedPlaylistId)) {
                        LOG.warn("taswell: active playlist id {} does not resolve to any builtin or "
                                + "custom playlist — degrading to an empty playlist", id);
                        lastWarnedUnresolvedPlaylistId = id;
                    }
                    return new Playlist(id, id, List.of());
                });
    }

    private List<String> resolvedActivePlaylistIds() {
        return library.resolve(activePlaylist()).stream()
                .filter(entry -> entry.track() != null)
                .map(Library.Entry::trackId)
                .filter(id -> !failedTrackIds.contains(id))
                .toList();
    }

    /**
     * Delegates to {@link GapTiming} (pure JVM, unit tested) — see its javadoc for why negative
     * {@code minGapSeconds}/{@code maxGapSeconds} are clamped to zero rather than fed straight to
     * {@link Random#nextInt(int)}.
     */
    private int randomGapTicks() {
        return GapTiming.computeTicks(config.minGapSeconds, config.maxGapSeconds, TICKS_PER_SECOND, gapRandom);
    }
}
