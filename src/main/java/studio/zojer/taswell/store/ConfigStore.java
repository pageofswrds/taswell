package studio.zojer.taswell.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads and saves {@link TaswellConfig} as JSON. Pure JVM — takes an explicit
 * {@link Path} rather than resolving one itself, so tests never touch
 * {@code net.fabricmc.*} (see {@code TaswellPaths} for the real on-disk location).
 *
 * <p>{@link #load(Path)} never throws: a missing file yields defaults, and a file that
 * fails to parse is quarantined (renamed to a {@code .bad} sibling, replacing any
 * previous quarantine) before defaults are returned. This guarantees the mod can never
 * boot-loop on a corrupt config.
 */
public final class ConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigStore() {
    }

    public static TaswellConfig load(Path file) {
        if (!Files.exists(file)) {
            return new TaswellConfig();
        }
        try {
            String json = Files.readString(file);
            TaswellConfig cfg = GSON.fromJson(json, TaswellConfig.class);
            return cfg != null ? cfg : new TaswellConfig();
        } catch (IOException | JsonSyntaxException | JsonIOException e) {
            quarantine(file);
            return new TaswellConfig();
        }
    }

    public static void save(Path file, TaswellConfig config) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, GSON.toJson(config));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save config to " + file, e);
        }
    }

    private static void quarantine(Path file) {
        try {
            Path bad = file.resolveSibling(file.getFileName().toString() + ".bad");
            Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to quarantine corrupt config " + file, e);
        }
    }
}
