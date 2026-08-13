package studio.zojer.taswell.rotation;

import java.util.Random;

/**
 * Pure phase math for the between-track gap: turns {@code TaswellConfig}'s
 * {@code minGapSeconds}/{@code maxGapSeconds} into a tick count. Pulled out of
 * {@link studio.zojer.taswell.director.MusicDirector} (which is MC-touching and not unit
 * tested) so this one piece of arithmetic is testable outside a running game.
 *
 * <p>{@code TaswellConfig} is a plain mutable bean loaded straight from JSON — a hand-edited
 * config (or one from a future settings UI with no input validation) can carry a negative
 * {@code minGapSeconds}/{@code maxGapSeconds}. Negative values are clamped to zero here rather
 * than fed to {@link Random#nextInt(int)}, which throws {@code IllegalArgumentException} for a
 * non-positive bound — an unclamped negative gap would crash rotation on every advance.
 */
public final class GapTiming {
    private GapTiming() {
    }

    /**
     * @param minGapSeconds configured minimum gap, seconds; negative clamps to 0
     * @param maxGapSeconds configured maximum gap, seconds; negative clamps to 0
     * @param ticksPerSecond the game's tick rate (20)
     * @param random         source of randomness for the draw between min and max
     * @return a tick count in {@code [min, max]} seconds, converted to ticks; {@code min}/{@code
     * max} are clamped to be non-negative and swapped if {@code min > max} after clamping
     */
    public static int computeTicks(int minGapSeconds, int maxGapSeconds, int ticksPerSecond, Random random) {
        int clampedMin = Math.max(0, minGapSeconds);
        int clampedMax = Math.max(0, maxGapSeconds);
        int min = Math.min(clampedMin, clampedMax);
        int max = Math.max(clampedMin, clampedMax);
        int seconds = min == max ? min : min + random.nextInt(max - min + 1);
        return seconds * ticksPerSecond;
    }
}
