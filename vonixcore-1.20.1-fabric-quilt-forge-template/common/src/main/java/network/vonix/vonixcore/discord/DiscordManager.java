package network.vonix.vonixcore.discord;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.DiscordConfig;
import network.vonix.vonixcore.platform.Platform;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.javacord.api.entity.activity.ActivityType;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.intent.Intent;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.webhook.Webhook;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Main manager class for Discord integration.
 * Handles bot connection, message sending/receiving, and resource management.
 */
public class DiscordManager {

    private static DiscordManager instance;

    private DiscordApi api;
    private MinecraftServer server;
    private TextChannel channel;
    private TextChannel eventChannel; // For events like advancements/deaths if configured separately
    private Webhook webhook;
    private Webhook eventWebhook;
    private final OkHttpClient httpClient;
    private final ServerPrefixConfig serverPrefixConfig;
    private final PlayerPreferences playerPreferences;
    private final LinkedAccountsManager linkedAccountsManager;
    private final AdvancementDataExtractor advancementExtractor;
    private final EventDataExtractor eventExtractor;
    private final VanillaComponentBuilder componentBuilder;
    private boolean isConnected = false;

    private DiscordManager() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        
        this.serverPrefixConfig = new ServerPrefixConfig();
        this.advancementExtractor = new AdvancementDataExtractor();
        this.eventExtractor = new EventDataExtractor();
        this.componentBuilder = new VanillaComponentBuilder();
        
        Path configDir = Platform.getConfigDirectory();
        try {
            this.playerPreferences = new PlayerPreferences(configDir);
            this.linkedAccountsManager = new LinkedAccountsManager(configDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize Discord managers", e);
        }
    }

    public static DiscordManager getInstance() {
        if (instance == null) {
            instance = new DiscordManager();
        }
        return instance;
    }

    /**
     * Initialize the Discord bot connection.
     */
    public void initialize(MinecraftServer server) {
        this.server = server;

        if (!DiscordConfig.CONFIG.enabled.get()) {
            VonixCore.LOGGER.info("Discord integration is disabled in config.");
            return;
        }

        String token = DiscordConfig.CONFIG.botToken.get();
        if (token.isEmpty() || token.equals("YOUR_BOT_TOKEN_HERE")) {
            VonixCore.LOGGER.warn("Discord bot token is not set. Discord integration will not work.");
            return;
        }

        VonixCore.executeAsync(() -> {
            try {
                VonixCore.LOGGER.info("Connecting to Discord...");
                java.util.concurrent.CompletableFuture<DiscordApi> loginFuture = new DiscordApiBuilder()
                        .setToken(token)
                        .setAllIntentsExcept(Intent.GUILD_PRESENCES, Intent.GUILD_MEMBERS) // Optimized intents
                        .login();

                VonixCore.LOGGER.info("Discord login requested. Waiting for response...");

                loginFuture.thenAcceptAsync(apiInstance -> {
                            this.api = apiInstance;
                            this.isConnected = true;
                            VonixCore.LOGGER.info("Connected to Discord as {}", api.getYourself().getDiscriminatedName());

                            try {
                                setupChannels();
                                setupWebhooks();
                                setupListeners();
                                updateStatus();

                                if (DiscordConfig.CONFIG.sendJoin.get()) {
                                    sendServerStatusMessage("Server Started", "The Minecraft server has started.", 0x00FF00); // Green
                                }
                            } catch (Exception e) {
                                VonixCore.LOGGER.error("Error setting up Discord resources", e);
                            }
                        }, VonixCore.ASYNC_EXECUTOR)
                        .exceptionally(throwable -> {
                            VonixCore.LOGGER.error("Failed to connect to Discord", throwable);
                            this.isConnected = false;
                            return null;
                        });

            } catch (Exception e) {
                VonixCore.LOGGER.error("Failed to initiate Discord connection", e);
                isConnected = false;
            }
        });
    }

    /**
     * Shut down the Discord bot connection.
     */
    public void shutdown() {
        if (!isConnected || api == null) return;

        if (DiscordConfig.CONFIG.sendLeave.get()) {
            // Send shutdown message synchronously (best effort)
            try {
                sendServerStatusMessage("Server Stopped", "The Minecraft server has stopped.", 0xFF0000); // Red
                // Give it a moment to send
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // Ignore
            }
        }

        api.disconnect();
        isConnected = false;
        VonixCore.LOGGER.info("Disconnected from Discord.");
    }
    
