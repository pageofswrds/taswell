package studio.zojer.taswell;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.zojer.taswell.director.MusicDirector;
import studio.zojer.taswell.library.VanillaTracks;

public class Taswell implements ClientModInitializer {
    public static final String MOD_ID = "taswell";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        VanillaTracks.registerSoundEvents();
        TaswellPaths.ensureDirs();

        MusicDirector director = MusicDirector.get();
        director.scanMusicFolderAsync();
        ClientTickEvents.END_CLIENT_TICK.register(client -> director.tick());

        LOG.info("taswell loaded");
    }
}
