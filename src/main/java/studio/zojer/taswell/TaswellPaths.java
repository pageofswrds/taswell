package studio.zojer.taswell;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the mod's on-disk locations under {@code config/taswell/}. The only class
 * in this package that touches {@code net.fabricmc.*} — {@link studio.zojer.taswell.store.ConfigStore}
 * and {@link studio.zojer.taswell.store.PlaylistStore} take an explicit {@link Path}
 * instead, so they stay pure JVM and unit-testable. Deliberately untested here: it's a
 * thin wrapper over {@link FabricLoader}, exercisable only inside a running game.
 */
public final class TaswellPaths {
    private TaswellPaths() {
    }

    /** {@code <game config dir>/taswell/} */
    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("taswell");
    }

    public static Path configFile() {
        return configDir().resolve("config.json");
    }

    public static Path playlistsFile() {
        return configDir().resolve("playlists.json");
    }

    /** Default folder scanned for local MP3s when {@code TaswellConfig.musicFolder} is null. */
    public static Path defaultMusicDir() {
        return configDir().resolve("music");
    }

    /** Creates {@link #configDir()} and {@link #defaultMusicDir()} if absent. */
    public static void ensureDirs() {
        try {
            Files.createDirectories(configDir());
            Files.createDirectories(defaultMusicDir());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create taswell config directories", e);
        }
    }
}
