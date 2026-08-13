package studio.zojer.taswell.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Decodes a local audio file (mp3 via the bundled mp3spi SPI, or wav natively) into
 * signed 16-bit PCM at the source sample rate and channel count.
 *
 * <p>Pure JVM — no {@code net.minecraft.*}/{@code net.fabricmc.*} — so it can be unit
 * tested directly against the fixture files.
 */
public final class Mp3Decoder {

    private Mp3Decoder() {
    }

    /**
     * Opens {@code file} and returns a PCM-decoded {@link AudioInputStream}.
     *
     * @throws UnsupportedAudioFileException if the file's format is not recognized or not
     *                                        convertible to PCM (e.g. random/corrupt bytes)
     * @throws IOException                   on I/O failure reading the file
     */
    public static AudioInputStream open(Path file) throws UnsupportedAudioFileException, IOException {
        AudioInputStream raw = AudioSystem.getAudioInputStream(file.toFile()); // mp3spi SPI handles mp3
        AudioFormat src = raw.getFormat();
        AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                src.getSampleRate(), 16, src.getChannels(),
                src.getChannels() * 2, src.getSampleRate(), false);
        return AudioSystem.getAudioInputStream(pcm, raw);
    }
}
