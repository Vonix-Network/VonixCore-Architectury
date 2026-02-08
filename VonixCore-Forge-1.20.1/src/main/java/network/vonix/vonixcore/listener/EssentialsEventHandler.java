package network.vonix.vonixcore.listener;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.chat.ChatFormatter;
import network.vonix.vonixcore.command.UtilityCommands;
import network.vonix.vonixcore.command.WorldCommands;
import network.vonix.vonixcore.config.EssentialsConfig;
import network.vonix.vonixcore.permissions.PermissionCommands;
import network.vonix.vonixcore.permissions.PermissionManager;

import java.sql.Connection;

/**
 * Event handler for essentials features: commands, permissions, chat
 * formatting.
 */
@Mod.EventBusSubscriber(modid = VonixCore.MODID)
public class EssentialsEventHandler {

    /**
     * Register all essentials commands.
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        if (!EssentialsConfig.CONFIG.enabled.get()) {
            return;
        }

        VonixCore.LOGGER.info("[VonixCore] Registering essentials commands...");

        // Register utility commands (tp, rtp, msg, nick, etc.)
        UtilityCommands.register(event.getDispatcher());

        // Register world commands (weather, time, afk, etc.)
        WorldCommands.register(event.getDispatcher());

        // Register permission commands (if not using LuckPerms)
        PermissionCommands.register(event.getDispatcher());

        VonixCore.LOGGER.info("[VonixCore] Essentials commands registered");
    }

    /**
     * Initialize permission system on server start.
     */
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        if (!EssentialsConfig.CONFIG.enabled.get()) {
            return;
        }

        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PermissionManager.getInstance().initialize(conn);
            VonixCore.LOGGER.info("[VonixCore] Permission system initialized");
        } catch (Exception e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to initialize permission system", e);
        }
    }

    /**
     * Format chat messages with prefix/suffix.
     */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onChatFormat(ServerChatEvent event) {
        if (!EssentialsConfig.CONFIG.enabled.get()) {
            return;
        }

        ServerPlayer player = event.getPlayer();
        String rawMessage = event.getRawText();

        // Format the message with prefix/suffix
        Component formatted = ChatFormatter.formatChatMessage(player, rawMessage);

        // Cancel the original event to prevent default rendering (which adds the double
        // name)
        event.setCanceled(true);

        // Manually broadcast the formatted message to all players (tellraw style)
        for (ServerPlayer p : player.getServer().getPlayerList().getPlayers()) {
            p.sendSystemMessage(formatted);
        }

        // Log to console
        player.getServer().sendSystemMessage(formatted);
    }

    /**
     * Track player join for /seen and permission cache.
     */
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Track for /seen command
            UtilityCommands.onPlayerJoin(player.getUUID());

            // Pre-load permission data
            PermissionManager.getInstance().getUser(player.getUUID());
        }
    }

    /**
     * Track player leave for /seen and clear AFK/ignore state.
     */
    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Track for /seen command
            UtilityCommands.onPlayerLeave(player.getUUID());

            // Clear AFK status
            WorldCommands.clearAfk(player.getUUID());

            // Clear permission cache for this player
            PermissionManager.getInstance().clearUserCache(player.getUUID());
        }
    }

    // Death location saving is handled in PlayerEventListener to avoid duplicates
}
