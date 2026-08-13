package studio.zojer.taswell.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

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
}
