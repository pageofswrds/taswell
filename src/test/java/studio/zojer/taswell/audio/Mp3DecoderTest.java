package studio.zojer.taswell.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link Mp3Decoder} against real fixture files under
 * {@code src/test/resources/fixtures/}. Pure JVM — no Minecraft classes involved.
 */
class Mp3DecoderTest {

    private static Path copyFixture(String name, Path into) {
        String resource = "/fixtures/" + name;
        try (InputStream in = Mp3DecoderTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing test fixture " + resource);
            }
            Path dest = into.resolve(name);
            Files.copy(in, dest);
            return dest;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void taggedMp3DecodesToSigned16BitPcmStereo44100(@TempDir Path dir) throws Exception {
        Path file = copyFixture("one.mp3", dir);

        try (AudioInputStream pcm = Mp3Decoder.open(file)) {
            AudioFormat format = pcm.getFormat();
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
            assertEquals(16, format.getSampleSizeInBits());
            assertEquals(44100f, format.getSampleRate());
            assertEquals(2, format.getChannels());

            byte[] buf = new byte[4096];
            long total = 0;
            int n;
            while ((n = pcm.read(buf)) != -1) {
                total += n;
            }
            assertTrue(total > 0, "expected decoded audio data, got " + total + " bytes");
        }
    }

    @Test
    void untaggedMonoMp3Decodes(@TempDir Path dir) throws Exception {
        Path file = copyFixture("untagged.mp3", dir);

        try (AudioInputStream pcm = Mp3Decoder.open(file)) {
            AudioFormat format = pcm.getFormat();
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
            assertEquals(16, format.getSampleSizeInBits());
            assertEquals(44100f, format.getSampleRate());
            assertEquals(1, format.getChannels());
        }
    }

    @Test
    void tinyWavDecodesAt22050Mono(@TempDir Path dir) throws Exception {
        Path file = copyFixture("tiny.wav", dir);

        try (AudioInputStream pcm = Mp3Decoder.open(file)) {
            AudioFormat format = pcm.getFormat();
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
            assertEquals(16, format.getSampleSizeInBits());
            assertEquals(22050f, format.getSampleRate());
            assertEquals(1, format.getChannels());

            byte[] buf = new byte[4096];
            long total = 0;
            int n;
            while ((n = pcm.read(buf)) != -1) {
                total += n;
            }
            assertTrue(total > 0, "expected decoded audio data, got " + total + " bytes");
        }
    }

    @Test
    void corruptMp3ThrowsWithoutHanging(@TempDir Path dir) {
        Path file = copyFixture("corrupt.mp3", dir);

        // The SPI must fail fast (either on open, or on first read) rather than block
        // indefinitely trying to sync to a valid mp3 frame in random bytes.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThrows(Exception.class, () -> {
                    try (AudioInputStream pcm = Mp3Decoder.open(file)) {
                        byte[] buf = new byte[4096];
                        while (pcm.read(buf) != -1) {
                            // drain; a well-formed corrupt fixture fails during open() or here
                        }
                    }
                }));
    }

    /**
     * Covers the resource-leak fix directly: when the second {@code AudioSystem
     * .getAudioInputStream(AudioFormat, AudioInputStream)} call rejects the conversion (thrown
     * as {@link IllegalArgumentException}, no registered {@code FormatConversionProvider}
     * supports it), the raw stream passed in — and its underlying handle — must still be
     * closed rather than leaked.
     *
     * <p>No real mp3/wav fixture reliably triggers this branch: {@link Mp3Decoder#open} always
     * builds its target PCM format from the source's own sample rate and channel count, which
     * every source mp3spi/the JDK can actually decode is, by construction, convertible to. So
     * this drives {@link Mp3Decoder#convertToPcm} directly with a raw stream carrying a made-up
     * {@link AudioFormat.Encoding} that no conversion provider on the classpath — mp3spi,
     * tritonus-share, or the JDK's own — can possibly claim to support, which is a
     * deterministic, cross-platform way to force the failure branch (pure SPI-registry lookup,
     * no OS audio backend involved).
     */
    @Test
    void conversionFailureClosesTheRawStream() {
        AudioFormat.Encoding unsupportedEncoding = new AudioFormat.Encoding("Mp3DecoderTest-unsupported");
        AudioFormat unsupportedSource = new AudioFormat(unsupportedEncoding, 44100f, 16, 1, 2, 44100f, false);
        byte[] data = new byte[32];
        AtomicBoolean underlyingClosed = new AtomicBoolean(false);
        InputStream underlying = new ByteArrayInputStream(data) {
            @Override
            public void close() throws IOException {
                underlyingClosed.set(true);
                super.close();
            }
        };
        AudioInputStream raw = new AudioInputStream(underlying, unsupportedSource, data.length / unsupportedSource.getFrameSize());

        assertThrows(IllegalArgumentException.class, () -> Mp3Decoder.convertToPcm(raw));
        assertTrue(underlyingClosed.get(), "raw stream's underlying handle should be closed when PCM conversion fails");
    }
}
