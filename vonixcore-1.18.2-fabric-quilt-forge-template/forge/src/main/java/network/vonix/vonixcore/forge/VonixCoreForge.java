package network.vonix.vonixcore.forge;

import dev.architectury.platform.forge.EventBuses;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.listener.EssentialsEventHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraft.server.level.ServerPlayer;

@Mod(VonixCore.MODID)
public class VonixCoreForge {
    public VonixCoreForge() {
        // Submit our event bus to let architectury register our content on the right time
        EventBuses.registerModEventBus(VonixCore.MODID, FMLJavaModLoadingContext.get().getModEventBus());
        
        // Run our common setup
        VonixCore.init();
        
        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        String message = event.getMessage();
        
        // Process the chat message
        boolean shouldCancel = EssentialsEventHandler.onChat(player, message);
        
        if (shouldCancel) {
            event.setCanceled(true);
        }
    }
}