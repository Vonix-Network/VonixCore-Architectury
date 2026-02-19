package network.vonix.vonixcore.forge.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import network.vonix.vonixcore.auth.AuthenticationManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge mixin to prevent frozen/unauthenticated players from picking up items.
 */
@Mixin(ItemEntity.class)
public class AuthItemPickupMixin {

    /**
     * Prevent item pickup by frozen players.
     */
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void vonixcore$onPlayerTouch(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer) {
            if (AuthenticationManager.shouldFreeze(serverPlayer.getUUID())) {
                ci.cancel(); // Prevent pickup
            }
        }
    }
}
