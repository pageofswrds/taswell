package studio.zojer.taswell.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link HudEnvelope} — pure phase math, no {@code net.minecraft.*}/{@code
 * net.fabricmc.*} involved, so it's testable outside a running game (unlike {@link
 * NowPlayingHud} itself, which is MC-touching and verified in-game instead — see the task
 * report).
 */
class HudEnvelopeTest {
    private static final float OFF_SCREEN_X = -100f;
    private static final float REST_X = 8f;

    /**
     * Pins the envelope's literal tick constants (spec-mandated slide-in/hold/fade durations,
     * not just an implementation detail the other tests happen to be parameterized over) so a
     * future edit that quietly changes {@code SLIDE_TICKS}/{@code HOLD_TICKS}/{@code FADE_TICKS}
     * fails a test rather than silently altering the HUD's timing.
     */
    @Test
    void tickConstantsMatchSpec() {
        assertEquals(5, HudEnvelope.SLIDE_TICKS);
        assertEquals(80, HudEnvelope.HOLD_TICKS);
        assertEquals(15, HudEnvelope.FADE_TICKS);
        assertEquals(100, HudEnvelope.VISIBLE_TICKS);
    }

    @Test
    void slideInStartsAtOffScreenX() {
        HudEnvelope.State state = HudEnvelope.compute(0, true, OFF_SCREEN_X, REST_X);

        assertTrue(state.visible());
        assertEquals(OFF_SCREEN_X, state.x(), 0.001f);
        assertEquals(1f, state.alpha(), 0.001f);
    }

    @Test
    void slideInMovesMonotonicallyTowardRestX() {
        float prev = OFF_SCREEN_X - 1f;
        for (int tick = 0; tick < HudEnvelope.SLIDE_TICKS; tick++) {
            HudEnvelope.State state = HudEnvelope.compute(tick, true, OFF_SCREEN_X, REST_X);
            assertTrue(state.x() >= prev, "x should not move backward during slide-in (tick " + tick + ")");
            prev = state.x();
        }
    }

    @Test
    void slideInReachesRestXExactlyAtSlideTicks() {
        HudEnvelope.State state = HudEnvelope.compute(HudEnvelope.SLIDE_TICKS, true, OFF_SCREEN_X, REST_X);

        assertTrue(state.visible());
        assertEquals(REST_X, state.x(), 0.001f);
        assertEquals(1f, state.alpha(), 0.001f);
    }

    @Test
    void holdKeepsFullAlphaAndRestX() {
        int midHold = HudEnvelope.SLIDE_TICKS + HudEnvelope.HOLD_TICKS / 2;

        HudEnvelope.State state = HudEnvelope.compute(midHold, true, OFF_SCREEN_X, REST_X);

        assertTrue(state.visible());
        assertEquals(REST_X, state.x(), 0.001f);
        assertEquals(1f, state.alpha(), 0.001f);
    }

    @Test
    void fadeInterpolatesAlphaDownTowardZeroWithoutReachingIt() {
        int fadeStart = HudEnvelope.SLIDE_TICKS + HudEnvelope.HOLD_TICKS;

        HudEnvelope.State atFadeStart = HudEnvelope.compute(fadeStart, true, OFF_SCREEN_X, REST_X);
        HudEnvelope.State midFade =
                HudEnvelope.compute(fadeStart + HudEnvelope.FADE_TICKS / 2, true, OFF_SCREEN_X, REST_X);
        HudEnvelope.State lastFadeTick =
                HudEnvelope.compute(fadeStart + HudEnvelope.FADE_TICKS - 1, true, OFF_SCREEN_X, REST_X);

        assertTrue(atFadeStart.visible());
        assertEquals(1f, atFadeStart.alpha(), 0.001f);
        assertEquals(REST_X, atFadeStart.x(), 0.001f);

        assertTrue(midFade.visible());
        assertTrue(midFade.alpha() < atFadeStart.alpha());
        assertTrue(midFade.alpha() > 0f);
        assertEquals(REST_X, midFade.x(), 0.001f);

        assertTrue(lastFadeTick.visible());
        assertTrue(lastFadeTick.alpha() < midFade.alpha());
        assertTrue(lastFadeTick.alpha() > 0f);
    }

    @Test
    void expiredAfterVisibleTicksIsNotVisible() {
        assertFalse(HudEnvelope.compute(HudEnvelope.VISIBLE_TICKS, true, OFF_SCREEN_X, REST_X).visible());
    }

    @Test
    void wellPastExpiryStaysNotVisible() {
        assertFalse(HudEnvelope.compute(HudEnvelope.VISIBLE_TICKS + 1000, true, OFF_SCREEN_X, REST_X).visible());
    }

    @Test
    void hudDisabledIsNeverVisibleRegardlessOfAge() {
        assertFalse(HudEnvelope.compute(0, false, OFF_SCREEN_X, REST_X).visible());
        assertFalse(HudEnvelope.compute(HudEnvelope.SLIDE_TICKS, false, OFF_SCREEN_X, REST_X).visible());
        assertFalse(HudEnvelope
                .compute(HudEnvelope.SLIDE_TICKS + HudEnvelope.HOLD_TICKS, false, OFF_SCREEN_X, REST_X)
                .visible());
    }
}
