package network.vonix.vonixcore.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.DiscordConfig;
import okhttp3.*;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.javacord.api.entity.activity.ActivityType;
import org.javacord.api.entity.intent.Intent;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.interaction.SlashCommand;
import org.javacord.api.interaction.SlashCommandInteraction;

import java.awt.Color;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Discord integration manager using Javacord + Webhooks.
 * - Minecraft → Discord: Webhooks for messages and embeds
 * - Discord → Minecraft: Javacord gateway for message reception
 * - Bot Status: Javacord API for real-time player count updates
 * - Slash Commands: Javacord API for /list command
 */
public class DiscordManager {

    private static DiscordManager instance;
    private MinecraftServer server;
    private final OkHttpClient httpClient;
    private final BlockingQueue<WebhookMessage> messageQueue;
    private Thread messageQueueThread;
    private boolean running = false;
    private String ourWebhookId = null;
    private String eventWebhookId = null;
    private DiscordApi discordApi = null;
    private LinkedAccountsManager linkedAccountsManager = null;
    private PlayerPreferences playerPreferences = null;
    private ServerPrefixConfig serverPrefixConfig = null;
    
    // Advancement message formatting components
    private final AdvancementEmbedDetector advancementDetector;
    private final AdvancementDataExtractor advancementExtractor;
    private final VanillaComponentBuilder componentBuilder;
    
    // Event message formatting components
    private final EventEmbedDetector eventDetector;
    private final EventDataExtractor eventExtractor;