    public boolean isRunning() {
        return isConnected;
    }

    private void setupChannels() {
        String channelId = DiscordConfig.CONFIG.channelId.get();
        if (!channelId.isEmpty()) {
            api.getTextChannelById(channelId).ifPresent(c -> {
                this.channel = c;
                String name = c.asServerTextChannel().map(sc -> sc.getName()).orElse("unknown");
                VonixCore.LOGGER.info("Bound to Discord channel: #{}", name);
            });
        }

        String eventChannelId = DiscordConfig.CONFIG.eventChannelId.get();
        if (!eventChannelId.isEmpty()) {
            api.getTextChannelById(eventChannelId).ifPresent(c -> {
                this.eventChannel = c;
                String name = c.asServerTextChannel().map(sc -> sc.getName()).orElse("unknown");
                VonixCore.LOGGER.info("Bound to Discord event channel: #{}", name);
            });
        } else {
            this.eventChannel = this.channel; // Fallback to main channel
        }
    }

    private void setupWebhooks() {
        // Main webhook setup
        String webhookUrl = DiscordConfig.CONFIG.webhookUrl.get();
        if (!webhookUrl.isEmpty()) {
            this.webhook = getWebhookFromUrl(webhookUrl);
        }

        // Event webhook setup
        String eventWebhookUrl = DiscordConfig.CONFIG.eventWebhookUrl.get();
        if (!eventWebhookUrl.isEmpty()) {
            this.eventWebhook = getWebhookFromUrl(eventWebhookUrl);
        } else {
            this.eventWebhook = this.webhook; // Fallback
        }
    }

    private Webhook getWebhookFromUrl(String url) {
        try {
            String[] parts = url.split("/");
            if (parts.length >= 7) {
                long id = Long.parseLong(parts[parts.length - 2]);
                // String token = parts[parts.length - 1]; // Token not needed for ID lookup if bot has access
                return api.getWebhookById(id).join();
            }
        } catch (Exception e) {
            VonixCore.LOGGER.warn("Failed to parse webhook URL: {}", url);
        }
        return null;
    }

    private void setupListeners() {
        api.addMessageCreateListener(event -> {
            // Ignore bots if configured
            if (DiscordConfig.CONFIG.ignoreBots.get() && event.getMessageAuthor().isBotUser()) return;
            // Ignore webhooks if configured (unless it's a specific cross-server bot)
            if (DiscordConfig.CONFIG.ignoreWebhooks.get() && event.getMessageAuthor().isWebhook()) return;
            // Ignore own messages
            if (event.getMessageAuthor().isYourself()) return;

            // Check channel
            if (channel != null && !event.getChannel().getIdAsString().equals(channel.getIdAsString())) return;

            // Handle commands or chat
            handleDiscordMessage(event.getMessage());
        });
    }

    private void handleDiscordMessage(Message message) {
        String content = message.getContent();
        
        // Loop Prevention: Filter by prefix
        // If enabled, ignore messages that start with our server prefix (prevents self-echo from webhooks)
        if (DiscordConfig.CONFIG.filterByPrefix.get()) {
            String serverPrefix = DiscordConfig.CONFIG.serverPrefix.get();
            if (content.startsWith(serverPrefix)) {
                if (DiscordConfig.CONFIG.debugLogging.get()) {
                    VonixCore.LOGGER.info("Ignored Discord message (Loop Prevention): Starts with server prefix '{}'", serverPrefix);
                }
                return;
            }
        }
        
        // Check for commands
        String prefix = DiscordConfig.CONFIG.serverPrefix.get();
        
        if (content.startsWith(prefix)) {
            handleCommand(message, content.substring(prefix.length()));
            return;
        }

        // Process chat bridging
        if (server != null) {
            String author = message.getAuthor().getDisplayName();
            String msg = message.getContent();
            
            // Basic formatting
            String formatted = String.format(DiscordConfig.CONFIG.discordToMinecraftFormat.get(), author, msg);
            formatted = formatted.replace("{username}", author).replace("{message}", msg);
            
            final Component finalComponent = Component.literal(formatted);
            
            // Broadcast to server
            server.execute(() -> {
                server.getPlayerList().broadcastSystemMessage(finalComponent, false);
            });
        }
    }

