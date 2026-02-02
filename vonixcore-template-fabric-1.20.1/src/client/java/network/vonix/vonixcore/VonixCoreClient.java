package network.vonix.vonixcore;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side mod initializer for VonixCore.
 * Currently VonixCore is primarily server-side, but this
 * allows for future client-side features like custom GUIs.
 */
@Environment(EnvType.CLIENT)
public class VonixCoreClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("VonixCore-Client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[VonixCore] Client-side initialization complete");
        // This entrypoint is suitable for setting up client-specific logic, such as
        // rendering.
        // VonixCore is primarily server-side, but this can be used for:
        // - Custom GUI screens
        // - Client-side caching
        // - Packet handlers
    }
}
