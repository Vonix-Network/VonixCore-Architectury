package network.vonix.vonixcore.listener;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.chat.ChatFormatter;
import network.vonix.vonixcore.discord.DiscordManager;

/**
 * Handles essentials-related events like chat and advancements.
 */
public class EssentialsEventHandler {

    /**
     * Register all essentials event listeners.
     */
    public static void register() {
        // Handle chat formatting and broadcasting manually
        // ALLOW_CHAT_MESSAGE lets us cancel the vanilla broadcast (return false)
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (!network.vonix.vonixcore.config.EssentialsConfig.getInstance().isEnabled()
                    || !network.vonix.vonixcore.config.EssentialsConfig.getInstance().isChatFormattingEnabled()) {
                return true; // Let vanilla handle it
            }

            String content = message.signedContent();

            // Format the message
            Component formatted = network.vonix.vonixcore.chat.ChatFormatter.formatChatMessage(sender, content);

            // Broadcast to all players manually
            sender.getServer().getPlayerList().getPlayers().forEach(p -> {
                p.sendSystemMessage(formatted);
            });

            // Log to console
            sender.getServer().sendSystemMessage(formatted);

            // Manually trigger Discord logging since we are cancelling the event
            if (VonixCore.getInstance().isDiscordEnabled()) {
                VonixCore.executeAsync(() -> {
                    try {
                        // Send raw username - sendMinecraftMessage will apply prefix via webhookUsernameFormat
                        DiscordManager.getInstance().sendMinecraftMessage(sender.getName().getString(), content);
                    } catch (Exception e) {
                        VonixCore.LOGGER.error("[Discord] Failed to send chat message: {}", e.getMessage());
                    }
                });
            }

            return false; // Cancel vanilla broadcast to prevent duplicate username
        });

        // Command message event - can be used for logging
        ServerMessageEvents.COMMAND_MESSAGE.register((message, source, params) -> {
            // Can log commands here if needed
        });

        // Save back location on death
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH
                .register((entity, damageSource) -> {
                    if (entity instanceof ServerPlayer player) {
                        network.vonix.vonixcore.teleport.TeleportManager.getInstance().saveLastLocation(player, true);
                    }
                });
    }
}
