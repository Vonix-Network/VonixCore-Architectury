package network.vonix.vonixcore.fabric.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
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
 * 1.18.2 version - uses handleChat(ServerboundChatPacket) since
 * PlayerChatMessage doesn't exist.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {

    @Shadow
    public ServerPlayer player;

    /**
     * Intercept chat handling to apply custom formatting and cancel vanilla.
     * In 1.18.2, we target handleChat which receives the ServerboundChatPacket.
     */
    @Inject(method = "handleChat(Lnet/minecraft/network/protocol/game/ServerboundChatPacket;)V", at = @At("HEAD"), cancellable = true)
    private void vonixcore$onHandleChat(ServerboundChatPacket packet, CallbackInfo ci) {
        // Only intercept if essentials is enabled and chat formatting is enabled
        if (!EssentialsConfig.CONFIG.enabled.get() || !EssentialsConfig.CONFIG.chatFormattingEnabled.get()) {
            return;
        }

        try {
            String rawMessage = packet.getMessage();

            // Format the message with prefix/suffix
            Component formatted = ChatFormatter.formatChatMessage(player, rawMessage);

            // Broadcast the formatted message to all players
            // 1.18.2: Use broadcastMessage(Component, ChatType, UUID)
            player.server.getPlayerList().broadcastMessage(formatted, net.minecraft.network.chat.ChatType.SYSTEM,
                    net.minecraft.Util.NIL_UUID);

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

            // Cancel the vanilla handling
            ci.cancel();
        } catch (Exception e) {
            VonixCore.LOGGER.error("[VonixCore] Error in chat mixin", e);
            // Let vanilla handle it if we fail
        }
    }
}
