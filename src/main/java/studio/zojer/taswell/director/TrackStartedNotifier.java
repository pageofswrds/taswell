package studio.zojer.taswell.director;

import org.slf4j.Logger;
import studio.zojer.taswell.track.Track;

import java.util.List;
import java.util.function.Consumer;

/**
 * Calls each {@code onTrackStarted} listener in turn, isolating them from each other's
 * failures. Review finding: {@code MusicDirector.notifyTrackStarted} had no such isolation, and
 * is reached from {@code MusicDirector.tick()} — itself called every tick from the Fabric client
 * tick event — so an uncaught exception thrown by one listener (a HUD bug, Tasks 8-10) would
 * propagate out of the tick handler and crash the client's tick loop entirely, for a failure
 * that's local to a single listener and should never be fatal to playback.
 *
 * <p>Pure JVM — {@code Track} is a plain record and {@code Logger} is an {@code org.slf4j}
 * facade type, not Minecraft/Fabric API (same allowance {@code ConfigStore}/{@code
 * PlaylistStore} already rely on) — so this is unit-testable without booting {@link
 * MusicDirector}'s singleton (which touches {@code FabricLoader} at construction).
 */
final class TrackStartedNotifier {
    private TrackStartedNotifier() {
    }

    static void notifyAll(List<Consumer<Track>> listeners, Track track, Logger log) {
        for (Consumer<Track> listener : listeners) {
            try {
                listener.accept(track);
            } catch (RuntimeException e) {
                log.error("taswell: onTrackStarted listener threw — ignoring so playback isn't affected", e);
            }
        }
    }
}
