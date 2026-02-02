package network.vonix.vonixcore.discord;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.DiscordConfig;
import network.vonix.vonixcore.platform.Platform;
import org.javacord.api.entity.message.Message;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Main coordinator for Discord integration.
 * Delegates to BotClient (Receiving/Status), WebhookClient (Sending),
 * and helper managers for accounts and preferences.
 */
public class DiscordManager {
    private static DiscordManager instance;
    private final BotClient botClient;
    private final WebhookClient webhookClient;
    private final MessageConverter messageConverter;

    // Server reference
    private MinecraftServer server;

    // Sub-systems
    private LinkedAccountsManager linkedAccountsManager;
    private PlayerPreferences playerPreferences;

    private boolean running = false;
    private String eventChannelId;

    private DiscordManager() {
        this.botClient = new BotClient();
        this.webhookClient = new WebhookClient();
        this.messageConverter = new MessageConverter();
    }

    public static DiscordManager getInstance() {
        if (instance == null) {
            instance = new DiscordManager();
        }
        return instance;
    }

    public boolean isRunning() {
        return running && botClient.isConnected();
    }

    public void initialize(MinecraftServer server) {
        if (!DiscordConfig.CONFIG.enabled.get()) {
            VonixCore.LOGGER.info("[Discord] Disabled in config.");
            return;
        }

        this.server = server;
        this.running = true;

        // 1. Initialize Clients
        String webhookUrl = DiscordConfig.CONFIG.webhookUrl.get();
        String botToken = DiscordConfig.CONFIG.botToken.get();
        String channelId = DiscordConfig.CONFIG.channelId.get();

        this.webhookClient.updateUrl(webhookUrl);

        // Determine event channel
        String pEventChannelId = DiscordConfig.CONFIG.eventChannelId.get();
        if (pEventChannelId != null && !pEventChannelId.isEmpty()) {
            this.eventChannelId = pEventChannelId;
            VonixCore.LOGGER.info("[Discord] Using separate channel for events: {}", pEventChannelId);
        } else {
            this.eventChannelId = channelId;
            VonixCore.LOGGER.info("[Discord] Using main channel for events.");
        }

        // 2. Initialize Sub-systems
        Path configDir = Platform.getConfigDirectory();
        try {
            this.playerPreferences = new PlayerPreferences(configDir);
            if (DiscordConfig.CONFIG.enableAccountLinking.get()) {
                this.linkedAccountsManager = new LinkedAccountsManager(configDir);
            }
        } catch (IOException e) {
            VonixCore.LOGGER.error("[Discord] Failed to load data managers", e);
        }

        // 3. Connect Bot
        this.botClient.setMessageHandler(this::onDiscordMessage);
        this.botClient.connect(botToken, channelId).thenRun(() -> {
            // 4. Send Startup Message (only after connection)
            sendStartupEmbed(DiscordConfig.CONFIG.serverName.get());
        });

        VonixCore.LOGGER.info("[Discord] Integration initialized.");
    }

    public void shutdown() {
        if (!running)
            return;

        VonixCore.LOGGER.info("[Discord] Sending shutdown message...");
        try {
            sendShutdownEmbed(DiscordConfig.CONFIG.serverName.get()).get(5, TimeUnit.SECONDS);
            VonixCore.LOGGER.info("[Discord] Shutdown message sent successfully");
        } catch (Exception e) {
            VonixCore.LOGGER.warn("[Discord] Failed to send shutdown message", e);
        }

        running = false;

        if (botClient != null)
            botClient.disconnect();
        if (webhookClient != null)
            webhookClient.shutdown();
    }

