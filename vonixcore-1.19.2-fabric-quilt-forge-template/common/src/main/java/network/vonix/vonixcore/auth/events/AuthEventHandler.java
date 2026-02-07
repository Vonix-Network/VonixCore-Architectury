package network.vonix.vonixcore.auth.events;

import com.mojang.brigadier.ParseResults;
import dev.architectury.event.EventResult;
import dev.architectury.event.CompoundEventResult;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.InteractionEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.event.events.common.ChatEvent;
// import dev.architectury.event.events.common.CommandEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.auth.AuthCommands;
import network.vonix.vonixcore.auth.AuthenticationManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles player events for authentication - freezing, command registration,
 * etc.
 */
public class AuthEventHandler {
    private static final Map<UUID, Boolean> frozenPlayers = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastChatReminder = new ConcurrentHashMap<>();

    public static void init() {
        // 1.19.2 API: CommandRegistrationEvent has 3 parameters (dispatcher, registry, selection)
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> {
            AuthCommands.register(dispatcher);
            VonixCore.LOGGER.info("[VonixCore] Auth commands registered");
        });

        PlayerEvent.PLAYER_JOIN.register(player -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                AuthenticationManager.onPlayerJoin(serverPlayer);
            }
        });

        PlayerEvent.PLAYER_QUIT.register(player -> {
            UUID uuid = player.getUUID();
            AuthenticationManager.onPlayerLeave(uuid);
            frozenPlayers.remove(uuid);
            lastChatReminder.remove(uuid);
        });

        TickEvent.PLAYER_PRE.register(player -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                if (isFrozen(serverPlayer.getUUID())) {
                    serverPlayer.teleportTo(serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ());
                    serverPlayer.setDeltaMovement(0, 0, 0);
                }
            }
        });

        // Block Break - Use LEFT_CLICK_BLOCK as proxy for breaking while frozen
        InteractionEvent.LEFT_CLICK_BLOCK.register((player, hand, pos, face) -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                if (isFrozen(serverPlayer.getUUID())) {
                    return EventResult.interruptFalse();
                }
            }
            return EventResult.pass();
        });

        // Block Place - Architectury InteractionEvent.RIGHT_CLICK_BLOCK
        InteractionEvent.RIGHT_CLICK_BLOCK.register((player, hand, pos, face) -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                if (isFrozen(serverPlayer.getUUID())) {
                    return EventResult.interruptFalse();
                }
            }
            return EventResult.pass();
        });

        // Item Toss - PlayerEvent.DROP_ITEM
        PlayerEvent.DROP_ITEM.register((player, itemEntity) -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                if (isFrozen(serverPlayer.getUUID())) {
                    ItemStack item = itemEntity.getItem();
                    if (player.getInventory().add(item)) {
                        itemEntity.discard();
                    }
                    return EventResult.interruptFalse();
                }
            }
            return EventResult.pass();
        });

        // Item Pickup - PlayerEvent.PICKUP_ITEM
        /*
        PlayerEvent.PICKUP_ITEM.register((player, itemEntity) -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                if (isFrozen(serverPlayer.getUUID())) {
                    return CompoundEventResult.interruptFalse(ItemStack.EMPTY);
                }
            }
            return CompoundEventResult.pass();
        });
        */

        // Right Click Item - InteractionEvent.RIGHT_CLICK_ITEM
        InteractionEvent.RIGHT_CLICK_ITEM.register((player, hand) -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                if (isFrozen(serverPlayer.getUUID())) {
                    return CompoundEventResult.interruptFalse(ItemStack.EMPTY);
                }
            }
            return CompoundEventResult.pass();
        });

        // Chat
        ChatEvent.RECEIVED.register((player, component) -> {
            return EventResult.pass();
        });
        
        // Command blocking temporarily disabled - requires platform specific implementation or Mixin
        /*
        CommandEvent.PROCESS.register((dispatcher, parseResults, selection) -> {
             CommandSourceStack source = (CommandSourceStack) parseResults.getContext().getSource();
             if (source.getEntity() instanceof ServerPlayer player) {
                 UUID uuid = player.getUUID();
                 if (!isFrozen(uuid)) return EventResult.pass();
                 
                 String command = parseResults.getReader().getString().toLowerCase();
                 if (!command.startsWith("login") && !command.startsWith("register")) {
                     player.sendSystemMessage(Component.literal("§cYou must authenticate first! Use §e/login <password>§c or §e/register"));
                     return EventResult.interruptFalse();
                 }
             }
             return EventResult.pass();
        });
        */
    }

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
}
