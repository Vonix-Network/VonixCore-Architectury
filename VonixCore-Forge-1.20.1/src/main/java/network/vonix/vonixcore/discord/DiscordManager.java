package network.vonix.vonixcore.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
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
        
        // Use default queue size if config is not available (e.g., in tests)
        int queueSize = 100; // default
        try {
            if (DiscordConfig.CONFIG != null && DiscordConfig.CONFIG.messageQueueSize != null) {
                queueSize = DiscordConfig.CONFIG.messageQueueSize.get();
            }
        } catch (Exception e) {
            // Config not available, use default
        }
        this.messageQueue = new LinkedBlockingQueue<>(queueSize);
        
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
        if (!DiscordConfig.CONFIG.enabled.get()) {
            VonixCore.LOGGER.info("[Discord] Discord integration is disabled in config");
            return;
        }

        this.server = server;
        String token = DiscordConfig.CONFIG.botToken.get();

        if (token == null || token.isEmpty() || token.equals("YOUR_BOT_TOKEN_HERE")) {
            VonixCore.LOGGER.warn("[Discord] Bot token not configured, Discord integration disabled");
            return;
        }

        VonixCore.LOGGER.info("[Discord] Starting Discord integration (Javacord + Webhooks)...");

        extractWebhookId();

        // Initialize player preferences
        try {
            playerPreferences = new PlayerPreferences(server.getServerDirectory().toPath().resolve("config"));
            VonixCore.LOGGER.info("[Discord] Player preferences system initialized");
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Failed to initialize player preferences", e);
        }

        // Initialize server prefix configuration
        try {
            serverPrefixConfig = new ServerPrefixConfig();
            // Set fallback prefix from existing config (strip brackets if present)
            String configPrefix = DiscordConfig.CONFIG.serverPrefix.get();
            if (configPrefix != null && !configPrefix.trim().isEmpty()) {
                String strippedPrefix = stripBracketsFromPrefix(configPrefix.trim());
                serverPrefixConfig.setFallbackPrefix(strippedPrefix);
            }
            VonixCore.LOGGER.info("[Discord] Server prefix configuration system initialized");
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Failed to initialize server prefix configuration", e);
            // Create a basic fallback configuration
            serverPrefixConfig = new ServerPrefixConfig();
        }

        running = true;
        startMessageQueueThread();

        // Initialize Javacord
        initializeJavacord(token);

        // Send startup embed
        String serverName = DiscordConfig.CONFIG.serverName.get();
        sendStartupEmbed(serverName);

        VonixCore.LOGGER.info("[Discord] Discord integration initialized successfully!");
        if (ourWebhookId != null) {
            VonixCore.LOGGER.info("[Discord] Chat Webhook ID: {}", ourWebhookId);
        }
    }

    public void shutdown() {
        if (!running) {
            return;
        }

        VonixCore.LOGGER.info("[Discord] Shutting down Discord integration...");
        running = false;

        // 1. Send shutdown embed
        try {
            sendShutdownEmbed(DiscordConfig.CONFIG.serverName.get());
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Failed to send shutdown embed", e);
        }

        // 2. Stop message queue thread
        if (messageQueueThread != null) {
            messageQueueThread.interrupt();
            try {
                messageQueueThread.join(2000);
            } catch (InterruptedException e) {
                // Ignore
            }
        }

        // 3. Disconnect Javacord
        if (discordApi != null) {
            try {
                // Remove listeners first to stop processing new events
                discordApi.getListeners().keySet().forEach(listener -> discordApi.removeListener(listener));

                // Disconnect with longer timeout for cleaner shutdown
                try {
                    discordApi.disconnect().get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    VonixCore.LOGGER.warn("[Discord] Javacord disconnect timeout, forcing shutdown");
                }

                // Force shutdown Javacord's internal thread pool
                // This helps prevent "Central ExecutorService" leaks
                if (discordApi.getThreadPool() != null) {
                    try {
                        // Try graceful shutdown first
                        discordApi.getThreadPool().getExecutorService().shutdown();
                        discordApi.getThreadPool().getScheduler().shutdown();
                        
                        // Wait a bit for graceful shutdown
                        if (!discordApi.getThreadPool().getExecutorService().awaitTermination(3, TimeUnit.SECONDS)) {
                            discordApi.getThreadPool().getExecutorService().shutdownNow();
                        }
                        if (!discordApi.getThreadPool().getScheduler().awaitTermination(3, TimeUnit.SECONDS)) {
                            discordApi.getThreadPool().getScheduler().shutdownNow();
                        }
                    } catch (Exception e) {
                        // Force shutdown if graceful fails
                        try {
                            discordApi.getThreadPool().getExecutorService().shutdownNow();
                            discordApi.getThreadPool().getScheduler().shutdownNow();
                        } catch (Exception ignored) {
                            // Ignore if already shutdown
                        }
                    }
                }
                VonixCore.LOGGER.info("[Discord] Javacord disconnected and thread pools shut down");
            } catch (Throwable e) {
                // Catch Throwable to handle NoClassDefFoundError during shutdown
                VonixCore.LOGGER.debug("[Discord] Javacord disconnect failed (likely shutdown race condition): {}",
                        e.getMessage());
            } finally {
                discordApi = null;
            }
        }

        if (httpClient != null) {
            try {
                httpClient.dispatcher().executorService().shutdown();
                httpClient.connectionPool().evictAll();
                if (!httpClient.dispatcher().executorService().awaitTermination(3, TimeUnit.SECONDS)) {
                    httpClient.dispatcher().executorService().shutdownNow();
                }
            } catch (Throwable e) {
                // Ignore errors during http client shutdown
            }
        }

        VonixCore.LOGGER.info("[Discord] Discord integration shut down");
    }

    public boolean isRunning() {
        return running;
    }

    // ========= Account Linking =========

    public String generateLinkCode(ServerPlayer player) {
        if (linkedAccountsManager == null || !DiscordConfig.CONFIG.enableAccountLinking.get()) {
            return null;
        }
        return linkedAccountsManager.generateLinkCode(player.getUUID(), player.getName().getString());
    }

    public boolean unlinkAccount(UUID uuid) {
        if (linkedAccountsManager == null || !DiscordConfig.CONFIG.enableAccountLinking.get()) {
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
                DiscordConfig.CONFIG.webhookId.get(),
                DiscordConfig.CONFIG.webhookUrl.get(),
                "chat");
        eventWebhookId = extractWebhookIdFromConfig(
                "",
                DiscordConfig.CONFIG.eventWebhookUrl.get(),
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
            String channelId = DiscordConfig.CONFIG.channelId.get();
            if (channelId == null || channelId.equals("YOUR_CHANNEL_ID_HERE")) {
                VonixCore.LOGGER.warn("[Discord] Channel ID not configured - Discord integration will be limited");
                return;
            }

            VonixCore.LOGGER.info("[Discord] Connecting to Discord (async)...");

            // Enhanced error handling for Discord API connection
            try {
                new DiscordApiBuilder()
                        .setToken(botToken)
                        .setIntents(Intent.GUILD_MESSAGES, Intent.MESSAGE_CONTENT)
                        .login()
                        .orTimeout(15, TimeUnit.SECONDS)
                        .whenComplete((api, error) -> {
                            if (error != null) {
                                // Enhanced error logging with more context
                                VonixCore.LOGGER.error("[Discord] Failed to connect to Discord API. " +
                                        "Error type: {} | Error message: {} | Token length: {} | " +
                                        "This will disable Discord message processing but server will continue normally.", 
                                        error.getClass().getSimpleName(), error.getMessage(), 
                                        botToken != null ? botToken.length() : 0, error);
                                
                                // Ensure discordApi is null to prevent further API calls
                                discordApi = null;
                                return;
                            }
                            
                            try {
                                discordApi = api;
                                onJavacordConnected(channelId);
                            } catch (Exception postConnectionError) {
                                VonixCore.LOGGER.error("[Discord] Error during post-connection setup. " +
                                        "Error: {} | Channel ID: {} | API status: {} | " +
                                        "Discord integration may be partially functional.", 
                                        postConnectionError.getMessage(), channelId, 
                                        (api != null ? "connected" : "null"), postConnectionError);
                                
                                // Don't null the API here as basic connection succeeded
                                // Just log the error and continue with limited functionality
                            }
                        });
            } catch (Exception builderError) {
                VonixCore.LOGGER.error("[Discord] Failed to create Discord API builder. " +
                        "Error: {} | Error type: {} | Token configured: {} | " +
                        "This indicates a configuration or dependency issue.", 
                        builderError.getMessage(), builderError.getClass().getSimpleName(),
                        (botToken != null && !botToken.isEmpty()), builderError);
                discordApi = null;
            }
            
        } catch (Exception configError) {
            VonixCore.LOGGER.error("[Discord] Failed to read Discord configuration. " +
                    "Error: {} | Error type: {} | " +
                    "Check your Discord configuration settings.", 
                    configError.getMessage(), configError.getClass().getSimpleName(), configError);
            discordApi = null;
        } catch (Throwable criticalError) {
            // Catch Throwable to handle any critical system errors
            VonixCore.LOGGER.error("[Discord] Critical error during Javacord initialization. " +
                    "Error: {} | Error type: {} | " +
                    "Discord integration will be completely disabled.", 
                    criticalError.getMessage(), criticalError.getClass().getSimpleName(), criticalError);
            discordApi = null;
        }
    }

    /**
     * Called after Javacord successfully connects to Discord.
     * Runs asynchronously to avoid blocking server startup.
     * Enhanced with comprehensive error handling for all initialization steps.
     */
    private void onJavacordConnected(String channelId) {
        try {
            VonixCore.LOGGER.info("[Discord] Connected as: {}", discordApi.getYourself().getName());

            // Enhanced error handling for channel ID parsing
            long channelIdLong;
            try {
                channelIdLong = Long.parseLong(channelId);
            } catch (NumberFormatException e) {
                VonixCore.LOGGER.error("[Discord] Invalid channel ID format: '{}'. " +
                        "Channel ID must be a valid Discord channel ID number. " +
                        "Message processing will be disabled.", channelId, e);
                return;
            }

            // Enhanced error handling for event channel ID parsing
            String eventChannelIdStr = DiscordConfig.CONFIG.eventChannelId.get();
            Long eventChannelIdLong = null;
            if (eventChannelIdStr != null && !eventChannelIdStr.isEmpty()) {
                try {
                    eventChannelIdLong = Long.parseLong(eventChannelIdStr);
                } catch (NumberFormatException e) {
                    VonixCore.LOGGER.warn("[Discord] Invalid event channel ID format: '{}'. " +
                            "Event channel processing will be disabled but main channel will work.", 
                            eventChannelIdStr, e);
                    // Continue with null event channel - this is not critical
                }
            }

            // Enhanced error handling for message listener registration
            final Long finalEventChannelId = eventChannelIdLong;
            try {
                discordApi.addMessageCreateListener(event -> {
                    // Wrap the entire message processing in try-catch to prevent
                    // any single message from crashing the listener
                    try {
                        long msgChannelId = event.getChannel().getId();
                        if (msgChannelId == channelIdLong ||
                                (finalEventChannelId != null && msgChannelId == finalEventChannelId)) {
                            processJavacordMessage(msgChannelId, event);
                        }
                    } catch (Exception messageProcessingError) {
                        // Enhanced error logging with message context
                        VonixCore.LOGGER.error("[Discord] Error processing individual message. " +
                                "Error: {} | Error type: {} | Channel: {} | Author: {} | " +
                                "Message processing will continue for other messages.", 
                                messageProcessingError.getMessage(), 
                                messageProcessingError.getClass().getSimpleName(),
                                event.getChannel().getId(),
                                event.getMessageAuthor().getDisplayName(), messageProcessingError);
                        
                        // Don't rethrow - this ensures one bad message doesn't break the entire listener
                    } catch (Throwable criticalMessageError) {
                        // Catch Throwable for critical errors that might not be Exceptions
                        VonixCore.LOGGER.error("[Discord] Critical error processing message. " +
                                "Error: {} | Error type: {} | Channel: {} | " +
                                "This indicates a serious system issue but processing will continue.", 
                                criticalMessageError.getMessage(), 
                                criticalMessageError.getClass().getSimpleName(),
                                event.getChannel().getId(), criticalMessageError);
                    }
                });
                VonixCore.LOGGER.info("[Discord] Message listener registered successfully for channels: {} and {}", 
                        channelIdLong, finalEventChannelId);
            } catch (Exception listenerError) {
                VonixCore.LOGGER.error("[Discord] Failed to register message listener. " +
                        "Error: {} | Error type: {} | Main channel: {} | Event channel: {} | " +
                        "Discord message processing will not work.", 
                        listenerError.getMessage(), listenerError.getClass().getSimpleName(),
                        channelIdLong, finalEventChannelId, listenerError);
                // Continue with other initialization steps even if listener fails
            }

            // Enhanced error handling for account linking initialization
            if (DiscordConfig.CONFIG.enableAccountLinking.get()) {
                try {
                    linkedAccountsManager = new LinkedAccountsManager(
                            server.getServerDirectory().toPath().resolve("config"));
                    VonixCore.LOGGER.info("[Discord] Account linking initialized successfully ({} accounts)",
                            linkedAccountsManager.getLinkedCount());
                } catch (Exception linkingError) {
                    VonixCore.LOGGER.error("[Discord] Failed to initialize account linking. " +
                            "Error: {} | Error type: {} | Config path: {} | " +
                            "Account linking features will be disabled.", 
                            linkingError.getMessage(), linkingError.getClass().getSimpleName(),
                            server != null ? server.getServerDirectory().toPath().resolve("config") : "unknown", 
                            linkingError);
                    linkedAccountsManager = null; // Ensure it's null so other code can check
                }
            }

            // Enhanced error handling for slash command registration
            try {
                registerListCommandAsync();
                VonixCore.LOGGER.debug("[Discord] List command registration initiated");
            } catch (Exception listCommandError) {
                VonixCore.LOGGER.error("[Discord] Failed to initiate list command registration. " +
                        "Error: {} | Error type: {} | " +
                        "/list command will not be available.", 
                        listCommandError.getMessage(), listCommandError.getClass().getSimpleName(), 
                        listCommandError);
            }

            if (DiscordConfig.CONFIG.enableAccountLinking.get() && linkedAccountsManager != null) {
                try {
                    registerLinkCommandsAsync();
                    VonixCore.LOGGER.debug("[Discord] Link commands registration initiated");
                } catch (Exception linkCommandError) {
                    VonixCore.LOGGER.error("[Discord] Failed to initiate link commands registration. " +
                            "Error: {} | Error type: {} | " +
                            "/link and /unlink commands will not be available.", 
                            linkCommandError.getMessage(), linkCommandError.getClass().getSimpleName(), 
                            linkCommandError);
                }
            }

            // Enhanced error handling for bot status update
            try {
                updateBotStatus();
                VonixCore.LOGGER.debug("[Discord] Bot status updated successfully");
            } catch (Exception statusError) {
                VonixCore.LOGGER.error("[Discord] Failed to update bot status. " +
                        "Error: {} | Error type: {} | " +
                        "Bot status will not show player count but other features will work.", 
                        statusError.getMessage(), statusError.getClass().getSimpleName(), statusError);
            }

        } catch (Exception generalError) {
            VonixCore.LOGGER.error("[Discord] Error during post-connection setup. " +
                    "Error: {} | Error type: {} | Channel ID: {} | " +
                    "Some Discord features may not work properly.", 
                    generalError.getMessage(), generalError.getClass().getSimpleName(), 
                    channelId, generalError);
        } catch (Throwable criticalError) {
            VonixCore.LOGGER.error("[Discord] Critical error during post-connection setup. " +
                    "Error: {} | Error type: {} | Channel ID: {} | " +
                    "Discord integration may be severely impacted.", 
                    criticalError.getMessage(), criticalError.getClass().getSimpleName(), 
                    channelId, criticalError);
        }
    }

    private void processJavacordMessage(long channelId, org.javacord.api.event.message.MessageCreateEvent event) {
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
            if (DiscordConfig.CONFIG.ignoreWebhooks.get() && isWebhook) {
                if (DiscordConfig.CONFIG.filterByPrefix.get()) {
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
            if (DiscordConfig.CONFIG.ignoreBots.get() && isBot && !isWebhook) {
                return;
            }

            boolean isEvent = false;
            if (content.isEmpty()) {
                // Check for embeds (often used for cross-server events)
                if (!event.getMessage().getEmbeds().isEmpty()) {
                    // First, check for advancement embeds and process them specially
                    for (org.javacord.api.entity.message.embed.Embed embed : event.getMessage().getEmbeds()) {
                        if (advancementDetector.isAdvancementEmbed(embed)) {
                            processAdvancementEmbed(embed, event);
                            return; // Skip normal embed processing for advancement embeds
                        }
                    }
                    
                    // Second, check for event embeds (join/leave/death) and process them specially
                    for (org.javacord.api.entity.message.embed.Embed embed : event.getMessage().getEmbeds()) {
                        if (eventDetector.isEventEmbed(embed)) {
                            processEventEmbed(embed, event);
                            return; // Skip normal embed processing for event embeds
                        }
                    }
                    
                    // Continue with normal embed processing if no special embeds found
                    org.javacord.api.entity.message.embed.Embed embed = event.getMessage().getEmbeds().get(0);
                    isEvent = true;

                    StringBuilder embedContent = new StringBuilder();

                    // Add Author if present
                    if (embed.getAuthor().isPresent()) {
                        embedContent.append(embed.getAuthor().get().getName()).append(" ");
                    }

                    // Add Title if present - but skip if it's just a generic event header
                    if (embed.getTitle().isPresent()) {
                        String title = embed.getTitle().get();
                        String strippedTitle = title.replaceAll("[^a-zA-Z ]", "").trim();
                        if (!strippedTitle.equalsIgnoreCase("Player Joined") &&
                                !strippedTitle.equalsIgnoreCase("Player Left") &&
                                !strippedTitle.equalsIgnoreCase("Advancement Made") &&
                                !strippedTitle.equalsIgnoreCase("Player Died")) {
                            embedContent.append(title).append(" ");
                        }
                    }

                    // Add Description with smart replacement
                    String description = embed.getDescription().orElse("");

                    // Parse Fields (where names are often hidden)
                    for (org.javacord.api.entity.message.embed.EmbedField field : embed.getFields()) {
                        String fieldName = field.getName();
                        String fieldValue = field.getValue();

                        if (fieldName.equalsIgnoreCase("Player") || fieldName.equalsIgnoreCase("User")) {
                            // Replace "A player" or "a player" with the actual name
                            if (description.toLowerCase().contains("a player")) {
                                description = description.replaceAll("(?i)A player", fieldValue);
                            } else if (!description.contains(fieldValue)) {
                                embedContent.append(fieldValue).append(" ");
                            }
                        } else if (!fieldName.equalsIgnoreCase("Server") && !fieldName.equalsIgnoreCase("Message")) {
                            embedContent.append("[").append(fieldName).append(": ").append(fieldValue).append("] ");
                        }
                    }

                    embedContent.append(description);

                    content = embedContent.toString().trim();
                    if (content.isEmpty()) {
                        return;
                    }
                } else {
                    return;
                }
            }

            // Strip duplicate username prefix from message content
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

                // Identify channel type
                String eventChanId = DiscordConfig.CONFIG.eventChannelId.get();
                boolean isEventChannel = !eventChanId.isEmpty() && String.valueOf(channelId).equals(eventChanId);

                if (displayName.startsWith("[") && displayName.contains("]")) {
                    int endBracket = displayName.indexOf("]");
                    String serverPrefix = displayName.substring(0, endBracket + 1);
                    String remainingName = displayName.substring(endBracket + 1).trim();

                    if (isEventChannel || isEvent) {
                        // Event: Just [Prefix] (Name is in message)
                        displayName = "§a" + serverPrefix;
                        formattedMessage = displayName + " §f" + cleanedContent;
                    } else {
                        // Chat: [Prefix] Name
                        // If remainingName is "Otherworld Server", we should probably use a better name
                        // but let's assume it's the player name if it doesn't contain "Server"
                        if (remainingName.toLowerCase().contains("server")) {
                            // If it's just "Server", use the content's implied name or just prefix
                            displayName = "§a" + serverPrefix;
                            formattedMessage = displayName + " §f" + cleanedContent;
                        } else {
                            displayName = "§a" + serverPrefix + " §f" + remainingName;
                            formattedMessage = displayName + "§7: §f" + cleanedContent;
                        }
                    }
                } else {
                    // No bracket prefix found - treat as cross-server player message
                    // Format with green [Cross-Server] prefix for consistency
                    displayName = "§a[Cross-Server] §f" + authorName;
                    formattedMessage = displayName + "§7: §f" + cleanedContent;
                }
            } else {
                // Standard Discord user message
                formattedMessage = DiscordConfig.CONFIG.discordToMinecraftFormat.get()
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
                    String inviteUrl = DiscordConfig.CONFIG.inviteUrl.get();
                    String rawFormat = DiscordConfig.CONFIG.discordToMinecraftFormat.get()
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
                                        .withColor(TextColor.parseColor("aqua")) // §b
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

                // Broadcast
                server.getPlayerList().broadcastSystemMessage(finalComponent, false);
            }
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Error processing message", e);
        }
    }

    /**
     * Processes advancement embeds by extracting data and converting to vanilla-style components.
     * This method handles the complete advancement processing pipeline: detection, extraction,
     * component generation, and chat system integration with comprehensive fallback behavior.
     * 
     * @param embed The Discord embed containing advancement information
     * @param event The message event for context (server, channel, etc.)
     */
    private void processAdvancementEmbed(org.javacord.api.entity.message.embed.Embed embed, 
                                       org.javacord.api.event.message.MessageCreateEvent event) {
        String contextInfo = buildErrorContext(embed, event);
        long startTime = System.currentTimeMillis();
        
        // Enhanced error logging with comprehensive context
        if (DiscordConfig.CONFIG.debugLogging.get()) {
            VonixCore.LOGGER.debug("[Discord] Starting advancement embed processing {}", contextInfo);
        }
        
        try {
            // Extract advancement data from the embed
            AdvancementData data = advancementExtractor.extractFromEmbed(embed);
            
            // Log successful extraction in debug mode
            if (DiscordConfig.CONFIG.debugLogging.get()) {
                VonixCore.LOGGER.debug("[Discord] Successfully extracted advancement data: player='{}', title='{}', type='{}' {}", 
                        data.getPlayerName(), data.getAdvancementTitle(), data.getType(), contextInfo);
            }
            
            // Determine server prefix for this message by extracting from webhook author name
            // This ensures cross-server advancement messages display the correct source server prefix
            String serverPrefix = extractServerPrefixFromAuthor(event.getMessageAuthor().getDisplayName());
            
            try {
                // Generate vanilla-style advancement component
                MutableComponent advancementComponent = componentBuilder.buildAdvancementMessage(data, serverPrefix);
                
                // Log successful component generation in debug mode
                if (DiscordConfig.CONFIG.debugLogging.get()) {
                    VonixCore.LOGGER.debug("[Discord] Successfully generated advancement component with prefix '{}' {}", 
                            serverPrefix, contextInfo);
                }
                
                try {
                    // Send to Minecraft chat system
                    if (server != null) {
                        server.getPlayerList().broadcastSystemMessage(advancementComponent, false);
                        
                        long processingTime = System.currentTimeMillis() - startTime;
                        if (DiscordConfig.CONFIG.debugLogging.get()) {
                            VonixCore.LOGGER.info("[Discord] Successfully processed advancement embed: {} earned '{}' ({}ms) {}", 
                                    data.getPlayerName(), data.getAdvancementTitle(), processingTime, contextInfo);
                        }
                        return; // Success - exit early
                    } else {
                        VonixCore.LOGGER.error("[Discord] Cannot send advancement message - Minecraft server instance is null. " +
                                "This indicates a critical system state issue. {}", contextInfo);
                        // Fall through to fallback processing
                    }
                } catch (Exception chatError) {
                    VonixCore.LOGGER.error("[Discord] Failed to send advancement message to chat system. " +
                            "Error: {} | Player: {} | Advancement: {} | Server: {} {}", 
                            chatError.getMessage(), data.getPlayerName(), data.getAdvancementTitle(), 
                            (server != null ? "available" : "null"), contextInfo, chatError);
                    // Fall through to fallback processing
                }
            } catch (Exception componentError) {
                VonixCore.LOGGER.error("[Discord] Failed to generate advancement component. " +
                        "Error: {} | Player: {} | Advancement: {} | Type: {} | Prefix: '{}' {}", 
                        componentError.getMessage(), data.getPlayerName(), data.getAdvancementTitle(), 
                        data.getType(), serverPrefix, contextInfo, componentError);
                // Fall through to fallback processing
            }
            
        } catch (ExtractionException e) {
            VonixCore.LOGGER.error("[Discord] Failed to extract advancement data from embed. " +
                    "Error: {} | Available fields: {} | Title: {} | Description: {} {}", 
                    e.getMessage(), embed.getFields().size(), 
                    embed.getTitle().orElse("none"), embed.getDescription().orElse("none"), contextInfo);
            // Fall through to fallback processing
            
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Unexpected error during advancement processing. " +
                    "Error: {} | Error type: {} | Embed fields: {} {}", 
                    e.getMessage(), e.getClass().getSimpleName(), embed.getFields().size(), contextInfo, e);
            // Fall through to fallback processing
        }
        
        // Comprehensive fallback processing - try multiple fallback strategies
        long fallbackStartTime = System.currentTimeMillis();
        VonixCore.LOGGER.warn("[Discord] Initiating fallback processing for advancement embed {}", contextInfo);
        handleAdvancementFallback(embed, event, contextInfo, fallbackStartTime);
    }
    
    /**
     * Handles fallback processing for advancement embeds when primary processing fails.
     * Implements multiple fallback strategies with graceful degradation and comprehensive error logging.
     * 
     * @param embed The Discord embed that failed to process
     * @param event The message event for context
     * @param contextInfo Context information for logging
     * @param fallbackStartTime The timestamp when fallback processing started
     */
    private void handleAdvancementFallback(org.javacord.api.entity.message.embed.Embed embed,
                                         org.javacord.api.event.message.MessageCreateEvent event,
                                         String contextInfo,
                                         long fallbackStartTime) {
        
        int strategyAttempted = 0;
        
        // Strategy 1: Try to create a basic advancement message from available embed data
        strategyAttempted++;
        if (DiscordConfig.CONFIG.debugLogging.get()) {
            VonixCore.LOGGER.debug("[Discord] Attempting fallback strategy {}: basic advancement message {}", 
                    strategyAttempted, contextInfo);
        }
        
        try {
            String playerName = extractPlayerNameFallback(embed);
            String advancementTitle = extractAdvancementTitleFallback(embed);
            
            if (playerName != null && advancementTitle != null) {
                String serverPrefix = extractServerPrefixFromAuthor(event.getMessageAuthor().getDisplayName());
                
                MutableComponent fallbackComponent = componentBuilder.createFallbackComponent(
                        playerName, advancementTitle, serverPrefix);
                
                if (server != null && fallbackComponent != null) {
                    server.getPlayerList().broadcastSystemMessage(fallbackComponent, false);
                    long totalTime = System.currentTimeMillis() - fallbackStartTime;
                    VonixCore.LOGGER.info("[Discord] Successfully used basic fallback processing for advancement embed. " +
                            "Player: {} | Title: {} | Time: {}ms {}", 
                            playerName, advancementTitle, totalTime, contextInfo);
                    return; // Success - exit early
                } else {
                    VonixCore.LOGGER.warn("[Discord] Basic fallback failed - server or component is null. " +
                            "Server: {} | Component: {} {}", 
                            (server != null ? "available" : "null"), 
                            (fallbackComponent != null ? "created" : "null"), contextInfo);
                }
            } else {
                VonixCore.LOGGER.warn("[Discord] Basic fallback failed - could not extract required data. " +
                        "Player: {} | Title: {} {}", 
                        (playerName != null ? "'" + playerName + "'" : "null"), 
                        (advancementTitle != null ? "'" + advancementTitle + "'" : "null"), contextInfo);
            }
        } catch (Exception fallbackError) {
            VonixCore.LOGGER.error("[Discord] Basic fallback advancement processing failed. " +
                    "Error: {} | Error type: {} {}", 
                    fallbackError.getMessage(), fallbackError.getClass().getSimpleName(), contextInfo, fallbackError);
        }
        
        // Strategy 2: Fall back to original Discord embed display (Requirement 6.3)
        strategyAttempted++;
        if (DiscordConfig.CONFIG.debugLogging.get()) {
            VonixCore.LOGGER.debug("[Discord] Attempting fallback strategy {}: original embed display {}", 
                    strategyAttempted, contextInfo);
        }
        
        try {
            MutableComponent originalEmbedComponent = convertEmbedToMinecraftComponent(embed, event);
            
            if (server != null && originalEmbedComponent != null) {
                server.getPlayerList().broadcastSystemMessage(originalEmbedComponent, false);
                long totalTime = System.currentTimeMillis() - fallbackStartTime;
                VonixCore.LOGGER.info("[Discord] Successfully fell back to original embed display for advancement. " +
                        "Time: {}ms | Embed title: {} | Fields: {} {}", 
                        totalTime, embed.getTitle().orElse("none"), embed.getFields().size(), contextInfo);
                return; // Success - exit early
            } else {
                VonixCore.LOGGER.warn("[Discord] Original embed fallback failed - server or component is null. " +
                        "Server: {} | Component: {} {}", 
                        (server != null ? "available" : "null"), 
                        (originalEmbedComponent != null ? "created" : "null"), contextInfo);
            }
        } catch (Exception embedFallbackError) {
            VonixCore.LOGGER.error("[Discord] Original embed fallback processing failed. " +
                    "Error: {} | Error type: {} | Embed title: {} | Fields: {} {}", 
                    embedFallbackError.getMessage(), embedFallbackError.getClass().getSimpleName(),
                    embed.getTitle().orElse("none"), embed.getFields().size(), contextInfo, embedFallbackError);
        }
        
        // Strategy 3: Last resort - create a minimal error message
        strategyAttempted++;
        if (DiscordConfig.CONFIG.debugLogging.get()) {
            VonixCore.LOGGER.debug("[Discord] Attempting fallback strategy {}: minimal error message {}", 
                    strategyAttempted, contextInfo);
        }
        
        try {
            String serverPrefix = extractServerPrefixFromAuthor(event.getMessageAuthor().getDisplayName());
            
            MutableComponent errorComponent = createMinimalErrorComponent(serverPrefix, contextInfo);
            
            if (server != null && errorComponent != null) {
                server.getPlayerList().broadcastSystemMessage(errorComponent, false);
                long totalTime = System.currentTimeMillis() - fallbackStartTime;
                VonixCore.LOGGER.warn("[Discord] Used minimal error message for advancement embed. " +
                        "Time: {}ms | Server prefix: '{}' {}", 
                        totalTime, serverPrefix, contextInfo);
                return; // Success - exit early
            } else {
                VonixCore.LOGGER.error("[Discord] Minimal error message creation failed - server or component is null. " +
                        "Server: {} | Component: {} {}", 
                        (server != null ? "available" : "null"), 
                        (errorComponent != null ? "created" : "null"), contextInfo);
            }
        } catch (Exception errorFallbackError) {
            VonixCore.LOGGER.error("[Discord] Even minimal error message creation failed. " +
                    "Error: {} | Error type: {} {}", 
                    errorFallbackError.getMessage(), errorFallbackError.getClass().getSimpleName(), 
                    contextInfo, errorFallbackError);
        }
        
        // If all fallback strategies fail, log critical error with comprehensive information
        long totalTime = System.currentTimeMillis() - fallbackStartTime;
        VonixCore.LOGGER.error("[Discord] CRITICAL: All {} fallback strategies failed for advancement embed. " +
                "Message will be lost. Time spent: {}ms | System state: server={} | " +
                "Embed info: title='{}', fields={}, description='{}' {}", 
                strategyAttempted, totalTime, (server != null ? "available" : "null"),
                embed.getTitle().orElse("none"), embed.getFields().size(), 
                embed.getDescription().orElse("none"), contextInfo);
        
        // Log system stability information with enhanced monitoring
        try {
            Runtime runtime = Runtime.getRuntime();
            long freeMemory = runtime.freeMemory();
            long totalMemory = runtime.totalMemory();
            long maxMemory = runtime.maxMemory();
            long usedMemory = totalMemory - freeMemory;
            
            // Calculate memory usage percentages
            double usedPercentage = (double) usedMemory / totalMemory * 100;
            double totalPercentage = (double) totalMemory / maxMemory * 100;
            
            VonixCore.LOGGER.error("[Discord] System stability check - Memory: {}MB used ({}%) / {}MB total ({}% of max {}MB) | " +
                    "Free: {}MB | Threads: {} | Processors: {} {}", 
                    usedMemory / 1024 / 1024, String.format("%.1f", usedPercentage),
                    totalMemory / 1024 / 1024, String.format("%.1f", totalPercentage), maxMemory / 1024 / 1024,
                    freeMemory / 1024 / 1024, Thread.activeCount(), runtime.availableProcessors(), contextInfo);
            
            // Check for potential memory issues
            if (usedPercentage > 90) {
                VonixCore.LOGGER.warn("[Discord] HIGH MEMORY USAGE WARNING: {}% memory used. This may affect advancement processing stability.", 
                        String.format("%.1f", usedPercentage));
            }
            
            // Check for thread count issues
            int threadCount = Thread.activeCount();
            if (threadCount > 100) {
                VonixCore.LOGGER.warn("[Discord] HIGH THREAD COUNT WARNING: {} active threads. This may indicate resource leaks.", threadCount);
            }
            
        } catch (Exception memoryCheckError) {
            VonixCore.LOGGER.error("[Discord] Could not perform system stability check: {} | Error type: {} {}", 
                    memoryCheckError.getMessage(), memoryCheckError.getClass().getSimpleName(), contextInfo);
        }
    }
    
    /**
     * Converts a Discord embed to a Minecraft component using the original embed display logic.
     * This provides fallback to the original Discord embed format when advancement processing fails.
     * Enhanced with better error handling and validation to ensure system stability.
     * 
     * @param embed The Discord embed to convert
     * @param event The message event for context
     * @return A MutableComponent representing the original embed, or null if conversion fails
     */
    private MutableComponent convertEmbedToMinecraftComponent(org.javacord.api.entity.message.embed.Embed embed,
                                                            org.javacord.api.event.message.MessageCreateEvent event) {
        try {
            if (embed == null) {
                VonixCore.LOGGER.warn("[Discord] Cannot convert null embed to Minecraft component");
                return null;
            }
            
            StringBuilder embedContent = new StringBuilder();
            
            // Add Author if present
            if (embed.getAuthor().isPresent()) {
                String authorName = embed.getAuthor().get().getName();
                if (authorName != null && !authorName.trim().isEmpty()) {
                    embedContent.append(authorName.trim()).append(" ");
                }
            }
            
            // Add Title if present
            if (embed.getTitle().isPresent()) {
                String title = embed.getTitle().get();
                if (title != null && !title.trim().isEmpty()) {
                    embedContent.append(title.trim()).append(" ");
                }
            }
            
            // Add Description
            String description = embed.getDescription().orElse("");
            
            // Parse Fields with enhanced validation
            for (org.javacord.api.entity.message.embed.EmbedField field : embed.getFields()) {
                try {
                    String fieldName = field.getName();
                    String fieldValue = field.getValue();
                    
                    if (fieldName != null && fieldValue != null && 
                        !fieldName.trim().isEmpty() && !fieldValue.trim().isEmpty()) {
                        embedContent.append("[").append(fieldName.trim()).append(": ")
                                   .append(fieldValue.trim()).append("] ");
                    }
                } catch (Exception fieldError) {
                    VonixCore.LOGGER.warn("[Discord] Error processing embed field: {}", fieldError.getMessage());
                    // Continue processing other fields
                }
            }
            
            if (description != null && !description.trim().isEmpty()) {
                embedContent.append(description.trim());
            }
            
            String content = embedContent.toString().trim();
            if (content.isEmpty()) {
                VonixCore.LOGGER.warn("[Discord] Embed content is empty after processing - cannot create component");
                return null;
            }
            
            // Get server prefix and format as webhook message with enhanced error handling
            org.javacord.api.entity.server.Server discordServer = event.getServer().orElse(null);
            String serverPrefix = getServerPrefix(discordServer);
            String authorName = event.getMessageAuthor().getDisplayName();
            
            String formattedMessage;
            if (authorName != null && authorName.startsWith("[") && authorName.contains("]")) {
                try {
                    int endBracket = authorName.indexOf("]");
                    String prefix = authorName.substring(0, endBracket + 1);
                    formattedMessage = "§a" + prefix + " §f" + content;
                } catch (Exception prefixError) {
                    VonixCore.LOGGER.warn("[Discord] Error parsing author prefix, using fallback: {}", prefixError.getMessage());
                    formattedMessage = "§a[" + serverPrefix + "] §f" + content;
                }
            } else {
                formattedMessage = "§a[" + serverPrefix + "] §f" + content;
            }
            
            Component component = toMinecraftComponentWithLinks(formattedMessage);
            return component instanceof MutableComponent ? (MutableComponent) component : Component.empty().append(component);
            
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Error converting embed to Minecraft component. " +
                    "Error: {} | Error type: {} | Embed title: {} | Fields: {}", 
                    e.getMessage(), e.getClass().getSimpleName(),
                    embed != null ? embed.getTitle().orElse("none") : "null",
                    embed != null ? embed.getFields().size() : "unknown", e);
            return null;
        }
    }
    
    /**
     * Creates a minimal error component when all other fallback strategies fail.
     * Enhanced with better error handling and more informative messaging.
     * 
     * @param serverPrefix The server prefix for context
     * @param contextInfo Context information for the error
     * @return A minimal error component, or null if creation fails
     */
    private MutableComponent createMinimalErrorComponent(String serverPrefix, String contextInfo) {
        try {
            MutableComponent errorComponent = Component.empty();
            
            // Add server prefix if available with validation
            if (serverPrefix != null && !serverPrefix.trim().isEmpty()) {
                try {
                    errorComponent.append(Component.literal("[" + serverPrefix.trim() + "] ")
                            .withStyle(ChatFormatting.GRAY));
                } catch (Exception prefixError) {
                    VonixCore.LOGGER.warn("[Discord] Error adding server prefix to minimal error component: {}", 
                            prefixError.getMessage());
                    // Continue without prefix
                }
            }
            
            // Add error message with enhanced information
            try {
                errorComponent.append(Component.literal("⚠ An advancement was earned but could not be displayed properly")
                        .withStyle(ChatFormatting.YELLOW));
                
                // Add additional context in debug mode
                if (DiscordConfig.CONFIG.debugLogging.get()) {
                    errorComponent.append(Component.literal(" (Check logs for details)")
                            .withStyle(ChatFormatting.GRAY));
                }
            } catch (Exception messageError) {
                VonixCore.LOGGER.error("[Discord] Error creating error message text: {}", messageError.getMessage());
                // Try to create a very basic message
                try {
                    return Component.literal("⚠ Advancement processing error").withStyle(ChatFormatting.RED);
                } catch (Exception basicError) {
                    VonixCore.LOGGER.error("[Discord] Cannot create even basic error message: {}", basicError.getMessage());
                    return null;
                }
            }
            
            return errorComponent;
            
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Failed to create minimal error component. " +
                    "Error: {} | Error type: {} | Server prefix: '{}' {}", 
                    e.getMessage(), e.getClass().getSimpleName(), serverPrefix, contextInfo, e);
            return null;
        }
    }
    
    /**
     * Builds context information for error logging to help with debugging.
     * Enhanced with additional system state information and better error handling.
     * 
     * @param embed The Discord embed being processed
     * @param event The message event for additional context
     * @return A string containing context information for logging
     */
    private String buildErrorContext(org.javacord.api.entity.message.embed.Embed embed,
                                   org.javacord.api.event.message.MessageCreateEvent event) {
        try {
            StringBuilder context = new StringBuilder();
            context.append("[Context: ");
            
            // Add server information with null safety
            try {
                if (event.getServer().isPresent()) {
                    context.append("Server=").append(event.getServer().get().getName()).append(", ");
                } else {
                    context.append("Server=DM/Unknown, ");
                }
            } catch (Exception serverError) {
                context.append("Server=Error, ");
            }
            
            // Add channel information with error handling
            try {
                context.append("Channel=").append(event.getChannel().getId()).append(", ");
            } catch (Exception channelError) {
                context.append("Channel=Error, ");
            }
            
            // Add message author information
            try {
                String authorName = event.getMessageAuthor().getDisplayName();
                context.append("Author=").append(authorName != null ? authorName : "Unknown").append(", ");
            } catch (Exception authorError) {
                context.append("Author=Error, ");
            }
            
            // Add embed information with comprehensive error handling
            if (embed != null) {
                try {
                    if (embed.getTitle().isPresent()) {
                        String title = embed.getTitle().get();
                        // Truncate very long titles for logging
                        if (title.length() > 50) {
                            title = title.substring(0, 47) + "...";
                        }
                        context.append("EmbedTitle='").append(title).append("', ");
                    } else {
                        context.append("EmbedTitle=None, ");
                    }
                } catch (Exception titleError) {
                    context.append("EmbedTitle=Error, ");
                }
                
                try {
                    context.append("FieldCount=").append(embed.getFields().size()).append(", ");
                } catch (Exception fieldError) {
                    context.append("FieldCount=Error, ");
                }
                
                try {
                    if (embed.getFooter().isPresent()) {
                        String footerText = embed.getFooter().get().getText().orElse("No text");
                        if (footerText.length() > 30) {
                            footerText = footerText.substring(0, 27) + "...";
                        }
                        context.append("Footer='").append(footerText).append("', ");
                    } else {
                        context.append("Footer=None, ");
                    }
                } catch (Exception footerError) {
                    context.append("Footer=Error, ");
                }
            } else {
                context.append("Embed=null, ");
            }
            
            // Add timestamp for debugging
            context.append("Timestamp=").append(System.currentTimeMillis());
            context.append("]");
            
            return context.toString();
            
        } catch (Exception e) {
            // If context building itself fails, return a minimal context with the error
            return "[Context: Error building context - " + e.getClass().getSimpleName() + ": " + e.getMessage() + "]";
        }
    }
    
    /**
     * Attempts to extract player name from embed as fallback when normal extraction fails.
     * Enhanced with more comprehensive field name matching and better error handling.
     * 
     * @param embed The Discord embed to extract from
     * @return The player name if found, null otherwise
     */
    private String extractPlayerNameFallback(org.javacord.api.entity.message.embed.Embed embed) {
        if (embed == null) {
            return null;
        }
        
        try {
            // Try to find player name in embed fields with comprehensive field name matching
            for (org.javacord.api.entity.message.embed.EmbedField field : embed.getFields()) {
                try {
                    String fieldName = field.getName();
                    if (fieldName != null) {
                        String lowerFieldName = fieldName.toLowerCase();
                        // Check for various field names that might contain player information
                        if (lowerFieldName.contains("player") || lowerFieldName.contains("user") || 
                            lowerFieldName.contains("name") || lowerFieldName.equals("who")) {
                            String value = field.getValue();
                            if (value != null && !value.trim().isEmpty()) {
                                return value.trim();
                            }
                        }
                    }
                } catch (Exception fieldError) {
                    // Continue processing other fields if one fails
                    continue;
                }
            }
            
            // Try embed author as fallback
            if (embed.getAuthor().isPresent()) {
                try {
                    String authorName = embed.getAuthor().get().getName();
                    if (authorName != null && !authorName.trim().isEmpty()) {
                        return authorName.trim();
                    }
                } catch (Exception authorError) {
                    // Continue to next fallback
                }
            }
            
            // Try to extract from embed description as last resort
            if (embed.getDescription().isPresent()) {
                try {
                    String description = embed.getDescription().get();
                    if (description != null && !description.trim().isEmpty()) {
                        // Look for patterns like "PlayerName has made" or "PlayerName earned"
                        String[] words = description.split("\\s+");
                        if (words.length > 0 && !words[0].toLowerCase().equals("a") && 
                            !words[0].toLowerCase().equals("the") && !words[0].toLowerCase().equals("someone")) {
                            return words[0].trim();
                        }
                    }
                } catch (Exception descError) {
                    // Final fallback failed
                }
            }
            
        } catch (Exception e) {
            VonixCore.LOGGER.debug("[Discord] Error in player name fallback extraction: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Attempts to extract advancement title from embed as fallback when normal extraction fails.
     * Enhanced with better pattern matching and error handling.
     * 
     * @param embed The Discord embed to extract from
     * @return The advancement title if found, null otherwise
     */
    private String extractAdvancementTitleFallback(org.javacord.api.entity.message.embed.Embed embed) {
        if (embed == null) {
            return null;
        }
        
        try {
            // Try to find advancement title in embed fields with comprehensive matching
            for (org.javacord.api.entity.message.embed.EmbedField field : embed.getFields()) {
                try {
                    String fieldName = field.getName();
                    if (fieldName != null) {
                        String lowerFieldName = fieldName.toLowerCase();
                        // Check for various field names that might contain advancement information
                        if (lowerFieldName.contains("advancement") || lowerFieldName.contains("achievement") || 
                            lowerFieldName.contains("title") || lowerFieldName.contains("name") ||
                            lowerFieldName.equals("what") || lowerFieldName.contains("earned")) {
                            String value = field.getValue();
                            if (value != null && !value.trim().isEmpty()) {
                                return value.trim();
                            }
                        }
                    }
                } catch (Exception fieldError) {
                    // Continue processing other fields if one fails
                    continue;
                }
            }
            
            // Try embed title as fallback (but skip generic titles)
            if (embed.getTitle().isPresent()) {
                try {
                    String title = embed.getTitle().get().trim();
                    if (!title.isEmpty() && !isGenericAdvancementTitle(title)) {
                        return title;
                    }
                } catch (Exception titleError) {
                    // Continue to next fallback
                }
            }
            
            // Try to extract from embed description as last resort
            if (embed.getDescription().isPresent()) {
                try {
                    String description = embed.getDescription().get();
                    if (description != null && !description.trim().isEmpty()) {
                        // Look for patterns like "earned [Title]" or "made [Title]"
                        if (description.contains("[") && description.contains("]")) {
                            int start = description.indexOf("[");
                            int end = description.indexOf("]", start);
                            if (end > start) {
                                String extracted = description.substring(start + 1, end).trim();
                                if (!extracted.isEmpty()) {
                                    return extracted;
                                }
                            }
                        }
                    }
                } catch (Exception descError) {
                    // Final fallback failed
                }
            }
            
        } catch (Exception e) {
            VonixCore.LOGGER.debug("[Discord] Error in advancement title fallback extraction: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Checks if a title is a generic advancement type title that shouldn't be used
     * as the actual advancement title.
     * 
     * @param title The title to check
     * @return true if the title is generic, false otherwise
     */
    private boolean isGenericAdvancementTitle(String title) {
        String lowerTitle = title.toLowerCase();
        return lowerTitle.equals("advancement made") || 
               lowerTitle.equals("goal reached") || 
               lowerTitle.equals("challenge complete") ||
               lowerTitle.equals("advancement") ||
               lowerTitle.equals("goal") ||
               lowerTitle.equals("challenge");
    }

    /**
     * Processes event embeds (join/leave/death) by extracting data and converting to simplified format.
     * Format: [ServerPrefix] PlayerName action (e.g., "[MCSurvival] Steve joined")
     * 
     * @param embed The Discord embed containing event information
     * @param event The message event for context
     */
    private void processEventEmbed(org.javacord.api.entity.message.embed.Embed embed, 
                                  org.javacord.api.event.message.MessageCreateEvent event) {
        String contextInfo = buildErrorContext(embed, event);
        
        if (DiscordConfig.CONFIG.debugLogging.get()) {
            VonixCore.LOGGER.debug("[Discord] Starting event embed processing {}", contextInfo);
        }
        
        try {
            // Extract event data from the embed
            EventData data = eventExtractor.extractFromEmbed(embed);
            
            if (DiscordConfig.CONFIG.debugLogging.get()) {
                VonixCore.LOGGER.debug("[Discord] Successfully extracted event data: player='{}', type='{}' {}", 
                        data.getPlayerName(), data.getEventType(), contextInfo);
            }
            
            // Determine server prefix from webhook author name
            // For events, use "Cross-Server" as fallback since we can't determine source
            String serverPrefix = extractServerPrefixFromAuthorForEvents(event.getMessageAuthor().getDisplayName());
            
            try {
                // Generate simplified event component
                MutableComponent eventComponent = componentBuilder.buildEventMessage(data, serverPrefix);
                
                if (server != null) {
                    server.getPlayerList().broadcastSystemMessage(eventComponent, false);
                    
                    if (DiscordConfig.CONFIG.debugLogging.get()) {
                        VonixCore.LOGGER.info("[Discord] Successfully processed event embed: {} {} {}", 
                                data.getPlayerName(), data.getActionString(), contextInfo);
                    }
                    return;
                } else {
                    VonixCore.LOGGER.error("[Discord] Cannot send event message - server instance is null {}", contextInfo);
                }
            } catch (Exception componentError) {
                VonixCore.LOGGER.error("[Discord] Failed to generate event component. Error: {} | Player: {} | Type: {} {}", 
                        componentError.getMessage(), data.getPlayerName(), data.getEventType(), contextInfo);
            }
            
        } catch (ExtractionException e) {
            VonixCore.LOGGER.warn("[Discord] Failed to extract event data from embed: {} {}", e.getMessage(), contextInfo);
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Unexpected error during event processing: {} {}", e.getMessage(), contextInfo, e);
        }
        
        // Fallback: try to create a basic event message
        handleEventFallback(embed, event, contextInfo);
    }
    
    /**
     * Handles fallback processing for event embeds when primary processing fails.
     */
    private void handleEventFallback(org.javacord.api.entity.message.embed.Embed embed,
                                    org.javacord.api.event.message.MessageCreateEvent event,
                                    String contextInfo) {
        try {
            String playerName = extractPlayerNameFallback(embed);
            String serverPrefix = extractServerPrefixFromAuthorForEvents(event.getMessageAuthor().getDisplayName());
            
            // Determine action from embed
            String action = "performed an action";
            EventEmbedDetector.EventType eventType = eventDetector.getEventType(embed);
            if (eventType != EventEmbedDetector.EventType.UNKNOWN) {
                action = eventType.getActionVerb();
            }
            
            if (playerName != null) {
                MutableComponent fallbackComponent = componentBuilder.createEventFallbackComponent(
                        playerName, action, serverPrefix);
                
                if (server != null && fallbackComponent != null) {
                    server.getPlayerList().broadcastSystemMessage(fallbackComponent, false);
                    VonixCore.LOGGER.info("[Discord] Used fallback processing for event embed: {} {} {}", 
                            playerName, action, contextInfo);
                    return;
                }
            }
            
            // Ultimate fallback: convert embed to basic format
            MutableComponent originalComponent = convertEmbedToMinecraftComponent(embed, event);
            if (server != null && originalComponent != null) {
                server.getPlayerList().broadcastSystemMessage(originalComponent, false);
                VonixCore.LOGGER.info("[Discord] Used original embed display for event fallback {}", contextInfo);
            }
            
        } catch (Exception fallbackError) {
            VonixCore.LOGGER.error("[Discord] Event fallback processing failed: {} {}", 
                    fallbackError.getMessage(), contextInfo);
        }
    }

    // ========= Minecraft → Discord =========

    public void sendMinecraftMessage(String username, String message) {
        if (!running) {
            return;
        }

        String webhookUrl = DiscordConfig.CONFIG.webhookUrl.get();
        if (webhookUrl == null || webhookUrl.isEmpty() || webhookUrl.contains("YOUR_WEBHOOK_URL")) {
            return;
        }

        String prefix = DiscordConfig.CONFIG.serverPrefix.get();
        String formattedUsername = DiscordConfig.CONFIG.webhookUsernameFormat.get()
                .replace("{prefix}", prefix)
                .replace("{username}", username);

        String formattedMessage = DiscordConfig.CONFIG.minecraftToDiscordFormat.get()
                .replace("{message}", message);

        String avatarUrl = DiscordConfig.CONFIG.avatarUrl.get();
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
        String eventWebhookUrl = DiscordConfig.CONFIG.eventWebhookUrl.get();
        if (eventWebhookUrl != null && !eventWebhookUrl.isEmpty()) {
            return eventWebhookUrl;
        }
        return DiscordConfig.CONFIG.webhookUrl.get();
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
        String avatarUrl = DiscordConfig.CONFIG.avatarUrl.get();
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
        if (!DiscordConfig.CONFIG.sendJoin.get()) {
            return;
        }
        String serverName = DiscordConfig.CONFIG.serverName.get();
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
        if (!DiscordConfig.CONFIG.sendLeave.get()) {
            return;
        }
        String serverName = DiscordConfig.CONFIG.serverName.get();
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
        if (!DiscordConfig.CONFIG.sendAdvancement.get()) {
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
        if (discordApi == null || !DiscordConfig.CONFIG.setBotStatus.get()) {
            return;
        }

        try {
            if (server == null) {
                return;
            }

            int onlinePlayers = server.getPlayerList().getPlayerCount();
            int maxPlayers = server.getPlayerList().getMaxPlayers();

            String statusText = DiscordConfig.CONFIG.botStatusFormat.get()
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

        // Add listener first (doesn't require command to exist)
        discordApi.addSlashCommandCreateListener(event -> {
            SlashCommandInteraction interaction = event.getSlashCommandInteraction();
            if (interaction.getCommandName().equals("list")) {
                handleListCommand(interaction);
            }
        });

        // Register command asynchronously - don't block with .join()
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

        String serverName = DiscordConfig.CONFIG.serverName.get();

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

        // Add listener first (doesn't require commands to exist)
        discordApi.addSlashCommandCreateListener(event -> {
            SlashCommandInteraction interaction = event.getSlashCommandInteraction();

            if (interaction.getCommandName().equals("link")) {
                String code = interaction.getArgumentStringValueByName("code").orElse("");
                String discordId = String.valueOf(interaction.getUser().getId());
                String discordUsername = interaction.getUser().getName();

                LinkedAccountsManager.LinkResult result = linkedAccountsManager.verifyAndLink(code, discordId,
                        discordUsername);

                interaction.createImmediateResponder()
                        .setContent((result.success ? "✅ " : "❌ ") + result.message)
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

        // Register commands asynchronously - don't block with .join()
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
            if (DiscordConfig.CONFIG.debugLogging.get()) {
                VonixCore.LOGGER.debug("[Discord] Webhook URL not configured or invalid, skipping embed send");
            }
            return;
        }

        // Execute entire embed send asynchronously to avoid blocking main thread
        VonixCore.executeAsync(() -> {
            try {
                // Enhanced error handling for JSON payload creation
                JsonObject payload;
                try {
                    payload = new JsonObject();

                    String prefix = DiscordConfig.CONFIG.serverPrefix.get();
                    String serverName = DiscordConfig.CONFIG.serverName.get();
                    String baseUsername = serverName == null ? "Server" : serverName;
                    String formattedUsername = DiscordConfig.CONFIG.webhookUsernameFormat.get()
                            .replace("{prefix}", prefix)
                            .replace("{username}", baseUsername);

                    payload.addProperty("username", formattedUsername);

                    String avatarUrl = DiscordConfig.CONFIG.serverAvatarUrl.get();
                    if (avatarUrl != null && !avatarUrl.isEmpty()) {
                        payload.addProperty("avatar_url", avatarUrl);
                    }
                } catch (Exception payloadError) {
                    VonixCore.LOGGER.error("[Discord] Failed to create webhook payload base structure. " +
                            "Error: {} | Error type: {} | Webhook URL: {} | " +
                            "Embed will not be sent.", 
                            payloadError.getMessage(), payloadError.getClass().getSimpleName(),
                            webhookUrl.length() > 50 ? webhookUrl.substring(0, 50) + "..." : webhookUrl, 
                            payloadError);
                    return;
                }

                // Enhanced error handling for embed customization
                JsonObject embed;
                try {
                    embed = new JsonObject();
                    customize.accept(embed);
                } catch (Exception customizeError) {
                    VonixCore.LOGGER.error("[Discord] Failed to customize embed content. " +
                            "Error: {} | Error type: {} | Webhook URL: {} | " +
                            "Embed customization failed, will attempt to send basic embed.", 
                            customizeError.getMessage(), customizeError.getClass().getSimpleName(),
                            webhookUrl.length() > 50 ? webhookUrl.substring(0, 50) + "..." : webhookUrl, 
                            customizeError);
                    
                    // Create a basic error embed as fallback
                    embed = new JsonObject();
                    embed.addProperty("title", "⚠ Embed Processing Error");
                    embed.addProperty("description", "An error occurred while processing this embed");
                    embed.addProperty("color", 0xF04747); // Red color for error
                }

                // Enhanced error handling for embed array creation
                try {
                    JsonArray embeds = new JsonArray();
                    embeds.add(embed);
                    payload.add("embeds", embeds);
                } catch (Exception embedArrayError) {
                    VonixCore.LOGGER.error("[Discord] Failed to create embed array. " +
                            "Error: {} | Error type: {} | " +
                            "This indicates a JSON processing issue.", 
                            embedArrayError.getMessage(), embedArrayError.getClass().getSimpleName(), 
                            embedArrayError);
                    return;
                }

                // Enhanced error handling for HTTP request creation and execution
                try {
                    RequestBody body = RequestBody.create(
                            payload.toString(),
                            MediaType.parse("application/json"));

                    Request request = new Request.Builder()
                            .url(webhookUrl)
                            .post(body)
                            .build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            // Enhanced error logging with response details
                            String responseBody = "";
                            try {
                                if (response.body() != null) {
                                    responseBody = response.body().string();
                                    if (responseBody.length() > 200) {
                                        responseBody = responseBody.substring(0, 200) + "...";
                                    }
                                }
                            } catch (Exception bodyReadError) {
                                responseBody = "Could not read response body: " + bodyReadError.getMessage();
                            }

                            VonixCore.LOGGER.error("[Discord] Failed to send embed to webhook. " +
                                    "HTTP Status: {} | Response: {} | Webhook URL: {} | " +
                                    "This may indicate webhook configuration issues or Discord API problems.", 
                                    response.code(), responseBody,
                                    webhookUrl.length() > 50 ? webhookUrl.substring(0, 50) + "..." : webhookUrl);
                        } else if (DiscordConfig.CONFIG.debugLogging.get()) {
                            VonixCore.LOGGER.debug("[Discord] Successfully sent embed to webhook (HTTP {})", response.code());
                        }
                    }
                } catch (IOException httpError) {
                    VonixCore.LOGGER.error("[Discord] Network error sending embed to webhook. " +
                            "Error: {} | Error type: {} | Webhook URL: {} | " +
                            "This may indicate network connectivity issues.", 
                            httpError.getMessage(), httpError.getClass().getSimpleName(),
                            webhookUrl.length() > 50 ? webhookUrl.substring(0, 50) + "..." : webhookUrl, 
                            httpError);
                } catch (Exception requestError) {
                    VonixCore.LOGGER.error("[Discord] Error creating or executing HTTP request for webhook. " +
                            "Error: {} | Error type: {} | Webhook URL: {} | " +
                            "This indicates an HTTP client configuration issue.", 
                            requestError.getMessage(), requestError.getClass().getSimpleName(),
                            webhookUrl.length() > 50 ? webhookUrl.substring(0, 50) + "..." : webhookUrl, 
                            requestError);
                }

            } catch (Exception generalError) {
                VonixCore.LOGGER.error("[Discord] Unexpected error during webhook embed sending. " +
                        "Error: {} | Error type: {} | Webhook URL: {} | " +
                        "This indicates a system-level issue.", 
                        generalError.getMessage(), generalError.getClass().getSimpleName(),
                        webhookUrl != null && webhookUrl.length() > 50 ? webhookUrl.substring(0, 50) + "..." : "null", 
                        generalError);
            } catch (Throwable criticalError) {
                VonixCore.LOGGER.error("[Discord] Critical error during webhook embed sending. " +
                        "Error: {} | Error type: {} | " +
                        "This indicates a severe system issue that needs immediate attention.", 
                        criticalError.getMessage(), criticalError.getClass().getSimpleName(), criticalError);
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
            VonixCore.LOGGER.info("[Discord] Message queue thread started");
            
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    WebhookMessage webhookMessage = messageQueue.poll(1, TimeUnit.SECONDS);
                    if (webhookMessage != null) {
                        try {
                            sendWebhookMessage(webhookMessage);
                            
                            // Enhanced error handling for rate limiting delay
                            int delay = DiscordConfig.CONFIG.rateLimitDelay.get();
                            if (delay > 0) {
                                try {
                                    Thread.sleep(delay);
                                } catch (InterruptedException sleepInterrupted) {
                                    VonixCore.LOGGER.debug("[Discord] Rate limit delay interrupted, shutting down message queue");
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                        } catch (Exception messageError) {
                            VonixCore.LOGGER.error("[Discord] Error processing individual webhook message from queue. " +
                                    "Error: {} | Error type: {} | Content: {} | Username: {} | " +
                                    "Queue processing will continue with next message.", 
                                    messageError.getMessage(), messageError.getClass().getSimpleName(),
                                    webhookMessage.content != null && webhookMessage.content.length() > 50 
                                        ? webhookMessage.content.substring(0, 50) + "..." 
                                        : webhookMessage.content,
                                    webhookMessage.username, messageError);
                            
                            // Don't rethrow - continue processing other messages
                        } catch (Throwable criticalMessageError) {
                            VonixCore.LOGGER.error("[Discord] Critical error processing webhook message from queue. " +
                                    "Error: {} | Error type: {} | Content: {} | " +
                                    "This indicates a severe issue but queue processing will continue.", 
                                    criticalMessageError.getMessage(), criticalMessageError.getClass().getSimpleName(),
                                    webhookMessage.content != null && webhookMessage.content.length() > 50 
                                        ? webhookMessage.content.substring(0, 50) + "..." 
                                        : webhookMessage.content, criticalMessageError);
                        }
                    }
                } catch (InterruptedException e) {
                    VonixCore.LOGGER.info("[Discord] Message queue thread interrupted, shutting down gracefully");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception queueError) {
                    VonixCore.LOGGER.error("[Discord] Error in message queue processing loop. " +
                            "Error: {} | Error type: {} | Queue size: {} | " +
                            "Queue processing will continue but this indicates a system issue.", 
                            queueError.getMessage(), queueError.getClass().getSimpleName(),
                            messageQueue.size(), queueError);
                    
                    // Add a small delay to prevent rapid error loops
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException delayInterrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } catch (Throwable criticalQueueError) {
                    VonixCore.LOGGER.error("[Discord] Critical error in message queue thread. " +
                            "Error: {} | Error type: {} | " +
                            "This is a severe system issue that may require restart.", 
                            criticalQueueError.getMessage(), criticalQueueError.getClass().getSimpleName(), 
                            criticalQueueError);
                    
                    // For critical errors, add a longer delay and consider stopping
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException delayInterrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            
            VonixCore.LOGGER.info("[Discord] Message queue thread stopped. Final queue size: {}", messageQueue.size());
            
            // Log any remaining messages in the queue for debugging
            if (!messageQueue.isEmpty() && DiscordConfig.CONFIG.debugLogging.get()) {
                VonixCore.LOGGER.debug("[Discord] {} messages remaining in queue at shutdown", messageQueue.size());
            }
        }, "VonixCore-Discord-Queue");
        
        // Enhanced error handling for thread configuration
        try {
            messageQueueThread.setDaemon(true);
            messageQueueThread.setUncaughtExceptionHandler((thread, exception) -> {
                VonixCore.LOGGER.error("[Discord] Uncaught exception in message queue thread. " +
                        "Thread: {} | Error: {} | Error type: {} | " +
                        "This indicates a critical system failure in Discord message processing.", 
                        thread.getName(), exception.getMessage(), exception.getClass().getSimpleName(), exception);
                
                // Attempt to restart the message queue thread if it crashes
                if (running) {
                    VonixCore.LOGGER.warn("[Discord] Attempting to restart message queue thread after crash");
                    try {
                        startMessageQueueThread();
                    } catch (Exception restartError) {
                        VonixCore.LOGGER.error("[Discord] Failed to restart message queue thread: {}", 
                                restartError.getMessage(), restartError);
                    }
                }
            });
            
            messageQueueThread.start();
            VonixCore.LOGGER.debug("[Discord] Message queue thread configured and started successfully");
            
        } catch (Exception threadError) {
            VonixCore.LOGGER.error("[Discord] Failed to configure or start message queue thread. " +
                    "Error: {} | Error type: {} | " +
                    "Discord message sending will not work.", 
                    threadError.getMessage(), threadError.getClass().getSimpleName(), threadError);
        }
    }

    private void sendWebhookMessage(WebhookMessage webhookMessage) {
        if (webhookMessage == null) {
            VonixCore.LOGGER.warn("[Discord] Cannot send null webhook message");
            return;
        }

        try {
            // Enhanced error handling for JSON payload creation
            JsonObject json;
            try {
                json = new JsonObject();
                
                // Validate and add content with null safety
                if (webhookMessage.content != null) {
                    json.addProperty("content", webhookMessage.content);
                } else {
                    VonixCore.LOGGER.warn("[Discord] Webhook message content is null, using empty string");
                    json.addProperty("content", "");
                }
                
                // Validate and add username with null safety
                if (webhookMessage.username != null && !webhookMessage.username.trim().isEmpty()) {
                    json.addProperty("username", webhookMessage.username);
                } else {
                    VonixCore.LOGGER.warn("[Discord] Webhook username is null or empty, using default");
                    json.addProperty("username", "Server");
                }

                // Add avatar URL if provided and valid
                if (webhookMessage.avatarUrl != null && !webhookMessage.avatarUrl.trim().isEmpty()) {
                    try {
                        json.addProperty("avatar_url", webhookMessage.avatarUrl);
                    } catch (Exception avatarError) {
                        VonixCore.LOGGER.warn("[Discord] Error adding avatar URL to webhook message: {} | " +
                                "Avatar URL: {} | Message will be sent without avatar.", 
                                avatarError.getMessage(), webhookMessage.avatarUrl);
                    }
                }
            } catch (Exception jsonError) {
                VonixCore.LOGGER.error("[Discord] Failed to create webhook message JSON payload. " +
                        "Error: {} | Error type: {} | Content length: {} | Username: {} | " +
                        "Message will not be sent.", 
                        jsonError.getMessage(), jsonError.getClass().getSimpleName(),
                        webhookMessage.content != null ? webhookMessage.content.length() : 0,
                        webhookMessage.username, jsonError);
                return;
            }

            // Enhanced error handling for HTTP request creation and execution
            try {
                // Validate webhook URL
                if (webhookMessage.webhookUrl == null || webhookMessage.webhookUrl.trim().isEmpty()) {
                    VonixCore.LOGGER.error("[Discord] Webhook URL is null or empty, cannot send message. " +
                            "Content: {} | Username: {}", 
                            webhookMessage.content != null && webhookMessage.content.length() > 50 
                                ? webhookMessage.content.substring(0, 50) + "..." 
                                : webhookMessage.content,
                            webhookMessage.username);
                    return;
                }

                RequestBody body = RequestBody.create(
                        json.toString(),
                        MediaType.parse("application/json"));

                Request request = new Request.Builder()
                        .url(webhookMessage.webhookUrl)
                        .post(body)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        // Enhanced error logging with response details
                        String responseBody = "";
                        try {
                            if (response.body() != null) {
                                responseBody = response.body().string();
                                if (responseBody.length() > 200) {
                                    responseBody = responseBody.substring(0, 200) + "...";
                                }
                            }
                        } catch (Exception bodyReadError) {
                            responseBody = "Could not read response body: " + bodyReadError.getMessage();
                        }

                        VonixCore.LOGGER.error("[Discord] Failed to send webhook message. " +
                                "HTTP Status: {} | Response: {} | Content: {} | Username: {} | " +
                                "Webhook URL: {} | This may indicate webhook configuration issues.", 
                                response.code(), responseBody,
                                webhookMessage.content != null && webhookMessage.content.length() > 50 
                                    ? webhookMessage.content.substring(0, 50) + "..." 
                                    : webhookMessage.content,
                                webhookMessage.username,
                                webhookMessage.webhookUrl.length() > 50 
                                    ? webhookMessage.webhookUrl.substring(0, 50) + "..." 
                                    : webhookMessage.webhookUrl);
                    } else if (DiscordConfig.CONFIG.debugLogging.get()) {
                        VonixCore.LOGGER.debug("[Discord] Successfully sent webhook message (HTTP {}) | " +
                                "Content length: {} | Username: {}", 
                                response.code(), 
                                webhookMessage.content != null ? webhookMessage.content.length() : 0,
                                webhookMessage.username);
                    }
                }
            } catch (IOException httpError) {
                VonixCore.LOGGER.error("[Discord] Network error sending webhook message. " +
                        "Error: {} | Error type: {} | Content: {} | Username: {} | " +
                        "Webhook URL: {} | This may indicate network connectivity issues.", 
                        httpError.getMessage(), httpError.getClass().getSimpleName(),
                        webhookMessage.content != null && webhookMessage.content.length() > 50 
                            ? webhookMessage.content.substring(0, 50) + "..." 
                            : webhookMessage.content,
                        webhookMessage.username,
                        webhookMessage.webhookUrl != null && webhookMessage.webhookUrl.length() > 50 
                            ? webhookMessage.webhookUrl.substring(0, 50) + "..." 
                            : webhookMessage.webhookUrl, httpError);
            } catch (Exception requestError) {
                VonixCore.LOGGER.error("[Discord] Error creating or executing HTTP request for webhook message. " +
                        "Error: {} | Error type: {} | Content: {} | Username: {} | " +
                        "This indicates an HTTP client configuration issue.", 
                        requestError.getMessage(), requestError.getClass().getSimpleName(),
                        webhookMessage.content != null && webhookMessage.content.length() > 50 
                            ? webhookMessage.content.substring(0, 50) + "..." 
                            : webhookMessage.content,
                        webhookMessage.username, requestError);
            }

        } catch (Exception generalError) {
            VonixCore.LOGGER.error("[Discord] Unexpected error during webhook message sending. " +
                    "Error: {} | Error type: {} | Content: {} | Username: {} | " +
                    "This indicates a system-level issue.", 
                    generalError.getMessage(), generalError.getClass().getSimpleName(),
                    webhookMessage.content != null && webhookMessage.content.length() > 50 
                        ? webhookMessage.content.substring(0, 50) + "..." 
                        : webhookMessage.content,
                    webhookMessage.username, generalError);
        } catch (Throwable criticalError) {
            VonixCore.LOGGER.error("[Discord] Critical error during webhook message sending. " +
                    "Error: {} | Error type: {} | " +
                    "This indicates a severe system issue that needs immediate attention.", 
                    criticalError.getMessage(), criticalError.getClass().getSimpleName(), criticalError);
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

    /**
     * Gets the configured server prefix for a specific Discord server.
     * Uses the ServerPrefixConfig system to provide unique prefixes for different servers.
     * 
     * @param server The Discord server, can be null
     * @return The server prefix, never null or empty
     */
    public String getServerPrefix(org.javacord.api.entity.server.Server server) {
        if (serverPrefixConfig == null) {
            // Fallback to old config system if prefix config is not initialized
            return DiscordConfig.CONFIG.serverPrefix.get();
        }
        return serverPrefixConfig.getServerPrefix(server);
    }

    /**
     * Gets the configured server prefix for a specific Discord server ID.
     * Uses the ServerPrefixConfig system to provide unique prefixes for different servers.
     * 
     * @param serverId The Discord server ID
     * @return The server prefix, never null or empty
     */
    public String getServerPrefix(long serverId) {
        if (serverPrefixConfig == null) {
            // Fallback to old config system if prefix config is not initialized
            return DiscordConfig.CONFIG.serverPrefix.get();
        }
        return serverPrefixConfig.getServerPrefix(serverId);
    }

    /**
     * Gets the fallback server prefix used when no specific server context is available.
     * Returns prefix without brackets for consistent use with component builders.
     * 
     * @return The fallback server prefix (without brackets)
     */
    public String getFallbackServerPrefix() {
        if (serverPrefixConfig == null) {
            return stripBracketsFromPrefix(DiscordConfig.CONFIG.serverPrefix.get());
        }
        return serverPrefixConfig.getFallbackPrefix();
    }

    /**
     * Extracts server prefix from a webhook author name.
     * Webhook usernames follow the format "[Prefix]Username" or "[Prefix] Username".
     * This method extracts the prefix portion (without brackets) for use in advancement messages.
     * 
     * @param authorName The webhook author display name
     * @return The extracted prefix (without brackets), or fallback prefix if extraction fails
     */
    private String extractServerPrefixFromAuthor(String authorName) {
        if (authorName != null && authorName.startsWith("[") && authorName.contains("]")) {
            int endBracket = authorName.indexOf("]");
            // Extract the prefix without the brackets
            String prefix = authorName.substring(1, endBracket).trim();
            if (!prefix.isEmpty()) {
                return prefix;
            }
        }
        // Fallback to configured prefix if extraction fails
        // getFallbackServerPrefix() may return value with brackets, so strip them
        return stripBracketsFromPrefix(getFallbackServerPrefix());
    }
    
    /**
     * Extracts server prefix from a webhook author name for event messages.
     * Uses "Cross-Server" as fallback since events from unknown sources should not
     * display the local server's prefix (that would be confusing to players).
     * 
     * @param authorName The webhook author display name
     * @return The extracted prefix (without brackets), or "Cross-Server" if extraction fails
     */
    private String extractServerPrefixFromAuthorForEvents(String authorName) {
        if (authorName != null && authorName.startsWith("[") && authorName.contains("]")) {
            int endBracket = authorName.indexOf("]");
            // Extract the prefix without the brackets
            String prefix = authorName.substring(1, endBracket).trim();
            if (!prefix.isEmpty()) {
                return prefix;
            }
        }
        // For events, use "Cross-Server" as fallback to clearly indicate the source is unknown
        return "Cross-Server";
    }
    
    /**
     * Strips brackets from a prefix if present.
     * Handles prefixes in formats like "[Prefix]", "[Prefix", "Prefix]", or "Prefix".
     * 
     * @param prefix The prefix that may contain brackets
     * @return The prefix without surrounding brackets
     */
    private String stripBracketsFromPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return prefix;
        }
        String stripped = prefix.trim();
        // Remove leading bracket if present
        if (stripped.startsWith("[")) {
            stripped = stripped.substring(1);
        }
        // Remove trailing bracket if present
        if (stripped.endsWith("]")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped.trim();
    }

    /**
     * Gets the ServerPrefixConfig instance for advanced configuration.
     * 
     * @return The ServerPrefixConfig instance, may be null if not initialized
     */
    public ServerPrefixConfig getServerPrefixConfig() {
        return serverPrefixConfig;
    }
}
