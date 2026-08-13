package studio.zojer.taswell.ui;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.ARGB;
import studio.zojer.taswell.director.MusicDirector;
import studio.zojer.taswell.track.Track;

/**
 * Bottom-left "now playing" overlay: {@code ♪ Title — Artist}, slide-in/hold/fade per {@link
 * HudEnvelope} (extracted there — pure and unit-tested; see its javadoc/tests for the timeline).
 * This class is the MC-touching half: it owns the mutable age counter, wires into {@link
 * MusicDirector#onTrackStarted}, and draws.
 *
 * <p><b>26.2 render contract</b> (verified against the mapped sources and Fabric API's own {@code
 * hud} package — {@code javap} against {@code fabric-rendering-v1}'s classes, no sources jar is
 * published for fabric-api submodules): registration is {@code
 * HudElementRegistry.addLast(Identifier, HudElement)}
 * ({@code net.fabricmc.fabric.api.client.rendering.v1.hud}), and the render contract is {@code
 * HudElement.extractRenderState(GuiGraphicsExtractor, DeltaTracker)} — matching {@code
 * PlayerScreen}'s (Task 9) independently-verified finding that there is no {@code
 * GuiGraphics}/{@code render(GuiGraphics, ...)} pair anywhere in 26.2, not even Fabric's own HUD
 * element abstraction. {@code GuiGraphicsExtractor.guiWidth()}/{@code guiHeight()} stand in for
 * the old screen-dimension arguments a pre-1.21.6 HUD render callback would have received
 * directly.
 *
 * <p>One instance, constructed once in {@code Taswell.onInitializeClient()} and wired three ways:
 * registered as a permanent {@code onTrackStarted} listener (no unregister API exists on {@link
 * MusicDirector} — see the task brief, register once, never per-screen), ticked from the existing
 * client tick handler (the age counter's only clock — {@link HudEnvelope} itself has no notion of
 * wall-clock time), and registered as a HUD element via {@code HudElementRegistry.addLast}. Not
 * constructed per-screen or per-frame; all three entry points ({@link #onTrackStarted},
 * {@link #tick}, {@link #extractRenderState}) run on the client thread, same as everywhere else
 * {@link MusicDirector} touches state, so no synchronization is needed here either.
 */
public final class NowPlayingHud implements HudElement {
    private static final int PADDING_X = 4;
    private static final int PADDING_Y = 3;
    /** {@code y = screenHeight − 40}, per the brief. */
    private static final int BOTTOM_MARGIN = 40;
    private static final float REST_X = 8f;
    private static final int TEXT_RGB = 0xFFFFFF;
    private static final int PILL_RGB = 0x000000;
    /** The pill's own translucency on top of the envelope's fade alpha — a flat backdrop, not opaque black. */
    private static final float PILL_BASE_ALPHA = 0.55f;
    /**
     * Clamp the fade tail rather than draw all the way to alpha 0. {@code
     * GuiGraphicsExtractor.text(...)} itself already skips drawing entirely when {@code
     * ARGB.alpha(color) == 0} (confirmed by reading its source) — this clamp additionally covers
     * the brief's broader "MC's font renderer ignores alpha below ~0.04 (4/255)" note for the
     * remaining near-zero-but-nonzero values, so the fade never risks a visible pop/flash right
     * before it would otherwise hit exactly zero.
     */
    private static final int MIN_VISIBLE_ALPHA_BYTE = 4;

    private final MusicDirector director;

    private Track displayTrack;
    private int ageTicks;

    public NowPlayingHud(MusicDirector director) {
        this.director = director;
    }

    /** Registered once via {@link MusicDirector#onTrackStarted} — see the class javadoc. */
    public void onTrackStarted(Track track) {
        if (!director.hudEnabled()) {
            return;
        }
        this.displayTrack = track;
        this.ageTicks = 0;
    }

    /**
     * Called every client tick (see {@code Taswell.onInitializeClient}'s tick handler). While
     * disabled, the counter simply doesn't advance — "don't accumulate visual state" per the
     * brief — so re-enabling mid-envelope resumes from where it was rather than having silently
     * expired in the background while hidden.
     */
    public void tick() {
        if (!director.hudEnabled() || displayTrack == null) {
            return;
        }
        ageTicks++;
        if (ageTicks >= HudEnvelope.VISIBLE_TICKS) {
            displayTrack = null;
            ageTicks = 0;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Track track = this.displayTrack;
        if (track == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        String text = "♪ " + track.title() + " — " + track.artist();
        int pillWidth = font.width(text) + PADDING_X * 2;
        int pillHeight = font.lineHeight + PADDING_Y * 2;
        float offScreenX = -pillWidth;

        HudEnvelope.State state = HudEnvelope.compute(ageTicks, director.hudEnabled(), offScreenX, REST_X);
        if (!state.visible()) {
            return;
        }
        int alphaByte = Math.round(state.alpha() * 255f);
        if (alphaByte < MIN_VISIBLE_ALPHA_BYTE) {
            return;
        }

        int x = Math.round(state.x());
        int y = graphics.guiHeight() - BOTTOM_MARGIN;

        int pillAlphaByte = Math.round(alphaByte * PILL_BASE_ALPHA);
        graphics.fill(x, y, x + pillWidth, y + pillHeight, ARGB.color(pillAlphaByte, PILL_RGB));
        graphics.text(font, text, x + PADDING_X, y + PADDING_Y, ARGB.color(alphaByte, TEXT_RGB));
    }
}
