package network.vonix.vonixcore.forge.mixin;

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
 * Forge mixin to intercept chat messages and apply custom formatting while
 * cancelling
 * vanilla broadcast. This prevents duplicate messages that occur when using
 * Architectury's ChatEvent which fires AFTER vanilla processing.
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
        // Only intercept if essentials is enabled and chat formatting is enabled
        if (!EssentialsConfig.CONFIG.enabled.get() || !EssentialsConfig.CONFIG.chatFormattingEnabled.get()) {
            return;
        }

        try {
            String rawMessage = message.signedContent();

            // Format the message with prefix/suffix
            Component formatted = ChatFormatter.formatChatMessage(player, rawMessage);

            // Broadcast the formatted message to all players
            player.server.getPlayerList().broadcastSystemMessage(formatted, false);

            // Send to Discord (with optional prefix filtering)
            if (DiscordManager.getInstance().isRunning()) {
                boolean shouldSendToDiscord = true;

                // Check if chat filter is enabled and message starts with filter prefix
                if (DiscordConfig.CONFIG.enableChatFilter.get()) {
                    String filterPrefix = DiscordConfig.CONFIG.chatFilterPrefix.get();
                    if (filterPrefix != null && !filterPrefix.isEmpty() && rawMessage.startsWith(filterPrefix)) {
                        shouldSendToDiscord = false;
                    }
                }

                if (shouldSendToDiscord) {
                    DiscordManager.getInstance()
                            .sendChatMessage(player.getName().getString(), rawMessage, player.getStringUUID());
                }
            }

            // Cancel the vanilla broadcast
            ci.cancel();
        } catch (Exception e) {
            VonixCore.LOGGER.error("[VonixCore] Error in Forge chat mixin", e);
            // Let vanilla handle it if we fail
        }
    }
}
