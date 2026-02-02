package network.vonix.vonixcore.listener;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.ProtectionConfig;
import network.vonix.vonixcore.consumer.Consumer;

/**
 * Block event listener for logging block changes.
 */
@Mod.EventBusSubscriber(modid = VonixCore.MODID)
public class BlockEventListener {

    private static final int ACTION_BREAK = 0;
    private static final int ACTION_PLACE = 1;
    private static final int ACTION_EXPLODE = 2;

    /**
     * Handle block break events.
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!ProtectionConfig.CONFIG.enabled.get() || !ProtectionConfig.CONFIG.logBlockBreak.get()) {
            return;
        }

        if (event.getLevel().isClientSide()) {
            return;
        }

        BlockPos pos = event.getPos();
        BlockState state = event.getState();
        String user = event.getPlayer().getName().getString();
        String world = getWorldName(event.getLevel());

        long time = System.currentTimeMillis() / 1000L;
        String blockType = getBlockId(state);
        String blockData = serializeBlockState(state);

        Consumer.getInstance().queueEntry(new Consumer.BlockLogEntry(
                time, user, world,
                pos.getX(), pos.getY(), pos.getZ(),
                blockType, blockType, blockData, "minecraft:air", null,
                ACTION_BREAK));
    }

    /**
     * Handle block place events.
     */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!ProtectionConfig.CONFIG.enabled.get() || !ProtectionConfig.CONFIG.logBlockPlace.get()) {
            return;
        }

        if (event.getLevel().isClientSide()) {
            return;
        }

        Entity entity = event.getEntity();
        if (!(entity instanceof ServerPlayer player)) {
            return;
        }

        BlockPos pos = event.getPos();
        BlockState state = event.getPlacedBlock();
        // In NeoForge, we don't have easy access to the replaced block, assume air
        String oldBlockType = "minecraft:air";
        String user = player.getName().getString();
        String world = getWorldName(event.getLevel());

        long time = System.currentTimeMillis() / 1000L;
        String blockType = getBlockId(state);
        String blockData = serializeBlockState(state);

        Consumer.getInstance().queueEntry(new Consumer.BlockLogEntry(
                time, user, world,
                pos.getX(), pos.getY(), pos.getZ(),
                blockType, oldBlockType, null, blockType, blockData,
                ACTION_PLACE));
    }

    /**
     * Handle explosion events.
     */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!ProtectionConfig.CONFIG.enabled.get() || !ProtectionConfig.CONFIG.logBlockExplode.get()) {
            return;
        }

        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }

        String world = getWorldName(level);
        long time = System.currentTimeMillis() / 1000L;

        // Determine the source of the explosion
        Entity source = event.getExplosion().getDirectSourceEntity();
        String user = source != null ? source.getType().getDescriptionId() : "#explosion";

        // Log each affected block
        for (BlockPos pos : event.getAffectedBlocks()) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir())
                continue;

            String blockType = getBlockId(state);
            String blockData = serializeBlockState(state);

            Consumer.getInstance().queueEntry(new Consumer.BlockLogEntry(
                    time, user, world,
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockType, blockType, blockData, "minecraft:air", null,
                    ACTION_EXPLODE));
        }
    }

    /**
     * Get the world name from a LevelAccessor.
     */
    private static String getWorldName(net.minecraft.world.level.LevelAccessor level) {
        if (level instanceof Level l) {
            return l.dimension().location().toString();
        }
        return "unknown";
    }

    /**
     * Get the block ID as a string.
     */
    private static String getBlockId(BlockState state) {
        ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key != null ? key.toString() : "minecraft:air";
    }

    /**
     * Serialize block state properties to a string.
     */
    private static String serializeBlockState(BlockState state) {
        if (state.getValues().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        state.getValues().forEach((property, value) -> {
            if (sb.length() > 0)
                sb.append(",");
            sb.append(property.getName()).append("=").append(value.toString());
        });
        return sb.toString();
    }
}
