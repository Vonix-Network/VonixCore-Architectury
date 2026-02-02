package network.vonix.vonixcore.forge;

import dev.architectury.platform.forge.EventBuses;
import network.vonix.vonixcore.VonixCore;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(VonixCore.MODID)
public class VonixCoreForge {
    public VonixCoreForge() {
        // Submit our event bus to let architectury register our content on the right
        // time
        EventBuses.registerModEventBus(VonixCore.MODID, FMLJavaModLoadingContext.get().getModEventBus());

        // Run our common setup
        VonixCore.init();

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);
    }

    // Chat handling is now done via Architectury ChatEvent.RECEIVED in common
    // EssentialsEventHandler
}