    private static final Pattern DISCORD_MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)]\\((https?://[^)]+)\\)");

    private DiscordManager() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        this.messageQueue = new LinkedBlockingQueue<>(
                DiscordConfig.getInstance().getMessageQueueSize());
        
        // Initialize advancement processing components
        this.advancementDetector = new AdvancementEmbedDetector();
        this.advancementExtractor = new AdvancementDataExtractor();
        this.componentBuilder = new VanillaComponentBuilder();
        
        // Initialize event processing components
        this.eventDetector = new EventEmbedDetector();
        this.eventExtractor = new EventDataExtractor();
    }

    public static DiscordManager getInstance() {
        if (instance == null) {
            instance = new DiscordManager();
        }
        return instance;
    }

    public void initialize(MinecraftServer server) {
        if (!DiscordConfig.getInstance().isEnabled()) {
            VonixCore.LOGGER.info("[Discord] Discord integration is disabled in config");
            return;
        }

        this.server = server;
        String token = DiscordConfig.getInstance().getBotToken();

        if (token == null || token.isEmpty() || token.equals("YOUR_BOT_TOKEN_HERE")) {
            VonixCore.LOGGER.warn("[Discord] Bot token not configured, Discord integration disabled");
            return;
        }

        VonixCore.LOGGER.info("[Discord] Starting Discord integration (Javacord + Webhooks)...");

        extractWebhookId();

        // Initialize player preferences
        try {
            playerPreferences = new PlayerPreferences(VonixCore.getInstance().getConfigPath());
            VonixCore.LOGGER.info("[Discord] Player preferences system initialized");
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Failed to initialize player preferences", e);
        }

        // Initialize server prefix configuration
        try {
            serverPrefixConfig = new ServerPrefixConfig();
            String configPrefix = DiscordConfig.getInstance().getServerPrefix();
            if (configPrefix != null && !configPrefix.trim().isEmpty()) {
                String strippedPrefix = stripBracketsFromPrefix(configPrefix.trim());
                serverPrefixConfig.setFallbackPrefix(strippedPrefix);
            }
            VonixCore.LOGGER.info("[Discord] Server prefix configuration system initialized");
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Failed to initialize server prefix configuration", e);
            serverPrefixConfig = new ServerPrefixConfig();
        }

        running = true;
        startMessageQueueThread();

        // Initialize Javacord
        initializeJavacord(token);

        // Send startup embed
        String serverName = DiscordConfig.getInstance().getServerName();
        sendStartupEmbed(serverName);

        VonixCore.LOGGER.info("[Discord] Discord integration initialized successfully!");
        if (ourWebhookId != null) {
            VonixCore.LOGGER.info("[Discord] Chat Webhook ID: {}", ourWebhookId);
        }
        if (eventWebhookId != null) {
            VonixCore.LOGGER.info("[Discord] Event Webhook ID: {}", eventWebhookId);
        }
    }

    public void shutdown() {
        if (!running) {
            return;
        }

        VonixCore.LOGGER.info("[Discord] Shutting down Discord integration...");
        running = false;

        if (messageQueueThread != null && messageQueueThread.isAlive()) {
            messageQueueThread.interrupt();
            try {
                messageQueueThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (discordApi != null) {
            try {
                discordApi.disconnect().get(5, TimeUnit.SECONDS);
                VonixCore.LOGGER.info("[Discord] Javacord disconnected");
            } catch (Exception e) {
                VonixCore.LOGGER.warn("[Discord] Javacord disconnect timeout: {}", e.getMessage());
            } finally {
                discordApi = null;
            }
        }

        if (httpClient != null) {
            try {
                httpClient.dispatcher().executorService().shutdown();
                httpClient.connectionPool().evictAll();
                httpClient.dispatcher().executorService().awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                httpClient.dispatcher().executorService().shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        VonixCore.LOGGER.info("[Discord] Discord integration shut down");
    }

    public boolean isRunning() {
        return running;
    }

    // ========= Account Linking =========

    public String generateLinkCode(ServerPlayer player) {
        if (linkedAccountsManager == null || !DiscordConfig.getInstance().isEnableAccountLinking()) {
            return null;
        }
        return linkedAccountsManager.generateLinkCode(player.getUUID(), player.getName().getString());
    }

    public boolean unlinkAccount(UUID uuid) {
        if (linkedAccountsManager == null || !DiscordConfig.getInstance().isEnableAccountLinking()) {
            return false;
        }
        return linkedAccountsManager.unlinkMinecraft(uuid);
    }

    // ========= Player Preferences =========

    public boolean hasServerMessagesFiltered(UUID playerUuid) {
        if (playerPreferences == null) {
            return false;
        }
        return playerPreferences.hasServerMessagesFiltered(playerUuid);
    }

    public void setServerMessagesFiltered(UUID playerUuid, boolean filtered) {
        if (playerPreferences != null) {
            playerPreferences.setServerMessagesFiltered(playerUuid, filtered);
        }
    }

    public boolean hasEventsFiltered(UUID playerUuid) {
        if (playerPreferences == null) {
            return false;
        }
        return playerPreferences.hasEventsFiltered(playerUuid);
    }

    public void setEventsFiltered(UUID playerUuid, boolean filtered) {
        if (playerPreferences != null) {
            playerPreferences.setEventsFiltered(playerUuid, filtered);
        }
    }

    // ========= Webhook ID Extraction =========

    private void extractWebhookId() {
        ourWebhookId = extractWebhookIdFromConfig(
                DiscordConfig.getInstance().getWebhookId(),
                DiscordConfig.getInstance().getWebhookUrl(),
                "chat");
        eventWebhookId = extractWebhookIdFromConfig(
                "",
                DiscordConfig.getInstance().getEventWebhookUrl(),
                "event");
    }

    private String extractWebhookIdFromConfig(String manualId, String webhookUrl, String type) {
        if (manualId != null && !manualId.isEmpty()) {
            return manualId;
        }

        if (webhookUrl != null && !webhookUrl.isEmpty() && !webhookUrl.contains("YOUR_WEBHOOK_URL")) {
            return extractIdFromWebhookUrl(webhookUrl);
        }

        return null;
    }

    private String extractIdFromWebhookUrl(String webhookUrl) {
        try {
            String[] parts = webhookUrl.split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                if ("webhooks".equals(parts[i]) && i + 1 < parts.length) {
                    return parts[i + 1];
                }
            }
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Error extracting webhook ID", e);
        }
        return null;
    }

    // ========= Javacord Initialization =========

    private void initializeJavacord(String botToken) {
        try {
            String channelId = DiscordConfig.getInstance().getChannelId();
            if (channelId == null || channelId.equals("YOUR_CHANNEL_ID_HERE")) {
                VonixCore.LOGGER.warn("[Discord] Channel ID not configured");
                return;
            }

            VonixCore.LOGGER.info("[Discord] Connecting to Discord (async)...");

            new DiscordApiBuilder()
                    .setToken(botToken)
                    .setIntents(Intent.GUILD_MESSAGES, Intent.MESSAGE_CONTENT)
                    .login()
                    .orTimeout(15, TimeUnit.SECONDS)
                    .whenComplete((api, error) -> {
                        if (error != null) {
                            VonixCore.LOGGER.error("[Discord] Failed to connect: {}", error.getMessage());
                            return;
                        }
                        discordApi = api;
                        onJavacordConnected(channelId);
                    });
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Failed to initialize Javacord", e);
            discordApi = null;
        }
    }

    private void onJavacordConnected(String channelId) {
        try {
            VonixCore.LOGGER.info("[Discord] Connected as: {}", discordApi.getYourself().getName());

            long channelIdLong = Long.parseLong(channelId);
            String eventChannelIdStr = DiscordConfig.getInstance().getEventChannelId();
            Long eventChannelIdLong = null;

            if (eventChannelIdStr != null && !eventChannelIdStr.isEmpty()) {
                try {
                    eventChannelIdLong = Long.parseLong(eventChannelIdStr);
                } catch (NumberFormatException e) {
                    // Ignore
                }
            }

            final Long finalEventChannelId = eventChannelIdLong;
            discordApi.addMessageCreateListener(event -> {
                long msgChannelId = event.getChannel().getId();
                VonixCore.LOGGER.debug("[Discord] Received message in channel {}, listening for {} / {}", 
                        msgChannelId, channelIdLong, finalEventChannelId);
                if (msgChannelId == channelIdLong ||
                        (finalEventChannelId != null && msgChannelId == finalEventChannelId)) {
                    VonixCore.LOGGER.debug("[Discord] Processing message from: {}", 
                            event.getMessageAuthor().getDisplayName());
                    processJavacordMessage(event);
                }
            });

            VonixCore.LOGGER.info("[Discord] Listening for messages in channel: {}", channelId);
            if (DiscordConfig.getInstance().isEnableAccountLinking()) {
                try {
                    linkedAccountsManager = new LinkedAccountsManager(VonixCore.getInstance().getConfigPath());
                    VonixCore.LOGGER.info("[Discord] Account linking initialized ({} accounts)",
                            linkedAccountsManager.getLinkedCount());
                } catch (Exception e) {
                    VonixCore.LOGGER.error("[Discord] Failed to initialize account linking", e);
                }
            }

            registerListCommandAsync();
            if (DiscordConfig.getInstance().isEnableAccountLinking() && linkedAccountsManager != null) {
                registerLinkCommandsAsync();
            }

            updateBotStatus();

        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Error during post-connection setup", e);
        }
    }

    private void processJavacordMessage(org.javacord.api.event.message.MessageCreateEvent event) {
        try {
            boolean isBot = event.getMessageAuthor().asUser().map(user -> user.isBot()).orElse(false);
            boolean isWebhook = !event.getMessageAuthor().asUser().isPresent();
            String content = event.getMessageContent();
            String authorName = event.getMessageAuthor().getDisplayName();

            // Handle !list command
            if (content.trim().equalsIgnoreCase("!list")) {
                handleTextListCommand(event);
                return;
            }

            // Filter our own webhooks based on username prefix
            // The webhook username format is "[prefix]username", so check for bracket-wrapped prefix
            if (isWebhook) {
                String ourPrefix = "[" + getFallbackServerPrefix() + "]";
                if (authorName != null && authorName.startsWith(ourPrefix)) {
                    return;
                }
            }

            // Filter other webhooks if configured
            if (DiscordConfig.getInstance().isIgnoreWebhooks() && isWebhook) {
                if (DiscordConfig.getInstance().isFilterByPrefix()) {
                    // Use same prefix as sending to ensure accurate filtering
                    String ourPrefix = "[" + getFallbackServerPrefix() + "]";
                    if (authorName != null && authorName.startsWith(ourPrefix)) {
                        return;
                    }
                } else {
                    return;
                }
            }

            // Filter bots
            if (DiscordConfig.getInstance().isIgnoreBots() && isBot && !isWebhook) {
                return;
            }

            if (content.isEmpty()) {
                // Check for embeds (often used for cross-server events)
                if (!event.getMessage().getEmbeds().isEmpty()) {
                    // First, check for advancement embeds and process them specially
                    for (org.javacord.api.entity.message.embed.Embed embed : event.getMessage().getEmbeds()) {
                        if (advancementDetector.isAdvancementEmbed(embed)) {
                            processAdvancementEmbed(embed, event);
                            return;
                        }
                    }
                    
                    // Second, check for event embeds (join/leave/death)
                    for (org.javacord.api.entity.message.embed.Embed embed : event.getMessage().getEmbeds()) {
                        if (eventDetector.isEventEmbed(embed)) {
                            processEventEmbed(embed, event);
                            return;
                        }
                    }
                }
                return;
            }

            // Strip duplicate username prefix from message content
            // This handles webhooks that include "Username: " in the message
            String cleanedContent = content;
            if (content.startsWith(authorName + ": ")) {
                cleanedContent = content.substring(authorName.length() + 2);
            } else if (content.startsWith(authorName + " ")) {
                cleanedContent = content.substring(authorName.length() + 1);
            }

            String formattedMessage;
            if (isWebhook) {
                // Special formatting for cross-server messages (webhooks)
                String displayName = authorName;

                if (displayName.startsWith("[") && displayName.contains("]")) {
                    int endBracket = displayName.indexOf("]");
                    String serverPrefix = displayName.substring(0, endBracket + 1);
                    String remainingName = displayName.substring(endBracket + 1).trim();

                    if (remainingName.toLowerCase().contains("server")) {
                        // Event or generic server message: just prefix
                        displayName = "§a" + serverPrefix;
                        formattedMessage = displayName + " §f" + cleanedContent;
                    } else {
                        // Chat: [Prefix] Name
                        displayName = "§a" + serverPrefix + " §f" + remainingName;
                        formattedMessage = displayName + "§7: §f" + cleanedContent;
                    }
                } else {
                    // No bracket prefix found - treat as cross-server player message
                    displayName = "§a[Cross-Server] §f" + authorName;
                    formattedMessage = displayName + "§7: §f" + cleanedContent;
                }
            } else {
                // Standard Discord user message
                formattedMessage = DiscordConfig.getInstance().getDiscordToMinecraftFormat()
                        .replace("{username}", authorName)
                        .replace("{message}", cleanedContent);
            }

            if (server != null) {
                MutableComponent finalComponent = Component.empty();

                if (isWebhook) {
                    // Webhook logic (already established) using the pre-calculated formattedMessage
                    finalComponent.append(toMinecraftComponentWithLinks(formattedMessage));
                } else {
                    // Standard Discord message: Make [Discord] clickable
                    String inviteUrl = DiscordConfig.getInstance().getInviteUrl();
                    String rawFormat = DiscordConfig.getInstance().getDiscordToMinecraftFormat()
                            .replace("{username}", authorName)
                            .replace("{message}", cleanedContent);

                    if (rawFormat.contains("[Discord]") && inviteUrl != null && !inviteUrl.isEmpty()) {
                        String[] parts = rawFormat.split("\\[Discord\\]", 2);

                        // Part before [Discord]
                        if (parts.length > 0 && !parts[0].isEmpty()) {
                            finalComponent.append(toMinecraftComponentWithLinks(parts[0]));
                        }

                        // Clickable [Discord]
                        finalComponent.append(Component.literal("[Discord]")
                                .setStyle(Style.EMPTY
                                        .withColor(ChatFormatting.AQUA)
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, inviteUrl))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                Component.literal("Click to join our Discord!")))));

                        // Part after [Discord]
                        if (parts.length > 1 && !parts[1].isEmpty()) {
                            finalComponent.append(toMinecraftComponentWithLinks(parts[1]));
                        }
                    } else {
                        // Fallback if no tag or no invite URL
                        finalComponent.append(toMinecraftComponentWithLinks(rawFormat));
                    }
                }

                // Execute on server main thread for thread safety
                server.execute(() -> {
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        boolean isFilterableMessage = isBot || isWebhook;
                        if (isFilterableMessage && hasServerMessagesFiltered(player.getUUID())) {
                            continue;
                        }
                        player.sendSystemMessage(finalComponent);
                    }
                });
            }
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Error processing message", e);
        }
    }

    // ========= Public API for sending messages =========

    public void sendChatMessage(ServerPlayer player, String message) {
        if (!running) return;
        sendMinecraftMessage(player.getName().getString(), message);
    }

    public void sendPlayerJoin(ServerPlayer player) {
        if (!running || !DiscordConfig.getInstance().isSendJoin()) return;
        sendJoinEmbed(player.getName().getString());
    }

    public void sendPlayerLeave(ServerPlayer player) {
        if (!running || !DiscordConfig.getInstance().isSendLeave()) return;
        sendLeaveEmbed(player.getName().getString());
    }

    public void sendMinecraftMessage(String username, String message) {
        if (!running) {
            return;
        }

        String webhookUrl = DiscordConfig.getInstance().getWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty() || webhookUrl.contains("YOUR_WEBHOOK_URL")) {
            return;
        }

        String prefix = DiscordConfig.getInstance().getServerPrefix();
        String formattedUsername = DiscordConfig.getInstance().getWebhookUsernameFormat()
                .replace("{prefix}", prefix)
                .replace("{username}", username);

        String formattedMessage = DiscordConfig.getInstance().getMinecraftToDiscordFormat()
                .replace("{message}", message);

        String avatarUrl = DiscordConfig.getInstance().getAvatarUrl();
        if (!avatarUrl.isEmpty() && server != null) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(username);
            if (player != null) {
                String uuid = player.getUUID().toString().replace("-", "");
                avatarUrl = avatarUrl
                        .replace("{uuid}", uuid)
                        .replace("{username}", username);
            }
        }

        WebhookMessage webhookMessage = new WebhookMessage(
                webhookUrl,
                formattedMessage,
                formattedUsername,
                avatarUrl.isEmpty() ? null : avatarUrl);

        if (!messageQueue.offer(webhookMessage)) {
            VonixCore.LOGGER.warn("[Discord] Message queue full, dropping message");
        }
    }

    public void sendSystemMessage(String message) {
        if (!running || message == null || message.isEmpty()) {
            return;
        }

        if (message.startsWith("💀")) {
            sendEventEmbed(embed -> {
                embed.addProperty("title", "Player Died");
                embed.addProperty("description", message);
                embed.addProperty("color", 0xF04747);
                JsonObject footer = new JsonObject();
                footer.addProperty("text", "VonixCore · Death");
                embed.add("footer", footer);
            });
        } else {
            sendMinecraftMessage("Server", message);
        }
    }

    // ========= Event Embeds =========

    private String getEventWebhookUrl() {
        String eventWebhookUrl = DiscordConfig.getInstance().getEventWebhookUrl();
        if (eventWebhookUrl != null && !eventWebhookUrl.isEmpty() && !eventWebhookUrl.contains("YOUR_WEBHOOK_URL")) {
            return eventWebhookUrl;
        }
        return DiscordConfig.getInstance().getWebhookUrl();
    }

    public void sendStartupEmbed(String serverName) {
        sendEventEmbed(EmbedFactory.createServerStatusEmbed(
                "Server Online",
                "The server is now online.",
                0x43B581,
                serverName,
                "VonixCore · Startup"));
    }

    public void sendShutdownEmbed(String serverName) {
        sendEventEmbed(EmbedFactory.createServerStatusEmbed(
                "Server Shutting Down",
                "The server is shutting down...",
                0xF04747,
                serverName,
                "VonixCore · Shutdown"));
    }

    private String getPlayerAvatarUrl(String username) {
        String avatarUrl = DiscordConfig.getInstance().getAvatarUrl();
        if (avatarUrl == null || avatarUrl.isEmpty()) {
            return null;
        }

        if (server != null) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(username);
            if (player != null) {
                String uuid = player.getUUID().toString().replace("-", "");
                return avatarUrl
                        .replace("{uuid}", uuid)
                        .replace("{username}", username);
            }
        }

        return avatarUrl.replace("{username}", username);
    }

    public void sendJoinEmbed(String username) {
        if (!DiscordConfig.getInstance().isSendJoin()) {
            return;
        }
        String serverName = DiscordConfig.getInstance().getServerName();
        String thumbnailUrl = getPlayerAvatarUrl(username);
        sendEventEmbed(EmbedFactory.createPlayerEventEmbed(
                "Player Joined",
                "A player joined the server.",
                0x5865F2,
                username,
                serverName,
                "VonixCore · Join",
                thumbnailUrl));
    }

    public void sendLeaveEmbed(String username) {
        if (!DiscordConfig.getInstance().isSendLeave()) {
            return;
        }
        String serverName = DiscordConfig.getInstance().getServerName();
        String thumbnailUrl = getPlayerAvatarUrl(username);
        sendEventEmbed(EmbedFactory.createPlayerEventEmbed(
                "Player Left",
                "A player left the server.",
                0x99AAB5,
                username,
                serverName,
                "VonixCore · Leave",
                thumbnailUrl));
    }

    public void sendAdvancementEmbed(String username, String advancementTitle, String advancementDescription,
            String type) {
        if (!DiscordConfig.getInstance().isSendAdvancement()) {
            return;
        }
        sendEventEmbed(EmbedFactory.createAdvancementEmbed(
                "🏆",
                0xFAA61A,
                username,
                advancementTitle,
                advancementDescription));
    }

    public void updateBotStatus() {
        if (discordApi == null || !DiscordConfig.getInstance().isSetBotStatus()) {
            return;
        }

        try {
            if (server == null) {
                return;
            }

            int onlinePlayers = server.getPlayerList().getPlayerCount();
            int maxPlayers = server.getPlayerList().getMaxPlayers();

            String statusText = DiscordConfig.getInstance().getBotStatusFormat()
                    .replace("{online}", String.valueOf(onlinePlayers))
                    .replace("{max}", String.valueOf(maxPlayers));

            discordApi.updateActivity(ActivityType.PLAYING, statusText);
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Error updating bot status", e);
        }
    }

    // ========= Slash Commands =========

    private void registerListCommandAsync() {
        if (discordApi == null) {
            return;
        }

        discordApi.addSlashCommandCreateListener(event -> {
            SlashCommandInteraction interaction = event.getSlashCommandInteraction();
            if (interaction.getCommandName().equals("list")) {
                handleListCommand(interaction);
            }
        });

        SlashCommand.with("list", "Show online players")
                .createGlobal(discordApi)
                .whenComplete((cmd, error) -> {
                    if (error != null) {
                        VonixCore.LOGGER.error("[Discord] Failed to register /list command: {}", error.getMessage());
                    } else {
                        VonixCore.LOGGER.debug("[Discord] /list command registered");
                    }
                });
    }

    private EmbedBuilder buildPlayerListEmbed() {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        int onlinePlayers = players.size();
        int maxPlayers = server.getPlayerList().getMaxPlayers();

        String serverName = DiscordConfig.getInstance().getServerName();

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("📋 " + serverName)
                .setColor(Color.GREEN)
                .setFooter("VonixCore · Player List");

        if (onlinePlayers == 0) {
            embed.setDescription("No players are currently online.");
        } else {
            StringBuilder playerListBuilder = new StringBuilder();
            for (int i = 0; i < players.size(); i++) {
                if (i > 0)
                    playerListBuilder.append("\n");
                playerListBuilder.append("• ").append(players.get(i).getName().getString());
            }
            embed.addField("Players " + onlinePlayers + "/" + maxPlayers, playerListBuilder.toString(), false);
        }

        return embed;
    }

    private void handleListCommand(SlashCommandInteraction interaction) {
        try {
            if (server == null) {
                interaction.createImmediateResponder()
                        .setContent("❌ Server is not available")
                        .respond();
                return;
            }

            EmbedBuilder embed = buildPlayerListEmbed();
            interaction.createImmediateResponder()
                    .addEmbed(embed)
                    .respond();
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Error handling /list command", e);
            interaction.createImmediateResponder()
                    .setContent("❌ An error occurred")
                    .respond();
        }
    }

    private void handleTextListCommand(org.javacord.api.event.message.MessageCreateEvent event) {
        try {
            if (server == null) {
                return;
            }
            EmbedBuilder embed = buildPlayerListEmbed();
            event.getChannel().sendMessage(embed);
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Error handling !list command", e);
        }
    }

    private void registerLinkCommandsAsync() {
        if (discordApi == null || linkedAccountsManager == null) {
            return;
        }

        discordApi.addSlashCommandCreateListener(event -> {
            SlashCommandInteraction interaction = event.getSlashCommandInteraction();

            if (interaction.getCommandName().equals("link")) {
                String code = interaction.getArgumentStringValueByName("code").orElse("");
                String discordId = String.valueOf(interaction.getUser().getId());
                String discordUsername = interaction.getUser().getName();

                LinkedAccountsManager.LinkResult result = linkedAccountsManager.verifyAndLink(code, discordId,
                        discordUsername);

                interaction.createImmediateResponder()
                        .setContent((result.success() ? "✅ " : "❌ ") + result.message())
                        .respond();
            } else if (interaction.getCommandName().equals("unlink")) {
                String discordId = String.valueOf(interaction.getUser().getId());
                boolean success = linkedAccountsManager.unlinkDiscord(discordId);

                interaction.createImmediateResponder()
                        .setContent(success ? "✅ Your Minecraft account has been unlinked."
                                : "❌ You don't have a linked Minecraft account.")
                        .respond();
            }
        });

        SlashCommand.with("link", "Link your Minecraft account to Discord",
                java.util.Arrays.asList(
                        org.javacord.api.interaction.SlashCommandOption.create(
                                org.javacord.api.interaction.SlashCommandOptionType.STRING,
                                "code",
                                "The 6-digit code from /vonix discord link in-game",
                                true)))
                .createGlobal(discordApi)
                .whenComplete((cmd, error) -> {
                    if (error != null) {
                        VonixCore.LOGGER.error("[Discord] Failed to register /link command: {}", error.getMessage());
                    }
                });

        SlashCommand.with("unlink", "Unlink your Discord account from Minecraft")
                .createGlobal(discordApi)
                .whenComplete((cmd, error) -> {
                    if (error != null) {
                        VonixCore.LOGGER.error("[Discord] Failed to register /unlink command: {}", error.getMessage());
                    }
                });
    }

    // ========= Webhook Sending =========

    private void sendEventEmbed(java.util.function.Consumer<JsonObject> customize) {
        String webhookUrl = getEventWebhookUrl();
        sendWebhookEmbedToUrl(webhookUrl, customize);
    }

    private void sendWebhookEmbedToUrl(String webhookUrl, java.util.function.Consumer<JsonObject> customize) {
        if (webhookUrl == null || webhookUrl.isEmpty() || webhookUrl.contains("YOUR_WEBHOOK_URL")) {
            return;
        }

        // Execute entire embed send asynchronously to avoid blocking main thread
        VonixCore.executeAsync(() -> {
            JsonObject payload = new JsonObject();

            String prefix = DiscordConfig.getInstance().getServerPrefix();
            String serverName = DiscordConfig.getInstance().getServerName();
            String baseUsername = serverName == null ? "Server" : serverName;
            String formattedUsername = DiscordConfig.getInstance().getWebhookUsernameFormat()
                    .replace("{prefix}", prefix)
                    .replace("{username}", baseUsername);

            payload.addProperty("username", formattedUsername);

            String avatarUrl = DiscordConfig.getInstance().getServerAvatarUrl();
            if (avatarUrl != null && !avatarUrl.isEmpty()) {
                payload.addProperty("avatar_url", avatarUrl);
            }

            JsonObject embed = new JsonObject();
            customize.accept(embed);

            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            payload.add("embeds", embeds);

            RequestBody body = RequestBody.create(
                    payload.toString(),
                    MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(webhookUrl)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() && DiscordConfig.getInstance().isDebugLogging()) {
                    VonixCore.LOGGER.error("[Discord] Failed to send embed: {}", response.code());
                }
            } catch (IOException e) {
                VonixCore.LOGGER.error("[Discord] Error sending embed", e);
            }
        });
    }

    private Component toMinecraftComponentWithLinks(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }

        var matcher = DISCORD_MARKDOWN_LINK.matcher(text);
        MutableComponent result = Component.empty();
        int lastEnd = 0;
        boolean hasLink = false;

        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();

            if (start > lastEnd) {
                String before = text.substring(lastEnd, start);
                if (!before.isEmpty()) {
                    result.append(Component.literal(before));
                }
            }

            String label = matcher.group(1);
            String url = matcher.group(2);

            Component linkComponent = Component
                    .literal(label)
                    .withStyle(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                            .withUnderlined(true)
                            .withColor(ChatFormatting.AQUA));

            result.append(linkComponent);
            lastEnd = end;
            hasLink = true;
        }

        if (lastEnd < text.length()) {
            String tail = text.substring(lastEnd);
            if (!tail.isEmpty()) {
                result.append(Component.literal(tail));
            }
        }

        if (!hasLink) {
            return Component.literal(text);
        }

        return result;
    }

    private void startMessageQueueThread() {
        messageQueueThread = new Thread(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    WebhookMessage webhookMessage = messageQueue.poll(1, TimeUnit.SECONDS);
                    if (webhookMessage != null) {
                        sendWebhookMessage(webhookMessage);

                        int delay = DiscordConfig.getInstance().getRateLimitDelay();
                        if (delay > 0) {
                            Thread.sleep(delay);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    VonixCore.LOGGER.error("[Discord] Error processing message queue", e);
                }
            }
        }, "VonixCore-Discord-Queue");
        messageQueueThread.setDaemon(true);
        messageQueueThread.start();
    }

    private void sendWebhookMessage(WebhookMessage webhookMessage) {
        JsonObject json = new JsonObject();
        json.addProperty("content", webhookMessage.content);
        json.addProperty("username", webhookMessage.username);

        if (webhookMessage.avatarUrl != null) {
            json.addProperty("avatar_url", webhookMessage.avatarUrl);
        }

        RequestBody body = RequestBody.create(
                json.toString(),
                MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url(webhookMessage.webhookUrl)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() && DiscordConfig.getInstance().isDebugLogging()) {
                VonixCore.LOGGER.error("[Discord] Failed to send message: {}", response.code());
            }
        } catch (IOException e) {
            VonixCore.LOGGER.error("[Discord] Error sending message", e);
        }
    }

    /**
     * Processes advancement embeds by extracting data and converting to vanilla-style components.
     */
    private void processAdvancementEmbed(org.javacord.api.entity.message.embed.Embed embed,
                                        org.javacord.api.event.message.MessageCreateEvent event) {
        try {
            AdvancementData data = advancementExtractor.extractFromEmbed(embed);
            String serverPrefix = extractServerPrefixFromAuthor(event.getMessageAuthor().getDisplayName());
            MutableComponent advancementComponent = componentBuilder.buildAdvancementMessage(data, serverPrefix);
            
            if (server != null) {
                server.getPlayerList().broadcastSystemMessage(advancementComponent, false);
                return;
            }
        } catch (ExtractionException e) {
            VonixCore.LOGGER.warn("[Discord] Failed to extract advancement data: {}", e.getMessage());
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Error processing advancement embed", e);
        }
        
        handleAdvancementFallback(embed, event);
    }
    
    private void handleAdvancementFallback(org.javacord.api.entity.message.embed.Embed embed,
                                          org.javacord.api.event.message.MessageCreateEvent event) {
        try {
            String playerName = extractPlayerNameFallback(embed);
            String advancementTitle = extractAdvancementTitleFallback(embed);
            
            if (playerName != null && advancementTitle != null) {
                String serverPrefix = extractServerPrefixFromAuthor(event.getMessageAuthor().getDisplayName());
                MutableComponent fallbackComponent = componentBuilder.createFallbackComponent(
                        playerName, advancementTitle, serverPrefix);
                
                if (server != null && fallbackComponent != null) {
                    server.getPlayerList().broadcastSystemMessage(fallbackComponent, false);
                }
            }
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Fallback advancement processing failed", e);
        }
    }
    
    private String extractPlayerNameFallback(org.javacord.api.entity.message.embed.Embed embed) {
        if (embed == null) return null;
        
        for (org.javacord.api.entity.message.embed.EmbedField field : embed.getFields()) {
            String fieldName = field.getName();
            if (fieldName != null) {
                String lowerFieldName = fieldName.toLowerCase();
                if (lowerFieldName.contains("player") || lowerFieldName.contains("user") || lowerFieldName.contains("name")) {
                    String value = field.getValue();
                    if (value != null && !value.trim().isEmpty()) {
                        return value.trim();
                    }
                }
            }
        }
        
        if (embed.getAuthor().isPresent()) {
            String authorName = embed.getAuthor().get().getName();
            if (authorName != null && !authorName.trim().isEmpty()) {
                return authorName.trim();
            }
        }
        
        return null;
    }
    
    private String extractAdvancementTitleFallback(org.javacord.api.entity.message.embed.Embed embed) {
        if (embed == null) return null;
        
        for (org.javacord.api.entity.message.embed.EmbedField field : embed.getFields()) {
            String fieldName = field.getName();
            if (fieldName != null) {
                String lowerFieldName = fieldName.toLowerCase();
                if (lowerFieldName.contains("advancement") || lowerFieldName.contains("achievement") || 
                    lowerFieldName.contains("title")) {
                    String value = field.getValue();
                    if (value != null && !value.trim().isEmpty()) {
                        return value.trim();
                    }
                }
            }
        }
        
        if (embed.getTitle().isPresent()) {
            String title = embed.getTitle().get().trim();
            if (!title.isEmpty()) {
                return title;
            }
        }
        
        return null;
    }
    
    private String extractServerPrefixFromAuthor(String authorName) {
        if (authorName != null && authorName.startsWith("[") && authorName.contains("]")) {
            int endBracket = authorName.indexOf("]");
            String prefix = authorName.substring(1, endBracket).trim();
            if (!prefix.isEmpty()) {
                return prefix;
            }
        }
        return stripBracketsFromPrefix(getFallbackServerPrefix());
    }
    
    private String extractServerPrefixFromAuthorForEvents(String authorName) {
        if (authorName != null && authorName.startsWith("[") && authorName.contains("]")) {
            int endBracket = authorName.indexOf("]");
            String prefix = authorName.substring(1, endBracket).trim();
            if (!prefix.isEmpty()) {
                return prefix;
            }
        }
        return "Cross-Server";
    }
    
    private String stripBracketsFromPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return prefix;
        }
        String stripped = prefix.trim();
        if (stripped.startsWith("[")) {
            stripped = stripped.substring(1);
        }
        if (stripped.endsWith("]")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped.trim();
    }
    
    public String getFallbackServerPrefix() {
        if (serverPrefixConfig == null) {
            return stripBracketsFromPrefix(DiscordConfig.getInstance().getServerPrefix());
        }
        return serverPrefixConfig.getFallbackPrefix();
    }

    private void processEventEmbed(org.javacord.api.entity.message.embed.Embed embed,
                                  org.javacord.api.event.message.MessageCreateEvent event) {
        try {
            EventData data = eventExtractor.extractFromEmbed(embed);
            String serverPrefix = extractServerPrefixFromAuthorForEvents(event.getMessageAuthor().getDisplayName());
            MutableComponent eventComponent = componentBuilder.buildEventMessage(data, serverPrefix);
            if (server != null) {
                server.getPlayerList().broadcastSystemMessage(eventComponent, false);
                return;
            }
        } catch (ExtractionException e) {
            VonixCore.LOGGER.warn("[Discord] Failed to extract event data: {}", e.getMessage());
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Error processing event embed: {}", e.getMessage());
        }
        handleEventFallback(embed, event);
    }
    
    private void handleEventFallback(org.javacord.api.entity.message.embed.Embed embed,
                                    org.javacord.api.event.message.MessageCreateEvent event) {
        try {
            String playerName = null;
            for (org.javacord.api.entity.message.embed.EmbedField field : embed.getFields()) {
                String fn = field.getName().toLowerCase();
                if (fn.contains("player") || fn.contains("user")) {
                    playerName = field.getValue();
                    break;
                }
            }
            if (playerName == null && embed.getAuthor().isPresent()) {
                playerName = embed.getAuthor().get().getName();
            }
            
            String serverPrefix = extractServerPrefixFromAuthorForEvents(event.getMessageAuthor().getDisplayName());
            String action = "performed an action";
            EventEmbedDetector.EventType eventType = eventDetector.getEventType(embed);
            if (eventType != EventEmbedDetector.EventType.UNKNOWN) {
                action = eventType.getActionVerb();
            }
            
            if (playerName != null && server != null) {
                MutableComponent fallback = componentBuilder.createEventFallbackComponent(playerName, action, serverPrefix);
                server.getPlayerList().broadcastSystemMessage(fallback, false);
            }
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Event fallback failed: {}", e.getMessage());
        }
    }

    private static class WebhookMessage {
        final String webhookUrl;
        final String content;
        final String username;
        final String avatarUrl;

        WebhookMessage(String webhookUrl, String content, String username, String avatarUrl) {
            this.webhookUrl = webhookUrl;
            this.content = content;
            this.username = username;
            this.avatarUrl = avatarUrl;
        }
    }
}
