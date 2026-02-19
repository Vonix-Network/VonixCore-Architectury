package network.vonix.vonixcore.fabric.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import network.vonix.vonixcore.auth.AuthenticationManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to block commands for unauthenticated/frozen players.
 * Only allows /login and /register commands.
 * In 1.20.1, commands use ServerboundChatCommandPacket (separate from chat).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class AuthCommandBlockerMixin {

    @Shadow
    public ServerPlayer player;

    /**
     * Block command execution for frozen players.
     * In 1.20.1, commands are handled via handleChatCommand, not handleChat.
     */
    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void vonixcore$onHandleChatCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        if (AuthenticationManager.shouldFreeze(player.getUUID())) {
            String command = packet.command().toLowerCase().trim();

            // Allow only /login and /register commands
            if (command.startsWith("login ") || command.equals("login") ||
                command.startsWith("register ") || command.equals("register") ||
                command.startsWith("log ") || command.startsWith("reg ")) {
                return; // Allow these commands
            }

            // Block all other commands
            player.sendSystemMessage(Component.literal(
                "§cYou must authenticate first! Use §e/login <password>§c or §e/register <password>"));
            ci.cancel();
        }
    }
}
