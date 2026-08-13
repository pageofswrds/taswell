package studio.zojer.taswell;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.zojer.taswell.director.MusicDirector;
import studio.zojer.taswell.library.VanillaTracks;
import studio.zojer.taswell.ui.PlayerScreen;

public class Taswell implements ClientModInitializer {
    public static final String MOD_ID = "taswell";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /**
     * 26.2 API drift from the task brief: as of 1.21.9, {@code KeyMapping}'s category is no
     * longer a raw translation-key string — it is this registered {@code KeyMapping.Category}
     * record, keyed by {@link Identifier} and looked up via {@link KeyMapping.Category#register}
     * (verified against the mapped {@code KeyMapping.java} source; vanilla's own categories,
     * e.g. {@code KeyMapping.Category.MOVEMENT}, are constructed the same way). Its label is
     * resolved via {@code Component.translatable(id.toLanguageKey("key.category"))}, i.e.
     * {@code "key.category." + namespace + "." + path} — so for {@code taswell:taswell} that is
     * {@code key.category.taswell.taswell}, not the brief's anticipated {@code
     * key.categories.taswell} (that flat string form is what pre-1.21.9 {@code KeyMapping}
     * constructors took directly). {@code en_us.json} carries the actual key.
     */
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, MOD_ID));

    @Override
    public void onInitializeClient() {
        VanillaTracks.registerSoundEvents();
        TaswellPaths.ensureDirs();

        // Fabric API drift alongside the above: the pre-1.21.9 `KeyBindingHelper` (module
        // `fabric-key-binding-api-v1`) is `KeyMappingHelper` in 26.2 (module
        // `fabric-key-mapping-api-v1`), with a `registerKeyMapping` method in place of
        // `registerKeyBinding` — confirmed via `javap` against the resolved jar (no sources jar
        // is published for this module).
        KeyMapping openKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.taswell.open", GLFW.GLFW_KEY_M, CATEGORY));
        KeyMapping nextKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.taswell.next", GLFW.GLFW_KEY_PERIOD, CATEGORY));
        KeyMapping previousKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.taswell.previous", GLFW.GLFW_KEY_COMMA, CATEGORY));
        KeyMapping playPauseKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.taswell.play_pause", GLFW.GLFW_KEY_MINUS, CATEGORY));

        MusicDirector director = MusicDirector.get();
        director.scanMusicFolderAsync();
        // One tick handler drives both the director's own clock and the transport keybinds —
        // deliberately not a second competing ClientTickEvents registration (see task brief).
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            director.tick();
            while (openKey.consumeClick()) {
                client.gui.setScreen(new PlayerScreen());
            }
            while (nextKey.consumeClick()) {
                director.next();
            }
            while (previousKey.consumeClick()) {
                director.previous();
            }
            while (playPauseKey.consumeClick()) {
                director.togglePause();
            }
        });

        LOG.info("taswell loaded");
    }
}
