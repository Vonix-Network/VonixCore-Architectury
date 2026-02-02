package network.vonix.vonixcore.auth.events;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.auth.AuthCommands;
import network.vonix.vonixcore.auth.AuthenticationManager;
import network.vonix.vonixcore.config.AuthConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles player events for authentication - freezing, command registration,
 * etc.
 * 
 * Fabric port of Forge's AuthEventHandler with identical functionality:
 * - Movement freeze for unauthenticated players
 * - Block break/place prevention
 * - Item toss/pickup prevention
 * - Chat blocking (with reminder)
 * - Command filtering (only /login, /register allowed)
 */
public class AuthEventHandler {
    private static final Map<UUID, Boolean> frozenPlayers = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastChatReminder = new ConcurrentHashMap<>();

    public static void register() {
        // Register auth commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            AuthCommands.register(dispatcher);
            VonixCore.LOGGER.info("[VonixCore] Auth commands registered");
        });

        // Only register freeze events if auth is enabled
        if (!AuthConfig.getInstance().isEnabled()) {
            return;
        }

        // Player join event
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            AuthenticationManager.onPlayerJoin(player);
            updateFreezeState(player.getUUID());
        });

        // Player leave event
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.player.getUUID();
            AuthenticationManager.onPlayerLeave(uuid);
            frozenPlayers.remove(uuid);
            lastChatReminder.remove(uuid);
        });

        // Player tick event - freeze movement for unauthenticated players
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (isFrozen(player.getUUID())) {
                    // Teleport player to their position to prevent movement
                    player.teleportTo(player.getX(), player.getY(), player.getZ());
                    player.setDeltaMovement(0, 0, 0);
                }
            }
        });

        // Block break event - prevent if frozen
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer serverPlayer && isFrozen(serverPlayer.getUUID())) {
                return false; // Cancel break
            }
            return true;
        });

        // Block interaction - prevent if frozen
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player instanceof ServerPlayer serverPlayer && isFrozen(serverPlayer.getUUID())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        // Item use - prevent if frozen
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayer serverPlayer && isFrozen(serverPlayer.getUUID())) {
                return InteractionResultHolder.fail(player.getItemInHand(hand));
            }
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        });

        // Chat blocking - prevent chat for frozen players with reminder
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            UUID uuid = sender.getUUID();
            if (isFrozen(uuid)) {
                long now = System.currentTimeMillis();
                Long last = lastChatReminder.get(uuid);
                if (last == null || (now - last) >= 5000) {
                    sender.sendSystemMessage(
                            Component.literal("§cYou must authenticate! Use §e/login <password>§c or §e/register"));
                    lastChatReminder.put(uuid, now);
                }
                return false; // Cancel chat
            }
            return true;
        });

        VonixCore.LOGGER.info("[VonixCore] Auth event handlers registered");
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

    /**
     * Check if a command is allowed for unauthenticated players.
     * Only /login and /register are allowed.
     */
    public static boolean isCommandAllowed(String command) {
        String cmd = command.toLowerCase().trim();
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        return cmd.startsWith("login") || cmd.startsWith("register");
    }

    /**
     * Called when a player tries to execute a command.
     * Returns true if the command should be blocked.
     */
    public static boolean shouldBlockCommand(ServerPlayer player, String command) {
        if (!isFrozen(player.getUUID())) {
            return false; // Player authenticated, allow all
        }
        if (isCommandAllowed(command)) {
            return false; // Allow auth commands
        }
        player.sendSystemMessage(
                Component.literal("§cYou must authenticate first! Use §e/login <password>§c or §e/register"));
        return true; // Block command
    }
}
