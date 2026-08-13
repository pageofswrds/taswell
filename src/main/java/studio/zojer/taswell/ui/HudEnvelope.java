package studio.zojer.taswell.ui;

/**
 * Pure phase math for the now-playing HUD's slide-in / hold / fade envelope, given how many
 * ticks have elapsed since the displayed track started. No {@code net.minecraft.*}/{@code
 * net.fabricmc.*} imports — kept unit-testable outside a running game (see {@code
 * HudEnvelopeTest}); {@link NowPlayingHud} is the MC-touching half that owns the age counter and
 * draws, verified in-game instead per the task report.
 *
 * <p>Timeline, in ticks since the track started: {@code [0, SLIDE_TICKS)} slides from {@code
 * offScreenX} to {@code restX} (eased); {@code [SLIDE_TICKS, SLIDE_TICKS + HOLD_TICKS)} holds at
 * {@code restX}, full alpha; {@code [SLIDE_TICKS + HOLD_TICKS, VISIBLE_TICKS)} fades alpha from 1
 * toward (but never quite reaching) 0 while holding at {@code restX}; {@code >= VISIBLE_TICKS} is
 * expired — nothing rendered. {@code hudEnabled} is folded in as a single always-hidden branch
 * rather than left as a caller-side gate the pure math doesn't know about, so "disabled ⇒ not
 * visible" is directly testable here too.
 */
public final class HudEnvelope {
    public static final int SLIDE_TICKS = 5;
    public static final int HOLD_TICKS = 80;
    public static final int FADE_TICKS = 15;
    /** Total ticks the HUD is visible in any form; {@code ageTicks >= VISIBLE_TICKS} is expired. */
    public static final int VISIBLE_TICKS = SLIDE_TICKS + HOLD_TICKS + FADE_TICKS;

    private HudEnvelope() {
    }

    /**
     * @param ageTicks   ticks elapsed since the displayed track started (0 = just started)
     * @param hudEnabled live {@code TaswellConfig.hudEnabled} flag
     * @param offScreenX x position at the very start of the slide-in (fully off the left edge —
     *                   the caller sizes this to the pill's own width)
     * @param restX      x position once slid into view, and held/faded there
     */
    public static State compute(int ageTicks, boolean hudEnabled, float offScreenX, float restX) {
        if (!hudEnabled || ageTicks < 0 || ageTicks >= VISIBLE_TICKS) {
            return State.hidden();
        }
        if (ageTicks < SLIDE_TICKS) {
            float t = easeOutCubic(ageTicks / (float) SLIDE_TICKS);
            float x = offScreenX + (restX - offScreenX) * t;
            return new State(true, x, 1f);
        }
        if (ageTicks < SLIDE_TICKS + HOLD_TICKS) {
            return new State(true, restX, 1f);
        }
        int fadeAge = ageTicks - SLIDE_TICKS - HOLD_TICKS;
        float alpha = 1f - (fadeAge / (float) FADE_TICKS);
        return new State(true, restX, alpha);
    }

    private static float easeOutCubic(float t) {
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }

    /** @param alpha 0 (invisible) .. 1 (opaque); meaningless when {@code visible} is false. */
    public record State(boolean visible, float x, float alpha) {
        static State hidden() {
            return new State(false, 0f, 0f);
        }
    }
}
