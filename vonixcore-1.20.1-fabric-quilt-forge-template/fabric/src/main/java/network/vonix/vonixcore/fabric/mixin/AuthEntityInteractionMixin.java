package network.vonix.vonixcore.fabric.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import network.vonix.vonixcore.auth.AuthenticationManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to block entity interactions for unauthenticated/frozen players.
 * Prevents attacking and interacting with entities (villagers, animals, etc.)
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class AuthEntityInteractionMixin {

    @Shadow
    public ServerPlayer player;

    /**
     * Block entity interactions for frozen players.
     * This covers both attacking and right-click interactions.
     */
    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void vonixcore$onEntityInteract(net.minecraft.network.protocol.game.ServerboundInteractPacket packet, CallbackInfo ci) {
        if (AuthenticationManager.shouldFreeze(player.getUUID())) {
            player.sendSystemMessage(Component.literal(
                "§cYou cannot interact with entities while frozen! Authenticate with §e/login§c or §e/register"));
            ci.cancel();
        }
    }
}