    /**
     * Handles incoming messages from Discord (via BotClient).
     */
    private void onDiscordMessage(org.javacord.api.event.message.MessageCreateEvent event) {
        if (server == null)
            return;

        Message message = event.getMessage();
        String msgChannelId = message.getChannel().getIdAsString();
        String mainChannelId = DiscordConfig.CONFIG.channelId.get();
        String eventChannelId = DiscordConfig.CONFIG.eventChannelId.get();

        boolean isMainChannel = mainChannelId != null && mainChannelId.equals(msgChannelId);
        boolean isEventChannel = eventChannelId != null && !eventChannelId.isEmpty()
                && eventChannelId.equals(msgChannelId);

        // Ignore messages from other channels
        if (!isMainChannel && !isEventChannel) {
            return;
        }

        // If it's an event channel message, check if we should show other server events
        if (isEventChannel && !DiscordConfig.CONFIG.showOtherServerEvents.get()) {
            return;
        }

        // Filter out bots if configured
        if (DiscordConfig.CONFIG.ignoreBots.get() && message.getAuthor().isBotUser())
            return;

        // Filter out webhooks if configured
        if (DiscordConfig.CONFIG.ignoreWebhooks.get() && message.getAuthor().isWebhook())
            return;

        // Filter by prefix to prevent echoing our own messages
        if (DiscordConfig.CONFIG.filterByPrefix.get()) {
            String serverPrefix = DiscordConfig.CONFIG.serverPrefix.get();
            if (serverPrefix != null && !serverPrefix.isEmpty()) {
                String authorName = message.getAuthor().getDisplayName();
                // Check if author name starts with our prefix (e.g., "[HomeStead]SomePlayer")
                if (authorName.startsWith(serverPrefix)) {
                    return; // Skip - this is our own message echoing back
                }
                // Also check webhook username format
                String webhookFormat = DiscordConfig.CONFIG.webhookUsernameFormat.get();
                if (webhookFormat != null && webhookFormat.contains("{prefix}")) {
                    String expectedStart = webhookFormat.split("\\{prefix\\}")[0] + serverPrefix;
                    if (authorName.startsWith(expectedStart) || authorName.startsWith(serverPrefix)) {
                        return; // Skip - this is our own message
                    }
                }
            }
        }

        // Convert to Minecraft Component
        Component chatComponent = MessageConverter.toMinecraft(message);

        // Broadcast to server
        server.execute(() -> {
            server.getPlayerList().broadcastSystemMessage(chatComponent, false);
        });
    }

    // =================================================================================
    // Sending Methods (Minecraft -> Discord)
    // =================================================================================

    public void sendMinecraftMessage(String username, String message) {
        if (!running || webhookClient == null)
            return;

        String prefix = DiscordConfig.CONFIG.serverPrefix.get();
        String formattedUsername = DiscordConfig.CONFIG.webhookUsernameFormat.get()
                .replace("{prefix}", prefix)
                .replace("{username}", username);

        String avatarUrl = getAvatarUrl(username);

        webhookClient.sendMessage(formattedUsername, avatarUrl, message);
    }

    public void sendSystemMessage(String message) {
        if (!running)
            return;

        if (message.startsWith("💀")) {
            sendDeathEmbed(message);
        } else {
            // System messages often don't have a player, so use server avatar/name
            sendMinecraftMessage("Server", message);
        }
    }

    // =================================================================================
    // Embed Senders
    // =================================================================================

    private CompletableFuture<Message> sendEventEmbedInternal(Consumer<JsonObject> embedBuilder) {
        if (!running)
            return CompletableFuture.completedFuture(null);
        JsonObject embed = new JsonObject();
        embedBuilder.accept(embed);
        return botClient.sendEmbed(eventChannelId, embed);
    }

    public void sendStartupEmbed(String serverName) {
        sendEventEmbedInternal(EmbedFactory.createServerStatusEmbed(
                "Server Online",
                "Server is now online",
                0x43B581,
                serverName,
                "VonixCore"));
    }

    public CompletableFuture<Message> sendShutdownEmbed(String serverName) {
        return sendEventEmbedInternal(EmbedFactory.createServerStatusEmbed(
                "Server Offline",
                "Server is shutting down",
                0xF04747,
                serverName,
                "VonixCore"));
    }

