package network.vonix.vonixcore.listener;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.ChatEvent;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.chat.ChatFormatter;
import network.vonix.vonixcore.command.UtilityCommands;
import network.vonix.vonixcore.command.VonixCoreCommands;
import network.vonix.vonixcore.command.WorldCommands;
import network.vonix.vonixcore.config.EssentialsConfig;
import network.vonix.vonixcore.permissions.PermissionCommands;
import network.vonix.vonixcore.permissions.PermissionManager;

import java.sql.Connection;

/**
 * Event handler for essentials features: commands, permissions, chat
 * formatting.
 */
public class EssentialsEventHandler {

    public static void init() {
        // Register Commands
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> {
            if (!EssentialsConfig.CONFIG.enabled.get()) {
                return;
            }

            VonixCore.LOGGER.info("[VonixCore] Registering essentials commands...");

            // Register utility commands (tp, rtp, msg, nick, etc.)
            UtilityCommands.register(dispatcher);

            // Register world commands (weather, time, afk, etc.)
            WorldCommands.register(dispatcher);

            // Register permission commands (if not using LuckPerms)
            PermissionCommands.register(dispatcher);

            // Register main VonixCore commands (homes, warps, kits, admin, etc.)
            VonixCoreCommands.register(dispatcher);

            VonixCore.LOGGER.info("[VonixCore] Essentials commands registered");
        });

        // Initialize permission system on server start
        LifecycleEvent.SERVER_STARTING.register(server -> {
            if (!EssentialsConfig.CONFIG.enabled.get()) {
                return;
            }

            try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
                PermissionManager.getInstance().initialize(conn);
                VonixCore.LOGGER.info("[VonixCore] Permission system initialized");
            } catch (Exception e) {
                VonixCore.LOGGER.error("[VonixCore] Failed to initialize permission system", e);
            }
        });

        // Chat Formatting
        ChatEvent.RECEIVED.register((player, component) -> {
            if (!EssentialsConfig.CONFIG.enabled.get() || !EssentialsConfig.CONFIG.chatFormattingEnabled.get()) {
                return EventResult.pass();
            }

            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                String rawMessage = component.getString();
                
                // Format the message with prefix/suffix
                Component formatted = ChatFormatter.formatChatMessage(serverPlayer, rawMessage);

                // Manually broadcast the formatted message to all players
                serverPlayer.server.getPlayerList().broadcastSystemMessage(formatted, false);
                
                // Send to Discord
                try {
                    if (network.vonix.vonixcore.discord.DiscordManager.getInstance().isRunning()) {
                        network.vonix.vonixcore.discord.DiscordManager.getInstance()
                            .sendChatMessage(serverPlayer.getName().getString(), rawMessage, serverPlayer.getStringUUID());
                    }
                } catch (Exception e) {
                    VonixCore.LOGGER.error("Failed to send chat to Discord", e);
                }

                // Cancel the original event to prevent default rendering
                return EventResult.interruptTrue();
            }

            return EventResult.pass();
        });

        // Track player join for /seen and permission cache
        PlayerEvent.PLAYER_JOIN.register(player -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                // Track for /seen command
                UtilityCommands.onPlayerJoin(serverPlayer.getUUID());

                // Pre-load permission data
                PermissionManager.getInstance().getUser(serverPlayer.getUUID());
            }
        });

        // Track player leave for /seen and clear AFK/ignore state
        PlayerEvent.PLAYER_QUIT.register(player -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                // Track for /seen command
                UtilityCommands.onPlayerLeave(serverPlayer.getUUID());

                // Clear AFK status
                WorldCommands.clearAfk(serverPlayer.getUUID());

                // Clear permission cache for this player
                PermissionManager.getInstance().clearUserCache(serverPlayer.getUUID());
            }
        });
    }
}
