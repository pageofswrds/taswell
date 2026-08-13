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
        return convertToPcm(raw);
    }

    /**
     * Converts an already-opened {@link AudioInputStream} to signed 16-bit PCM at its own
     * sample rate/channel count. Package-private (not {@code private}) so
     * {@code Mp3DecoderTest} can drive the failure branch directly with a raw stream whose
     * format is guaranteed unconvertible, without depending on a real file that happens to
     * trigger it.
     *
     * <p>{@code raw} is closed before this method returns abnormally: {@link
     * AudioSystem#getAudioInputStream(AudioFormat, AudioInputStream)} throws (unchecked)
     * {@link IllegalArgumentException} when no {@code FormatConversionProvider} supports the
     * requested conversion, and without this catch, {@code raw} — and the file handle
     * {@link #open} opened it from — would leak. On success, {@code raw} is left open: it's
     * cascade-closed by the returned stream's {@code close()}, since PCM decoding reads through
     * it.
     */
    static AudioInputStream convertToPcm(AudioInputStream raw) {
        try {
            AudioFormat src = raw.getFormat();
            AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    src.getSampleRate(), 16, src.getChannels(),
                    src.getChannels() * 2, src.getSampleRate(), false);
            return AudioSystem.getAudioInputStream(pcm, raw);
        } catch (IllegalArgumentException e) {
            try {
                raw.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }
}