    private void handleCommand(Message message, String command) {
        String[] parts = command.split(" ");
        String cmd = parts[0].toLowerCase();

        if (cmd.equals("link")) {
            // Handle account linking
            if (parts.length < 2) {
                message.getChannel().sendMessage("Usage: !link <code>");
                return;
            }
            String code = parts[1];
            String discordId = message.getAuthor().getIdAsString();
            String discordName = message.getAuthor().getDiscriminatedName();
            
            LinkedAccountsManager.LinkResult result = linkedAccountsManager.verifyAndLink(code, discordId, discordName);
            message.getChannel().sendMessage(result.message);
        } else if (cmd.equals("unlink")) {
            // Handle unlinking
            String discordId = message.getAuthor().getIdAsString();
            if (linkedAccountsManager.unlinkDiscord(discordId)) {
                message.getChannel().sendMessage("Successfully unlinked your Minecraft account.");
            } else {
                message.getChannel().sendMessage("You do not have a linked Minecraft account.");
            }
        } else if (cmd.equals("list")) {
            // Handle list command
            if (server != null) {
                int online = server.getPlayerList().getPlayerCount();
                int max = server.getPlayerList().getMaxPlayers();
                StringBuilder sb = new StringBuilder();
                sb.append("**Online Players (").append(online).append("/").append(max).append("):**\n");
                
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    sb.append(player.getName().getString()).append(", ");
                }
                
                String response = sb.toString();
                if (response.endsWith(", ")) {
                    response = response.substring(0, response.length() - 2);
                }
                
                message.getChannel().sendMessage(response);
            }
        }
    }

    public void updateStatus() {
        if (DiscordConfig.CONFIG.setBotStatus.get() && server != null) {
            int online = server.getPlayerList().getPlayerCount();
            int max = server.getPlayerList().getMaxPlayers();
            String status = DiscordConfig.CONFIG.botStatusFormat.get()
                    .replace("{online}", String.valueOf(online))
                    .replace("{max}", String.valueOf(max));
            api.updateActivity(ActivityType.PLAYING, status);
        }
    }

    // --- Sending Methods ---

    public void sendChatMessage(String playerName, String message, String uuid) {
        if (!isConnected || channel == null) return;

        VonixCore.executeAsync(() -> {
            if (webhook != null) {
                // Use webhook for player-like appearance
                sendWebhookMessage(webhook, playerName, message, getAvatarUrl(playerName, uuid));
            } else {
                // Fallback to bot message
                String format = DiscordConfig.CONFIG.minecraftToDiscordFormat.get()
                        .replace("{username}", playerName)
                        .replace("{message}", message);
                channel.sendMessage(format);
            }
        });
    }
    
    public void sendJoinEmbed(String playerName, String uuid) {
        if (!isConnected || !DiscordConfig.CONFIG.sendJoin.get()) return;
        
        sendEmbed(EmbedFactory.createPlayerEventEmbed(
            "Player Joined", 
            playerName + " joined the server.", 
            0x00FF00, // Green
            playerName, 
            DiscordConfig.CONFIG.serverName.get(), 
            "VonixCore", 
            getAvatarUrl(playerName, uuid)
        ));
    }
    
    public void sendLeaveEmbed(String playerName, String uuid) {
        if (!isConnected || !DiscordConfig.CONFIG.sendLeave.get()) return;
        
        sendEmbed(EmbedFactory.createPlayerEventEmbed(
            "Player Left", 
            playerName + " left the server.", 
            0xFF0000, // Red
            playerName, 
            DiscordConfig.CONFIG.serverName.get(), 
            "VonixCore", 
            getAvatarUrl(playerName, uuid)
        ));
    }
    
    public void sendDeathEmbed(String playerName, String deathMessage, String uuid) {
        if (!isConnected || !DiscordConfig.CONFIG.sendDeath.get()) return;
        
        sendEmbed(EmbedFactory.createSimpleEmbed(
            "Player Died", 
            deathMessage, 
            0x000000, // Black/Dark
            "VonixCore"
        ));
    }
    
    public void sendAdvancementEmbed(String playerName, String title, String description, AdvancementType type) {
        if (!isConnected || !DiscordConfig.CONFIG.sendAdvancement.get()) return;
        
        sendEmbed(EmbedFactory.createAdvancementEmbed(
            type == AdvancementType.CHALLENGE ? "🏆" : "⭐", 
            type.getColor().getColor() != null ? type.getColor().getColor() : 0xFFA500, 
            playerName, 
            title, 
            description
        ));
    }

    public void sendEmbed(Consumer<JsonObject> embedCreator) {
        if (!isConnected || channel == null) return;
        
        VonixCore.executeAsync(() -> {
            JsonObject json = new JsonObject();
            embedCreator.accept(json);
            
            EmbedBuilder builder = new EmbedBuilder();
            if (json.has("title")) builder.setTitle(json.get("title").getAsString());
            if (json.has("description")) builder.setDescription(json.get("description").getAsString());
            if (json.has("color")) builder.setColor(new java.awt.Color(json.get("color").getAsInt()));
            
            if (json.has("fields")) {
                json.getAsJsonArray("fields").forEach(e -> {
                    JsonObject field = e.getAsJsonObject();
                    builder.addField(
                        field.get("name").getAsString(),
                        field.get("value").getAsString(),
                        field.has("inline") && field.get("inline").getAsBoolean()
                    );
                });
            }
            
            if (json.has("footer")) {
                builder.setFooter(json.getAsJsonObject("footer").get("text").getAsString());
            }
            
            if (json.has("thumbnail")) {
                builder.setThumbnail(json.getAsJsonObject("thumbnail").get("url").getAsString());
            }
            
            // Send to event channel if available, otherwise main channel
            TextChannel target = (eventChannel != null) ? eventChannel : channel;
            target.sendMessage(builder);
        });
    }
    
    public void sendServerStatusMessage(String title, String description, int color) {
         sendEmbed(EmbedFactory.createServerStatusEmbed(
             title, 
             description, 
             color, 
             DiscordConfig.CONFIG.serverName.get(), 
             "VonixCore System"
         ));
    }
    
    public void sendShutdownEmbed(String serverName) {
        sendServerStatusMessage("Server Stopped", "The Minecraft server has stopped.", 0xFF0000);
    }

    private void sendWebhookMessage(Webhook webhook, String username, String content, String avatarUrl) {
        webhook.asIncomingWebhook().ifPresent(incomingWebhook -> {
             JsonObject json = new JsonObject();
             json.addProperty("content", content);
             json.addProperty("username", username);
             json.addProperty("avatar_url", avatarUrl);
             
             Request request = new Request.Builder()
                 .url("https://discord.com/api/webhooks/" + incomingWebhook.getId() + "/" + incomingWebhook.getToken())
                 .post(okhttp3.RequestBody.create(json.toString(), okhttp3.MediaType.parse("application/json")))
                 .build();
                 
             try (Response response = httpClient.newCall(request).execute()) {
                 if (!response.isSuccessful()) {
                     VonixCore.LOGGER.warn("Failed to send webhook message: {}", response.code());
                 }
             } catch (IOException e) {
                 VonixCore.LOGGER.error("Error sending webhook message", e);
             }
        });
    }

    private String getAvatarUrl(String playerName, String uuid) {
        String template = DiscordConfig.CONFIG.avatarUrl.get();
        return template.replace("{uuid}", uuid).replace("{username}", playerName);
    }

    public LinkedAccountsManager getLinkedAccountsManager() {
        return linkedAccountsManager;
    }
    
    public ServerPrefixConfig getServerPrefixConfig() {
        return serverPrefixConfig;
    }
    
    public PlayerPreferences getPlayerPreferences() {
        return playerPreferences;
    }
    
    public AdvancementDataExtractor getAdvancementExtractor() {
        return advancementExtractor;
    }
    
    public EventDataExtractor getEventExtractor() {
        return eventExtractor;
    }
    
    public VanillaComponentBuilder getComponentBuilder() {
        return componentBuilder;
    }
    
    public String generateLinkCode(ServerPlayer player) {
        return linkedAccountsManager.generateLinkCode(player.getUUID(), player.getName().getString());
    }
    
    public boolean unlinkAccount(java.util.UUID uuid) {
        return linkedAccountsManager.unlinkMinecraft(uuid);
    }
    
    public void setServerMessagesFiltered(java.util.UUID uuid, boolean filtered) {
        playerPreferences.setServerMessagesFiltered(uuid, filtered);
    }
    
    public boolean hasServerMessagesFiltered(java.util.UUID uuid) {
        return playerPreferences.hasServerMessagesFiltered(uuid);
    }
    
    public void setEventsFiltered(java.util.UUID uuid, boolean filtered) {
        playerPreferences.setEventsFiltered(uuid, filtered);
    }
    
    public boolean hasEventsFiltered(java.util.UUID uuid) {
        return playerPreferences.hasEventsFiltered(uuid);
    }
}