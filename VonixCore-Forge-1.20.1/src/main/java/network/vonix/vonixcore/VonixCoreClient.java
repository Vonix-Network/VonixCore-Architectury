package network.vonix.vonixcore;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client-side event handler for VonixCore.
 * This class uses EventBusSubscriber to automatically register for client-side
 * events.
 * It will not load on dedicated servers.
 */
@Mod.EventBusSubscriber(modid = VonixCore.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class VonixCoreClient {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // Client setup code
        VonixCore.LOGGER.info("HELLO FROM CLIENT SETUP");
        VonixCore.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }
}
