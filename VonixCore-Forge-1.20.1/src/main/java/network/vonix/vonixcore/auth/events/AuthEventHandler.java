package network.vonix.vonixcore.auth.events;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.CommandEvent;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.auth.AuthenticationManager;
import network.vonix.vonixcore.auth.AuthCommands;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles player events for authentication - freezing, command registration,
 * etc.
 */
@Mod.EventBusSubscriber(modid = VonixCore.MODID)
public class AuthEventHandler {
    private static final Map<UUID, Boolean> frozenPlayers = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastChatReminder = new ConcurrentHashMap<>();

    private static boolean isFrozen(UUID uuid) {
        return frozenPlayers.computeIfAbsent(uuid, AuthenticationManager::shouldFreeze);
    }

    public static void updateFreezeState(UUID uuid) {
        if (AuthenticationManager.isAuthenticated(uuid)) {
            frozenPlayers.remove(uuid);
        } else {
            frozenPlayers.put(uuid, AuthenticationManager.shouldFreeze(uuid));
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        AuthCommands.register(event.getDispatcher());
        VonixCore.LOGGER.info("[VonixCore] Auth commands registered");
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AuthenticationManager.onPlayerJoin(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        AuthenticationManager.onPlayerLeave(uuid);
        frozenPlayers.remove(uuid);
        lastChatReminder.remove(uuid);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START)
            return;
        if (event.player instanceof ServerPlayer player && isFrozen(player.getUUID())) {
            player.teleportTo(player.getX(), player.getY(), player.getZ());
            player.setDeltaMovement(0, 0, 0);
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && isFrozen(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isFrozen(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onItemToss(ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && isFrozen(player.getUUID())) {
            event.setCanceled(true);
            ItemEntity itemEntity = event.getEntity();
            ItemStack item = itemEntity.getItem();
            if (player.getInventory().add(item)) {
                itemEntity.discard();
            } else {
                event.setCanceled(false);
            }
        }
    }

    @SubscribeEvent
    public static void onItemPickup(EntityItemPickupEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isFrozen(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && isFrozen(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player && isFrozen(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        UUID uuid = player.getUUID();
        if (isFrozen(uuid)) {
            event.setCanceled(true);
            long now = System.currentTimeMillis();
            Long last = lastChatReminder.get(uuid);
            if (last == null || (now - last) >= 5000) {
                player.sendSystemMessage(
                        Component.literal("§cYou must authenticate! Use §e/login <password>§c or §e/register"));
                lastChatReminder.put(uuid, now);
            }
        }
    }

    /**
     * Block all commands except /login and /register for unauthenticated players
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCommand(CommandEvent event) {
        var source = event.getParseResults().getContext().getSource();
        if (source.getPlayer() == null) {
            return;
        }

        ServerPlayer player = (ServerPlayer) source.getPlayer();
        UUID uuid = player.getUUID();

        if (!isFrozen(uuid)) {
            return; // Player is authenticated, allow all commands
        }

        String command = event.getParseResults().getReader().getString().toLowerCase();

        // Only allow /login and /register commands
        if (!command.startsWith("login") && !command.startsWith("register")) {
            event.setCanceled(true);
            player.sendSystemMessage(
                    Component.literal("§cYou must authenticate first! Use §e/login <password>§c or §e/register"));
        }
    }
}
