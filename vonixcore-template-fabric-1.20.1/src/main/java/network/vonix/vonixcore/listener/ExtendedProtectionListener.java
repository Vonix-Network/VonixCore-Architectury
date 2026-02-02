package network.vonix.vonixcore.listener;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.ProtectionConfig;
import network.vonix.vonixcore.consumer.Consumer;

/**
 * Extended protection listener for comprehensive block logging.
 */
public class ExtendedProtectionListener {

    public static void register() {
        if (!VonixCore.getInstance().isProtectionEnabled()) {
            return;
        }

        // Block break logging
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!world.isClientSide() && ProtectionConfig.getInstance().isLogBlockBreak()) {
                logBlockBreak(player, world, pos, state);
            }
        });

        // Block interaction logging
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() && ProtectionConfig.getInstance().isLogContainerAccess()) {
                BlockPos pos = hitResult.getBlockPos();
                BlockState state = world.getBlockState(pos);
                // Log container access
                if (isContainer(state)) {
                    logContainerAccess(player, world, pos, state);
                }
            }
            return InteractionResult.PASS;
        });
    }

    private static boolean isContainer(BlockState state) {
        String name = state.getBlock().getDescriptionId();
        return name.contains("chest") || name.contains("barrel") || 
               name.contains("shulker") || name.contains("hopper") ||
               name.contains("dispenser") || name.contains("dropper");
    }

    private static void logBlockBreak(Player player, Level world, BlockPos pos, BlockState state) {
        VonixCore.executeAsync(() -> {
            try {
                Consumer.getInstance().queue(new Consumer.LogEntry(
                    "break",
                    player.getName().getString(),
                    world.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    state.getBlock().getDescriptionId()
                ));
            } catch (Exception e) {
                VonixCore.LOGGER.error("[Protection] Failed to log block break: {}", e.getMessage());
            }
        });
    }

    private static void logContainerAccess(Player player, Level world, BlockPos pos, BlockState state) {
        VonixCore.executeAsync(() -> {
            try {
                Consumer.getInstance().queue(new Consumer.LogEntry(
                    "container",
                    player.getName().getString(),
                    world.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    state.getBlock().getDescriptionId()
                ));
            } catch (Exception e) {
                VonixCore.LOGGER.error("[Protection] Failed to log container access: {}", e.getMessage());
            }
        });
    }
}
