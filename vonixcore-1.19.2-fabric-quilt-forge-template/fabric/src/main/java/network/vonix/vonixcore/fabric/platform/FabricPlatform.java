package network.vonix.vonixcore.fabric.platform;

import java.nio.file.Path;

/**
 * Platform abstraction for Fabric.
 */
public class FabricPlatform {

    /**
     * Get the config directory for the platform.
     * 
     * @return Path to the config directory
     */
    public static Path getConfigDirectory() {
        return dev.architectury.platform.Platform.getConfigFolder();
    }

    /**
     * Check if a mod is loaded.
     * 
     * @param modId The mod ID
     * @return True if loaded
     */
    public static boolean isModLoaded(String modId) {
        return dev.architectury.platform.Platform.isModLoaded(modId);
    }

    public static Path getGameDirectory() {
        return dev.architectury.platform.Platform.getGameFolder();
    }
}
