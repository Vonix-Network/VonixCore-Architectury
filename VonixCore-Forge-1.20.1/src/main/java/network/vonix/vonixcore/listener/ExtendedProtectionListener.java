package network.vonix.vonixcore.listener;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.command.ProtectionCommands;
import network.vonix.vonixcore.config.ProtectionConfig;
import network.vonix.vonixcore.consumer.Consumer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extended protection event listener for additional CoreProtect-style logging.
 * Logs containers, entity kills, interactions, and signs.
 */
@Mod.EventBusSubscriber(modid = VonixCore.MODID)
public class ExtendedProtectionListener {

    // Action constants
    private static final int ACTION_KILL = 0;
    private static final int ACTION_REMOVE = 0;
    private static final int ACTION_ADD = 1;

    // Container snapshots for tracking changes
    private static final Map<Integer, NonNullList<ItemStack>> containerSnapshots = new ConcurrentHashMap<>();

    /**
     * Snapshot container contents on open.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!ProtectionConfig.CONFIG.enabled.get() || !ProtectionConfig.CONFIG.logContainerTransactions.get())
            return;

        ServerPlayer player = (ServerPlayer) event.getEntity();
        AbstractContainerMenu container = event.getContainer();

        // Deep copy all items in the container
        NonNullList<ItemStack> items = NonNullList.create();
        for (Slot slot : container.slots) {
            items.add(slot.getItem().copy());
        }

        containerSnapshots.put(container.containerId, items);
    }

    /**
     * Compare container contents on close and log changes.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!ProtectionConfig.CONFIG.enabled.get() || !ProtectionConfig.CONFIG.logContainerTransactions.get())
            return;

        AbstractContainerMenu container = event.getContainer();
        NonNullList<ItemStack> oldItems = containerSnapshots.remove(container.containerId);

        if (oldItems == null)
            return;

        ServerPlayer player = (ServerPlayer) event.getEntity();
        String user = player.getName().getString();
        String world = getWorldName(player.level());
        // Note: Using player position as we don't have direct container position
        // TODO: Track container position from PlayerInteractEvent
        BlockPos pos = player.blockPosition();
        long time = System.currentTimeMillis() / 1000L;

        // Compare old vs new items
        for (int i = 0; i < container.slots.size(); i++) {
            if (i >= oldItems.size())
                break;

            ItemStack oldStack = oldItems.get(i);
            ItemStack newStack = container.slots.get(i).getItem();

            if (ItemStack.matches(oldStack, newStack))
                continue;

            // Item added or increased
            if (!newStack.isEmpty()
                    && (oldStack.isEmpty() || !ItemStack.isSameItemSameTags(oldStack, newStack)
                            || newStack.getCount() > oldStack.getCount())) {
                int amount = newStack.getCount()
                        - (ItemStack.isSameItemSameTags(oldStack, newStack) ? oldStack.getCount() : 0);
                Consumer.getInstance().queueEntry(new ContainerLogEntry(
                        time, user, world, pos.getX(), pos.getY(), pos.getZ(),
                        "container", getItemName(newStack), amount, ACTION_ADD));
            }

            // Item removed or decreased
            if (!oldStack.isEmpty()
                    && (newStack.isEmpty() || !ItemStack.isSameItemSameTags(oldStack, newStack)
                            || newStack.getCount() < oldStack.getCount())) {
                int amount = oldStack.getCount()
                        - (ItemStack.isSameItemSameTags(oldStack, newStack) ? newStack.getCount() : 0);
                Consumer.getInstance().queueEntry(new ContainerLogEntry(
                        time, user, world, pos.getX(), pos.getY(), pos.getZ(),
                        "container", getItemName(oldStack), amount, ACTION_REMOVE));
            }
        }
    }

    /**
     * Log entity kills by players.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEntityDeath(LivingDeathEvent event) {
        if (!ProtectionConfig.CONFIG.enabled.get())
            return;
        if (!ProtectionConfig.CONFIG.logEntityKills.get())
            return;

        LivingEntity entity = event.getEntity();
        Level level = entity.level();
        if (level.isClientSide())
            return;

        if (event.getSource() == null || event.getSource().getEntity() == null)
            return;

        // Get killer
        Entity killer = event.getSource().getEntity();
        if (!(killer instanceof ServerPlayer player))
            return;

        String user = player.getName().getString();
        String world = getWorldName(level);
        BlockPos pos = entity.blockPosition();
        long time = System.currentTimeMillis() / 1000L;
        String entityType = getEntityId(entity);

        Consumer.getInstance().queueEntry(new EntityLogEntry(
                time, user, world,
                pos.getX(), pos.getY(), pos.getZ(),
                entityType, null, ACTION_KILL));
    }

    /**
     * Log player interactions with blocks.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerInteract(PlayerInteractEvent.RightClickBlock event) {
        if (!ProtectionConfig.CONFIG.enabled.get())
            return;
        if (!ProtectionConfig.CONFIG.logPlayerInteractions.get())
            return;
        if (event.getLevel().isClientSide())
            return;
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;

        // Don't log if player is inspecting
        if (ProtectionCommands.isInspecting(player.getUUID()))
            return;

        BlockPos pos = event.getPos();
        BlockState state = event.getLevel().getBlockState(pos);
        String blockType = getBlockId(state);

        // Only log interactive blocks
        if (!isInteractiveBlock(blockType))
            return;

        String user = player.getName().getString();
        String world = getWorldName(event.getLevel());
        long time = System.currentTimeMillis() / 1000L;

        Consumer.getInstance().queueEntry(new InteractionLogEntry(
                time, user, world,
                pos.getX(), pos.getY(), pos.getZ(),
                blockType));
    }

    /**
     * Log sign text changes.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onSignChange(BlockEvent.EntityPlaceEvent event) {
        if (!ProtectionConfig.CONFIG.enabled.get())
            return;
        if (!ProtectionConfig.CONFIG.logSigns.get())
            return;
        if (event.getLevel().isClientSide())
            return;
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;

        BlockPos pos = event.getPos();
        BlockEntity blockEntity = event.getLevel().getBlockEntity(pos);
        if (!(blockEntity instanceof SignBlockEntity sign))
            return;

        String user = player.getName().getString();
        String world = getWorldName(event.getLevel());
        long time = System.currentTimeMillis() / 1000L;

        // Get sign text (delayed to allow text to be set)
        player.getServer().execute(() -> {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                net.minecraft.network.chat.Component lineComp = sign.getFrontText().getMessage(i, false);
                String line = lineComp.getString();
                if (!line.isEmpty()) {
                    if (text.length() > 0)
                        text.append("|");
                    text.append(line);
                }
            }

            if (text.length() > 0) {
                Consumer.getInstance().queueEntry(new SignLogEntry(
                        time, user, world,
                        pos.getX(), pos.getY(), pos.getZ(),
                        text.toString()));
            }
        });
    }

    // Helper methods

    private static String getWorldName(net.minecraft.world.level.LevelAccessor level) {
        if (level instanceof Level l) {
            return l.dimension().location().toString();
        }
        return "unknown";
    }

    private static String getBlockId(BlockState state) {
        var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return key != null ? key.toString() : "minecraft:air";
    }

    private static String getEntityId(Entity entity) {
        var key = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return key != null ? key.toString() : "unknown";
    }

    private static String getItemName(ItemStack stack) {
        return net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
    }

    private static boolean isInteractiveBlock(String blockType) {
        return blockType.contains("door") ||
                blockType.contains("button") ||
                blockType.contains("lever") ||
                blockType.contains("gate") ||
                blockType.contains("trapdoor") ||
                blockType.contains("chest") ||
                blockType.contains("furnace") ||
                blockType.contains("anvil") ||
                blockType.contains("crafting") ||
                blockType.contains("barrel") ||
                blockType.contains("shulker") ||
                blockType.contains("hopper") ||
                blockType.contains("dispenser") ||
                blockType.contains("dropper") ||
                blockType.contains("brewing") ||
                blockType.contains("enchant");
    }

    // Queue entry classes

    /**
     * Container transaction log entry.
     */
    public static class ContainerLogEntry implements Consumer.QueueEntry {
        private final long time;
        private final String user;
        private final String world;
        private final int x, y, z;
        private final String type;
        private final String item;
        private final int amount;
        private final int action;

