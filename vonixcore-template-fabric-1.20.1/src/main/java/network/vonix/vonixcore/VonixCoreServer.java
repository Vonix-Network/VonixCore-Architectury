package network.vonix.vonixcore;

import net.fabricmc.api.DedicatedServerModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dedicated server mod initializer for VonixCore.
 * Used for server-only initialization if needed.
 */
public class VonixCoreServer implements DedicatedServerModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("VonixCore-Server");

    @Override
    public void onInitializeServer() {
        LOGGER.info("[VonixCore] Dedicated server initialization");
        // Additional server-only initialization can go here
        // Most initialization happens in the main VonixCore.onInitialize() and server
        // events
    }
}
