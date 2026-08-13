package studio.zojer.taswell.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads and saves {@link TaswellConfig} as JSON. Pure JVM — takes an explicit
 * {@link Path} rather than resolving one itself, so tests never touch
 * {@code net.fabricmc.*} (see {@code TaswellPaths} for the real on-disk location).
 * ({@code org.slf4j} is not part of that restriction — it's a logging facade, not
 * Minecraft/Fabric API — and is already transitively on the classpath.)
 *
 * <p>{@link #load(Path)} never throws: a missing file yields defaults, and a file that
 * fails to parse is quarantined (renamed to a {@code .bad} sibling, replacing any
 * previous quarantine) before defaults are returned. Quarantine itself is best-effort:
 * if the move fails too (permission denied, disk full, an unreplaceable {@code .bad}
 * entry), that failure is logged and swallowed — {@code load} still returns defaults
 * rather than propagate. This guarantees the mod can never boot-loop on a corrupt
 * config, even when the filesystem won't cooperate with quarantining it.
 */
public final class ConfigStore {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigStore.class);
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

    /**
     * Best-effort: on failure, logs and returns rather than throwing, so a quarantine
     * that can't complete (e.g. the filesystem won't cooperate) never stops {@link
     * #load(Path)} from still returning defaults.
     */
    private static void quarantine(Path file) {
        try {
            Path bad = file.resolveSibling(file.getFileName().toString() + ".bad");
            Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warn("failed to quarantine corrupt config {} — leaving it in place", file, e);
        }
    }
}
