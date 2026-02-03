package network.vonix.vonixcore.listener;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.ProtectionConfig;
import network.vonix.vonixcore.consumer.Consumer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Entity event listener for logging entity-related events.
 */
@Mod.EventBusSubscriber(modid = VonixCore.MODID)
public class EntityEventListener {

    private static final int ACTION_KILL = 0;
    private static final int ACTION_SPAWN = 1;

    /**
     * Handle entity death events.
     */
    @SubscribeEvent
    public static void onEntityDeath(LivingDeathEvent event) {
        if (!ProtectionConfig.CONFIG.logEntityKills.get()) {
            return;
        }

        LivingEntity entity = event.getEntity();
        Level level = entity.level();

        if (level.isClientSide()) {
            return;
        }

        // Get the killer
        Entity killer = event.getSource().getEntity();
        String user;
        if (killer instanceof Player player) {
            user = player.getName().getString();
        } else if (killer != null) {
            user = killer.getType().getDescriptionId();
        } else {
            user = "#" + event.getSource().type().msgId();
        }

        BlockPos pos = entity.blockPosition();
        String world = level.dimension().location().toString();
        long time = System.currentTimeMillis() / 1000L;
        String entityType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                .toString();

        Consumer.getInstance().queueEntry(new EntityLogEntry(
                time, user, world,
                pos.getX(), pos.getY(), pos.getZ(),
                entityType, null, ACTION_KILL));
    }

    /**
     * Entity log entry for the consumer queue.
     */
    public static class EntityLogEntry implements Consumer.QueueEntry {
        private final long time;
        private final String user;
        private final String world;
        private final int x, y, z;
        private final String entityType;
        private final String entityData;
        private final int action;

        public EntityLogEntry(long time, String user, String world, int x, int y, int z,
                String entityType, String entityData, int action) {
            this.time = time;
            this.user = user;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.entityType = entityType;
            this.entityData = entityData;
            this.action = action;
        }

        @Override
        public void execute(Connection conn) throws SQLException {
            String sql = "INSERT INTO vp_entity (time, user, world, x, y, z, entity_type, entity_data, action) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, time);
                stmt.setString(2, user);
                stmt.setString(3, world);
                stmt.setInt(4, x);
                stmt.setInt(5, y);
                stmt.setInt(6, z);
                stmt.setString(7, entityType);
                stmt.setString(8, entityData);
                stmt.setInt(9, action);
                stmt.executeUpdate();
            }
        }
    }
}