        public ContainerLogEntry(long time, String user, String world, int x, int y, int z,
                String type, String item, int amount, int action) {
            this.time = time;
            this.user = user;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
            this.item = item;
            this.amount = amount;
            this.action = action;
        }

        @Override
        public void execute(Connection conn) throws SQLException {
            String sql = "INSERT INTO vp_container (time, user, world, x, y, z, type, item, amount, action) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, time);
                stmt.setString(2, user);
                stmt.setString(3, world);
                stmt.setInt(4, x);
                stmt.setInt(5, y);
                stmt.setInt(6, z);
                stmt.setString(7, type);
                stmt.setString(8, item);
                stmt.setInt(9, amount);
                stmt.setInt(10, action);
                stmt.executeUpdate();
            }
        }
    }

    /**
     * Entity kill log entry.
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

    /**
     * Interaction log entry.
     */
    public static class InteractionLogEntry implements Consumer.QueueEntry {
        private final long time;
        private final String user;
        private final String world;
        private final int x, y, z;
        private final String blockType;

        public InteractionLogEntry(long time, String user, String world, int x, int y, int z,
                String blockType) {
            this.time = time;
            this.user = user;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockType = blockType;
        }

        @Override
        public void execute(Connection conn) throws SQLException {
            String sql = "INSERT INTO vp_interaction (time, user, world, x, y, z, type) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, time);
                stmt.setString(2, user);
                stmt.setString(3, world);
                stmt.setInt(4, x);
                stmt.setInt(5, y);
                stmt.setInt(6, z);
                stmt.setString(7, blockType);
                stmt.executeUpdate();
            }
        }
    }

    /**
     * Sign log entry.
     */
    public static class SignLogEntry implements Consumer.QueueEntry {
        private final long time;
        private final String user;
        private final String world;
        private final int x, y, z;
        private final String text;

        public SignLogEntry(long time, String user, String world, int x, int y, int z, String text) {
            this.time = time;
            this.user = user;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.text = text;
        }

        @Override
        public void execute(Connection conn) throws SQLException {
            String sql = "INSERT INTO vp_sign (time, user, world, x, y, z, text) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, time);
                stmt.setString(2, user);
                stmt.setString(3, world);
                stmt.setInt(4, x);
                stmt.setInt(5, y);
                stmt.setInt(6, z);
                stmt.setString(7, text);
                stmt.executeUpdate();
            }
        }
    }
}
