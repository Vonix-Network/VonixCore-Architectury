package network.vonix.vonixcore.platform;

import java.nio.file.Path;

/**
 * Platform abstraction interface for VonixCore.
 */
public class Platform {
    
    /**
     * Get the config directory for the platform.
     * @return Path to the config directory
     */
    public static Path getConfigDirectory() {
        return dev.architectury.platform.Platform.getConfigFolder();
    }

    /**
     * Check if a mod is loaded.
     * @param modId The mod ID
     * @return True if loaded
     */
    public static boolean isModLoaded(String modId) {
        return dev.architectury.platform.Platform.isModLoaded(modId);
    }
    
    /**
     * Get the game working directory.
     * @return Path to the game directory
     */
    public static Path getGameDirectory() {
        return dev.architectury.platform.Platform.getGameFolder();
    }
}
