package studio.zojer.taswell.audio;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Adapts a PCM-decoded {@link AudioInputStream} (see {@link Mp3Decoder}) to Minecraft's
 * {@link AudioStream}, so a local mp3/wav can be streamed through the vanilla sound engine.
 *
 * <p>MC-touching (only {@code net.minecraft.client.sounds.AudioStream}) — verified against the
 * 26.2 mapped sources (see class javadoc note below) rather than unit-tested; exercised via the
 * in-game smoke test instead.
 *
 * <p>{@code onFinished} fires exactly once: either at true end-of-stream, or — per the plan's
 * Task 7 note — from the read failure branch, since {@code Channel.pumpBuffers} (the vanilla
 * consumer of this interface, {@code com.mojang.blaze3d.audio.Channel}) merely logs a decode
 * {@link IOException} and keeps the channel open rather than tearing it down, so without this
 * callback the director would stall waiting for a "finished" signal that never comes.
 */
public final class Mp3AudioStream implements AudioStream {

    private final AudioInputStream pcm;
    private final Runnable onFinished;
    private boolean finished;

    public Mp3AudioStream(AudioInputStream pcm, Runnable onFinished) {
        this.pcm = pcm;
        this.onFinished = onFinished;
    }

    @Override
    public AudioFormat getFormat() {
        return pcm.getFormat();
    }

    /**
     * Fills a buffer of up to {@code expectedSize} bytes from the underlying PCM stream.
     * Never returns {@code null} — mirrors vanilla's {@code JOrbisAudioStream}/{@code
     * FloatSampleSource}, which can likewise return a short (possibly empty) buffer once the
     * stream runs dry. Must be a <em>direct</em> buffer: the vanilla consumer hands it straight
     * to {@code AL10.alBufferData}, which requires native-addressable memory.
     */
    @Override
    public ByteBuffer read(int expectedSize) throws IOException {
        byte[] scratch = new byte[expectedSize];
        int total = 0;
        try {
            while (total < expectedSize) {
                int n = pcm.read(scratch, total, expectedSize - total);
                if (n < 0) {
                    break; // EOF
                }
                total += n;
            }
        } catch (IOException e) {
            // Decode failure mid-stream: the engine swallows this exception (logs and keeps the
            // channel open), so fire onFinished ourselves before propagating, or the director
            // that's waiting on it would stall forever.
            fireFinishedOnce();
            throw e;
        }

        if (total < expectedSize) {
            // Short read means EOF was reached this call.
            fireFinishedOnce();
        }

        ByteBuffer out = ByteBuffer.allocateDirect(total);
        out.put(scratch, 0, total);
        out.flip();
        return out;
    }

    @Override
    public void close() throws IOException {
        pcm.close();
    }

    private void fireFinishedOnce() {
        if (!finished) {
            finished = true;
            onFinished.run();
        }
    }
}
