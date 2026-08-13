package studio.zojer.taswell.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.zojer.taswell.director.MusicDirector;

/**
 * Suppresses all vanilla ambient music scheduling — title screen, in-world situational music,
 * and creative — so nothing plays until taswell's own director (Task 7) decides otherwise.
 *
 * <p>26.2 mapped {@code net.minecraft.client.sounds.MusicManager} has no {@code MusicInfo}/
 * frequency rework (that landed in a different lineage); it is the pre-rework shape: a single
 * no-arg {@link MusicManager#tick()} — called unconditionally from {@code Minecraft#tick()}
 * every tick, paused or not, and also directly from {@code Screen#tick()} overrides such as
 * {@code WinScreen} — that counts down a delay and, once expired, calls the single overload
 * {@link MusicManager#startPlaying}. Both are cancelled at {@code HEAD}: {@code tick()} so the
 * countdown/dispatch never runs, and {@code startPlaying} directly as a second line of defense
 * since it is public and screens can call it outside {@code tick()}.
 *
 * <p>Exception per spec: end-credits music stays vanilla. {@code WinScreen} routes through
 * these same two methods — its {@code tick()} override calls {@code MusicManager#tick()}
 * directly, and {@code Minecraft#getSituationalMusic()} resolves to {@code Musics.CREDITS} via
 * {@code WinScreen#getBackgroundMusic()} — so both injections carve out an escape hatch when
 * the current screen is {@code WinScreen}.
 */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void taswell$suppressTick(CallbackInfo ci) {
        if (MusicDirector.vanillaSuppressed() && !isCredits()) {
            ci.cancel();
        }
    }

    @Inject(method = "startPlaying", at = @At("HEAD"), cancellable = true)
    private void taswell$suppressStart(CallbackInfo ci) {
        if (MusicDirector.vanillaSuppressed() && !isCredits()) {
            ci.cancel();
        }
    }

    private static boolean isCredits() {
        return Minecraft.getInstance().gui.screen() instanceof WinScreen;
    }
}
