package network.vonix.vonixcore.listener;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.ProtectionConfig;

/**
 * Handles block-related events for protection logging.
 */
public class BlockEventListener {

    /**
     * Register all block event listeners.
     */
    public static void register() {
        // Block break event
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!world.isClientSide() && VonixCore.getInstance().isProtectionEnabled()) {
                if (ProtectionConfig.getInstance().isLogBlockBreak()) {
                    logBlockBreak(player, pos, state);
                }
            }
        });

        // Block place/interact event
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() && VonixCore.getInstance().isProtectionEnabled()) {
                // This callback fires for block interaction, not placement
                // Block placement logging would need a mixin or different approach
            }
            return InteractionResult.PASS;
        });
    }

    private static void logBlockBreak(Player player, BlockPos pos, BlockState state) {
        VonixCore.executeAsync(() -> {
            try {
                // Log to protection consumer
                // Consumer.getInstance().logBlockBreak(player.getUUID().toString(),
                // player.level().dimension().location().toString(),
                // pos.getX(), pos.getY(), pos.getZ(),
                // state.getBlock().getDescriptionId());
                VonixCore.LOGGER.debug("[Protection] Block break: {} at {} by {}",
                        state.getBlock().getDescriptionId(), pos, player.getName().getString());
            } catch (Exception e) {
                VonixCore.LOGGER.error("[Protection] Failed to log block break: {}", e.getMessage());
            }
        });
    }
}
