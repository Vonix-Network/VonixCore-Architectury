package network.vonix.vonixcore.listener;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.economy.EconomyManager;
import network.vonix.vonixcore.jobs.JobsManager;

/**
 * Handles player connection events (non-Discord related).
 * Discord join/leave events are handled by DiscordEventHandler.
 */
public class PlayerEventListener {

    /**
     * Register all player event listeners.
     */
    public static void register() {
        // Player join event
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            VonixCore.LOGGER.debug("[Essentials] Player joined: {}", player.getName().getString());
            
            // Load Economy Data
            if (EconomyManager.getInstance() != null) {
                EconomyManager.getInstance().loadPlayerBalance(player.getUUID());
            }
            
            // Load Jobs Data
            if (JobsManager.getInstance() != null) {
                JobsManager.getInstance().loadPlayerJobs(player.getUUID());
            }
        });

        // Player leave event
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.player;
            VonixCore.LOGGER.debug("[Essentials] Player left: {}", player.getName().getString());
            
            // Unload/Save Jobs Data
            if (JobsManager.getInstance() != null) {
                JobsManager.getInstance().unloadPlayerJobs(player.getUUID());
            }
            
            // Economy saves on change, but we can clear cache if needed, 
            // though keeping it for a bit might be fine. 
            // For now, EconomyManager manages its own cache, let's leave it.
        });
    }
}
