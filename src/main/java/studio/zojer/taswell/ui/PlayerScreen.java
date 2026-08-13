package studio.zojer.taswell.ui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The player screen opened by the {@code key.taswell.open} keybind (Task 8). Deliberately empty
 * for now — {@code Screen}'s defaults are all we need to compile and open/close cleanly: no
 * {@code init()} override (no widgets yet), no {@code render()} override (vanilla background
 * only), and {@code shouldCloseOnEsc()} defaults to {@code true} so Esc closes it like any other
 * menu. Task 9 fills in the actual playlist/track UI.
 */
public class PlayerScreen extends Screen {
    public PlayerScreen() {
        super(Component.translatable("screen.taswell.title"));
    }
}
