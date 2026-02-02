package network.vonix.vonixcore.platform;

import java.nio.file.Path;

/**
 * Platform abstraction interface for VonixCore.
 */
public class Platform {

    /**
     * Get the config directory for the platform.
     * 
     * @return Path to the config directory
     */
    public static Path getConfigDirectory() {
        return dev.architectury.platform.Platform.getConfigFolder();
    }

    public static Path getGameDirectory() {
        return dev.architectury.platform.Platform.getGameFolder();
    }

    public static boolean isFabric() {
        return dev.architectury.platform.Platform.isFabric();
    }

    public static boolean isForge() {
        return dev.architectury.platform.Platform.isForge();
    }
}
