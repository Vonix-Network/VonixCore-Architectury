package network.vonix.vonixcore.forge.platform;

import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * Forge platform utilities.
 * Note: Platform class in common uses Architectury API static methods.
 * This class provides Forge-specific utilities if needed.
 */
public class ForgePlatform {

    public static Path getConfigDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static boolean isModLoaded(String modId) {
        return net.minecraftforge.fml.ModList.get().isLoaded(modId);
    }

    public static String getPlatformName() {
        return "Forge";
    }
}
