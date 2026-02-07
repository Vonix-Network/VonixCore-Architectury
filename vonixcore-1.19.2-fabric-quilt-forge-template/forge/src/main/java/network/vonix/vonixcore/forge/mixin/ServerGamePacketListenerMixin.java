package network.vonix.vonixcore.forge.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.EssentialsConfig;
import network.vonix.vonixcore.config.DiscordConfig;
import network.vonix.vonixcore.discord.DiscordManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge mixin to intercept chat messages and apply custom formatting while
 * cancelling vanilla broadcast. This prevents duplicate messages that occur when using
 * Architectury's ChatEvent which fires AFTER vanilla processing.
 * 
 * Note: For 1.19.2, this targets the broadcastChatMessage method with PlayerChatMessage
 * which is the signed chat message format introduced in 1.19.1.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {

    @Shadow
    public ServerPlayer player;

    /**
     * Intercept chat broadcast to apply custom formatting and cancel vanilla.
     * This targets the method that broadcasts the chat message to all players.
     * 
     * In 1.19.2, the method signature is broadcastChatMessage(PlayerChatMessage)
     */
    @Inject(method = "broadcastChatMessage", at = @At("HEAD"), cancellable = true)
    private void vonixcore$onBroadcastChatMessage(PlayerChatMessage message,
            CallbackInfo ci) {
        try {
            // In 1.19.2, serverContent() returns the Component with the message
            String rawMessage = message.serverContent().getString();

            // ALWAYS send to Discord (if running), regardless of chat formatting setting
            sendToDiscordIfEnabled(rawMessage);

            // Only apply custom chat formatting if essentials + chat formatting are enabled
            if (EssentialsConfig.CONFIG.enabled.get() && EssentialsConfig.CONFIG.chatFormattingEnabled.get()) {
                // Format the message with prefix/suffix using the server's format
                // Note: In 1.19.2, we use the vanilla format since ChatFormatter is 1.20.1+
                Component formatted = Component.literal("")
                    .append(player.getDisplayName())
                    .append(Component.literal(": " + rawMessage));

                // Broadcast the formatted message to all players
                player.server.getPlayerList().broadcastSystemMessage(formatted, false);

                // Cancel the vanilla broadcast since we handled it
                ci.cancel();
            }
            // Otherwise let vanilla handle the chat broadcast normally
        } catch (Exception e) {
            VonixCore.LOGGER.error("[VonixCore] Error in Forge chat mixin", e);
            // Let vanilla handle it if we fail
        }
    }

    /**
     * Send chat message to Discord if enabled, independent of chat formatting.
     */
    private void sendToDiscordIfEnabled(String rawMessage) {
        if (!DiscordManager.getInstance().isRunning()) {
            return;
        }

        boolean shouldSendToDiscord = true;

        // Check if chat filter is enabled and message starts with filter prefix
        if (DiscordConfig.CONFIG.enableChatFilter.get()) {
            String filterPrefix = DiscordConfig.CONFIG.chatFilterPrefix.get();
            if (filterPrefix != null && !filterPrefix.isEmpty() && rawMessage.startsWith(filterPrefix)) {
                shouldSendToDiscord = false;
            }
        }

        if (shouldSendToDiscord) {
            // Use player's display name (nickname support may vary in 1.19.2)
            String displayName = player.getDisplayName().getString();
            DiscordManager.getInstance()
                    .sendChatMessage(displayName, rawMessage, player.getStringUUID());
        }
    }
}
