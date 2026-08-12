package studio.zojer.taswell;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Taswell implements ClientModInitializer {
    public static final String MOD_ID = "taswell";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOG.info("taswell loaded");
    }
}
