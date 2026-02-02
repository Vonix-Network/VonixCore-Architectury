package network.vonix.vonixcore.listener;

import network.vonix.vonixcore.VonixCore;

/**
 * Protection event handler - coordinates registration of protection listeners.
 */
public class ProtectionEventHandler {

    public static void register() {
        if (!VonixCore.getInstance().isProtectionEnabled()) {
            return;
        }

        // Register extended protection listener
        ExtendedProtectionListener.register();
        
        VonixCore.LOGGER.info("[Protection] Protection event handlers registered");
    }
}
