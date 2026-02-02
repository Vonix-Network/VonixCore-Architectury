package network.vonix.vonixcore.forge;

import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import network.vonix.vonixcore.VonixCore;

@Mod(VonixCore.MODID)
public final class VonixCoreForge {
    public VonixCoreForge() {
        // Submit our event bus to let Architectury API register our content on the right time.
        EventBuses.registerModEventBus(VonixCore.MODID, FMLJavaModLoadingContext.get().getModEventBus());

        // Run our common setup.
        VonixCore.init();
    }
}
