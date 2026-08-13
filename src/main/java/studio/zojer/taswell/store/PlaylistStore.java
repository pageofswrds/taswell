package studio.zojer.taswell.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Loads and saves custom {@link Playlist}s as a JSON array. Pure JVM — takes an
 * explicit {@link Path}; see {@code TaswellPaths} for the real on-disk location.
 *
 * <p>Builtin playlists ({@link Playlist#builtin()}) are synthesized by the library
 * (Task 4), never persisted here: {@link #save(Path, List)} strips them before
 * writing, and {@link #load(Path)} strips any it finds (defensive, in case a file was
 * hand-edited or written by an older version).
 *
 * <p>{@link #load(Path)} never throws: a missing file yields an empty list, and a file
 * that fails to parse is quarantined (renamed to a {@code .bad} sibling, replacing any
 * previous quarantine) before an empty list is returned.
 */
public final class PlaylistStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<Playlist>>() {
    }.getType();

    private PlaylistStore() {
    }

    public static List<Playlist> load(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            String json = Files.readString(file);
            List<Playlist> playlists = GSON.fromJson(json, LIST_TYPE);
            if (playlists == null) {
                return List.of();
            }
            return playlists.stream().filter(p -> !p.builtin()).toList();
        } catch (IOException | JsonSyntaxException | JsonIOException e) {
            quarantine(file);
            return List.of();
        }
    }

    public static void save(Path file, List<Playlist> playlists) {
        List<Playlist> customOnly = playlists.stream().filter(p -> !p.builtin()).toList();
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, GSON.toJson(customOnly, LIST_TYPE));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save playlists to " + file, e);
        }
    }

    private static void quarantine(Path file) {
        try {
            Path bad = file.resolveSibling(file.getFileName().toString() + ".bad");
            Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to quarantine corrupt playlists file " + file, e);
        }
    }
}