    public void sendJoinEmbed(String username, String uuid) {
        if (!DiscordConfig.CONFIG.sendJoin.get())
            return;

        sendEventEmbedInternal(EmbedFactory.createPlayerEventEmbed(
                "Player Joined",
                username + " joined the game",
                0x5865F2,
                username,
                DiscordConfig.CONFIG.serverName.get(),
                "Join",
                getAvatarUrl(username) // TODO: Use UUID if possible
        ));
    }

    public void sendLeaveEmbed(String username, String uuid) {
        if (!DiscordConfig.CONFIG.sendLeave.get())
            return;

        sendEventEmbedInternal(EmbedFactory.createPlayerEventEmbed(
                "Player Left",
                username + " left the game",
                0x99AAB5,
                username,
                DiscordConfig.CONFIG.serverName.get(),
                "Leave",
                getAvatarUrl(username) // TODO: Use UUID if possible
        ));
    }

    // Deprecated single-arg methods for compatibility if needed
    public void sendJoinEmbed(String username) {
        sendJoinEmbed(username, null);
    }

    public void sendLeaveEmbed(String username) {
        sendLeaveEmbed(username, null);
    }

    public void updateStatus() {
        updateBotStatus();
    }

    public void sendServerStatusMessage(String title, String description, int color) {
        sendEventEmbedInternal(EmbedFactory.createServerStatusEmbed(
                title,
                description,
                color,
                DiscordConfig.CONFIG.serverName.get(),
                "VonixCore"));
    }

    public void sendChatMessage(String username, String message, String uuid) {
        sendMinecraftMessage(username, message);
    }

    public void sendDeathEmbed(String message) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Player Died");
        embed.addProperty("description", message);
        embed.addProperty("color", 0xF04747);
        if (running) {
            botClient.sendEmbed(eventChannelId, embed);
        }
    }

    public void sendAdvancementEmbed(String username, String title, String desc) {
        if (!DiscordConfig.CONFIG.sendAdvancement.get())
            return;

        sendEventEmbedInternal(EmbedFactory.createAdvancementEmbed(
                "🏆",
                0xFAA61A,
                username,
                title,
                desc));
    }

    public void updateBotStatus() {
        if (botClient != null && server != null) {
            botClient.updateStatus(server.getPlayerList().getPlayerCount(), server.getPlayerList().getMaxPlayers());
        }
    }

    // =================================================================================
    // Player Preferences Delegation
    // =================================================================================

    public void setServerMessagesFiltered(UUID playerUuid, boolean filtered) {
        if (playerPreferences != null) {
            playerPreferences.setServerMessagesFiltered(playerUuid, filtered);
        }
    }

    public boolean hasServerMessagesFiltered(UUID playerUuid) {
        return playerPreferences != null && playerPreferences.hasServerMessagesFiltered(playerUuid);
    }

    public void setEventsFiltered(UUID playerUuid, boolean filtered) {
        if (playerPreferences != null) {
            playerPreferences.setEventsFiltered(playerUuid, filtered);
        }
    }

    public boolean hasEventsFiltered(UUID playerUuid) {
        return playerPreferences != null && playerPreferences.hasEventsFiltered(playerUuid);
    }

    // =================================================================================
    // Account Linking Delegation
    // =================================================================================

    public String generateLinkCode(ServerPlayer player) {
        return linkedAccountsManager != null
                ? linkedAccountsManager.generateLinkCode(player.getUUID(), player.getName().getString())
                : null;
    }

    public boolean unlinkAccount(UUID uuid) {
        return linkedAccountsManager != null && linkedAccountsManager.unlinkMinecraft(uuid);
    }

    // =================================================================================
    // Helpers & Getters
    // =================================================================================

    private String getAvatarUrl(String username) {
        String url = DiscordConfig.CONFIG.avatarUrl.get().replace("{username}", username);
        if (server != null) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(username);
            if (player != null) {
                url = url.replace("{uuid}", player.getUUID().toString().replace("-", ""));
            }
        }
        return url;
    }

    public MinecraftServer getServer() {
        return server;
    }
}
