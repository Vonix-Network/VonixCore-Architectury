package network.vonix.vonixcore.neoforge.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import network.vonix.vonixcore.auth.AuthenticationManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge mixin to block commands for unauthenticated/frozen players.
 * Only allows /login and /register commands.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class AuthCommandBlockerMixin {

    @Shadow
    public ServerPlayer player;

    /**
     * Block command execution for frozen players.
     */
    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void vonixcore$onHandleChatCommand(ServerboundChatPacket packet, CallbackInfo ci) {
        String message = packet.message();
        
        // Only process if it's a command and player is frozen
        if (message.startsWith("/") && AuthenticationManager.shouldFreeze(player.getUUID())) {
            String command = message.toLowerCase().trim();
            
            // Allow only /login and /register commands
            if (command.startsWith("/login ") || command.startsWith("/login") ||
                command.startsWith("/register ") || command.startsWith("/register") ||
                command.startsWith("/log ") || command.startsWith("/reg ")) {
                return; // Allow these commands
            }
            
            // Block all other commands
            player.sendSystemMessage(Component.literal(
                "§cYou must authenticate first! Use §e/login <password>§c or §e/register <password>"));
            ci.cancel();
        }
    }
}
