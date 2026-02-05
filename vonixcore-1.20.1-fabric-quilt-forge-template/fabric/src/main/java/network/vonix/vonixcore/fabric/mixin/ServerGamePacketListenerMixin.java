package network.vonix.vonixcore.fabric.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.chat.ChatFormatter;
import network.vonix.vonixcore.config.EssentialsConfig;
import network.vonix.vonixcore.config.DiscordConfig;
import network.vonix.vonixcore.discord.DiscordManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to intercept chat messages and apply custom formatting while cancelling
 * vanilla broadcast.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {

    @Shadow
    public ServerPlayer player;

    /**
     * Intercept chat broadcast to apply custom formatting and cancel vanilla.
     * This targets the method that broadcasts the chat message to all players.
     */
    @Inject(method = "broadcastChatMessage", at = @At("HEAD"), cancellable = true)
    private void vonixcore$onBroadcastChatMessage(net.minecraft.network.chat.PlayerChatMessage message,
            CallbackInfo ci) {
        try {
            String rawMessage = message.signedContent();

            // ALWAYS send to Discord (if running), regardless of chat formatting setting
            sendToDiscordIfEnabled(rawMessage);

            // Only apply custom chat formatting if essentials + chat formatting are enabled
            if (EssentialsConfig.CONFIG.enabled.get() && EssentialsConfig.CONFIG.chatFormattingEnabled.get()) {
                // Format the message with prefix/suffix
                Component formatted = ChatFormatter.formatChatMessage(player, rawMessage);

                // Broadcast the formatted message to all players
                player.server.getPlayerList().broadcastSystemMessage(formatted, false);

                // Cancel the vanilla broadcast since we handled it
                ci.cancel();
            }
            // Otherwise let vanilla handle the chat broadcast normally
        } catch (Exception e) {
            VonixCore.LOGGER.error("[VonixCore] Error in chat mixin", e);
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
            String nickname = network.vonix.vonixcore.command.UtilityCommands.getNickname(player.getUUID());
            String displayName = nickname != null ? nickname : player.getName().getString();
            DiscordManager.getInstance()
                    .sendChatMessage(displayName, rawMessage, player.getStringUUID());
        }
    }
}
