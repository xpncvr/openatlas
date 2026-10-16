package xpncvr.openatlas;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenAtlas implements ModInitializer {
    public static final String MOD_ID = "openatlas";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("OpenAtlas initialised");
    }
}
