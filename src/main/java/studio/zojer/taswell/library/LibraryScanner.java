package studio.zojer.taswell.library;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import studio.zojer.taswell.track.Track;
import studio.zojer.taswell.track.TrackSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Scans a folder (one level deep — subfolders are out of scope for v1) for local audio
 * files and turns each into a {@link TrackSource#LOCAL} {@link Track}. Pure JVM: no
 * {@code net.minecraft.*}/{@code net.fabricmc.*} imports, so it runs under plain JUnit.
 *
 * <p>ID3/metadata is read with jaudiotagger. A tag-read failure — including files that
 * merely have no tag frame, or aren't valid audio at all (see {@code corrupt.mp3} in the
 * test fixtures) — is not fatal: the track is still included, with its title falling
 * back to the filename stem and an empty artist. Playability is playback's problem, not
 * the scanner's.
 */
public final class LibraryScanner {
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("mp3", "wav");

    private LibraryScanner() {
    }

    /**
     * Lists {@code folder} (depth 1) for {@code .mp3}/{@code .wav} files and returns one
     * {@link Track} per file, sorted by title. A missing folder yields an empty list
     * rather than throwing.
     */
    public static List<Track> scan(Path folder) {
        List<Track> tracks = new ArrayList<>();
        try (Stream<Path> entries = Files.list(folder)) {
            entries
                    .filter(Files::isRegularFile)
                    .filter(LibraryScanner::hasSupportedExtension)
                    .forEach(file -> tracks.add(readTrack(file)));
        } catch (IOException e) {
            return List.of();
        }
        tracks.sort(Comparator.comparing(Track::title, String.CASE_INSENSITIVE_ORDER));
        return tracks;
    }

    private static boolean hasSupportedExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    private static Track readTrack(Path file) {
        String fileName = file.getFileName().toString();
        String stem = stem(fileName);
        String id = "local:" + fileName;

        String title = stem;
        String artist = "";
        try {
            AudioFile audioFile = AudioFileIO.read(file.toFile());
            Tag tag = audioFile.getTag();
            if (tag != null) {
                String tagTitle = tag.getFirst(FieldKey.TITLE);
                if (tagTitle != null && !tagTitle.isBlank()) {
                    title = tagTitle;
                }
                String tagArtist = tag.getFirst(FieldKey.ARTIST);
                if (tagArtist != null) {
                    artist = tagArtist;
                }
            }
        } catch (Exception e) {
            // Fall back to filename-derived title/empty artist — corrupt or unreadable
            // files are still included as tracks; playability is playback's problem.
        }

        return new Track(id, title, artist, TrackSource.LOCAL, null, file);
    }

    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
