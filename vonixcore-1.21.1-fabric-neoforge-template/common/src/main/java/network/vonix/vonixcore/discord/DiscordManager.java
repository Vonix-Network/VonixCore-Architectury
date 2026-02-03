package network.vonix.vonixcore.discord;

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
import network.vonix.vonixcore.platform.Platform;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.embed.Embed;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // Embed detection and processing
    private final EventEmbedDetector eventDetector = new EventEmbedDetector();
    private final AdvancementEmbedDetector advancementDetector = new AdvancementEmbedDetector();
    private final EventDataExtractor eventExtractor = new EventDataExtractor();
    private final AdvancementDataExtractor advancementExtractor = new AdvancementDataExtractor();
    private final VanillaComponentBuilder componentBuilder = new VanillaComponentBuilder();

    // Pattern for Discord markdown links
    private static final Pattern DISCORD_MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)]\\((https?://[^)]+)\\)");

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

        // Handle !list command early (before any filtering)
        if (message.getContent().trim().equalsIgnoreCase("!list")) {
            handleTextListCommand(event);
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
                if (authorName.startsWith(serverPrefix)) {
                    return;
                }
                String webhookFormat = DiscordConfig.CONFIG.webhookUsernameFormat.get();
                if (webhookFormat != null && webhookFormat.contains("{prefix}")) {
                    String expectedStart = webhookFormat.split("\\{prefix\\}")[0] + serverPrefix;
                    if (authorName.startsWith(expectedStart) || authorName.startsWith(serverPrefix)) {
                        return;
                    }
                }
            }
        }

        boolean isWebhook = message.getAuthor().isWebhook();
        String authorName = message.getAuthor().getDisplayName();
        String content = message.getContent();

        // Check for embeds that need special processing
        if (!message.getEmbeds().isEmpty()) {
            for (Embed embed : message.getEmbeds()) {
                // Check for advancement embeds first
                if (advancementDetector.isAdvancementEmbed(embed)) {
                    processAdvancementEmbed(embed, event);
                    return;
                }
                // Check for event embeds (join/leave/death)
                if (eventDetector.isEventEmbed(embed)) {
                    processEventEmbed(embed, event);
                    return;
                }
            }
        }

        // Regular message processing
        if (server != null) {
            MutableComponent finalComponent = Component.empty();

            if (isWebhook) {
                // Cross-server webhook: special formatting WITHOUT [Discord] prefix
                // Format: [ServerPrefix] Username: message
                String displayName = authorName;
                String cleanedContent = content;

                // Remove duplicate username from content if present (webhook quirk)
                if (content.startsWith(authorName + ": ")) {
                    cleanedContent = content.substring(authorName.length() + 2);
                } else if (content.startsWith(authorName + " ")) {
                    cleanedContent = content.substring(authorName.length() + 1);
                }

                String formattedMessage;
                if (displayName.startsWith("[") && displayName.contains("]")) {
                    int endBracket = displayName.indexOf("]");
                    String serverPrefix = displayName.substring(0, endBracket + 1);
                    String remainingName = displayName.substring(endBracket + 1).trim();

                    // Check if event channel
                    String eventChanId = DiscordConfig.CONFIG.eventChannelId.get();
                    boolean isEvtChannel = eventChanId != null && !eventChanId.isEmpty()
                            && eventChanId.equals(msgChannelId);

                    if (isEvtChannel) {
                        // Event channel: [Prefix] message (name is in message)
                        formattedMessage = "§a" + serverPrefix + " §f" + cleanedContent;
                    } else {
                        // Chat: [Prefix] Name: message
                        if (remainingName.isEmpty() || remainingName.toLowerCase().contains("server")) {
                            formattedMessage = "§a" + serverPrefix + " §f" + cleanedContent;
                        } else {
                            formattedMessage = "§a" + serverPrefix + " §f" + remainingName + "§7: §f" + cleanedContent;
                        }
                    }
                } else {
                    // No bracket prefix found - treat as cross-server
                    formattedMessage = "§a[Cross-Server] §f" + authorName + "§7: §f" + cleanedContent;
                }

                finalComponent.append(toMinecraftComponentWithLinks(formattedMessage));
            } else {
                // Regular Discord user: make [Discord] clickable
                String inviteUrl = DiscordConfig.CONFIG.inviteUrl.get();
                String rawFormat = DiscordConfig.CONFIG.discordToMinecraftFormat.get()
                        .replace("{username}", authorName)
                        .replace("{message}", content);

                if (rawFormat.contains("[Discord]") && inviteUrl != null && !inviteUrl.isEmpty()) {
                    String[] parts = rawFormat.split("\\[Discord\\]", 2);

                    // Part before [Discord]
                    if (parts.length > 0 && !parts[0].isEmpty()) {
                        finalComponent.append(toMinecraftComponentWithLinks(parts[0]));
                    }

                    // Clickable [Discord] with aqua color
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
                    // Fallback if no [Discord] tag or no invite URL
                    finalComponent.append(toMinecraftComponentWithLinks(rawFormat));
                }
            }

            // Broadcast to server
            server.execute(() -> {
                server.getPlayerList().broadcastSystemMessage(finalComponent, false);
            });
        }
    }

    /**
     * Processes an event embed (join/leave/death) and broadcasts as vanilla-style
     * message.
     */
    private void processEventEmbed(Embed embed, org.javacord.api.event.message.MessageCreateEvent event) {
        try {
            EventData data = eventExtractor.extractFromEmbed(embed);
            String serverPrefix = extractServerPrefixFromAuthor(event.getMessageAuthor().getDisplayName());

            MutableComponent eventComponent = componentBuilder.buildEventMessage(data, serverPrefix);

            if (server != null) {
                server.execute(() -> {
                    server.getPlayerList().broadcastSystemMessage(eventComponent, false);
                });
                if (DiscordConfig.CONFIG.debugLogging.get()) {
                    VonixCore.LOGGER.debug("[Discord] Processed event embed: {} {}",
                            data.getPlayerName(), data.getActionString());
                }
            }
        } catch (ExtractionException e) {
            VonixCore.LOGGER.warn("[Discord] Failed to extract event data: {}", e.getMessage());
            // Fallback to regular embed display
            handleEmbedFallback(embed, event);
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Error processing event embed", e);
            handleEmbedFallback(embed, event);
        }
    }

    /**
     * Processes an advancement embed and broadcasts as vanilla-style message.
     */
    private void processAdvancementEmbed(Embed embed, org.javacord.api.event.message.MessageCreateEvent event) {
        try {
            AdvancementData data = advancementExtractor.extractFromEmbed(embed);
            String serverPrefix = extractServerPrefixFromAuthor(event.getMessageAuthor().getDisplayName());

            MutableComponent advComponent = componentBuilder.buildAdvancementMessage(data, serverPrefix);

            if (server != null) {
                server.execute(() -> {
                    server.getPlayerList().broadcastSystemMessage(advComponent, false);
                });
                if (DiscordConfig.CONFIG.debugLogging.get()) {
                    VonixCore.LOGGER.debug("[Discord] Processed advancement embed: {} - {}",
                            data.getPlayerName(), data.getAdvancementTitle());
                }
            }
        } catch (ExtractionException e) {
            VonixCore.LOGGER.warn("[Discord] Failed to extract advancement data: {}", e.getMessage());
            handleEmbedFallback(embed, event);
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Error processing advancement embed", e);
            handleEmbedFallback(embed, event);
        }
    }

    /**
     * Fallback embed display when parsing fails.
     */
    private void handleEmbedFallback(Embed embed, org.javacord.api.event.message.MessageCreateEvent event) {
        Component fallback = MessageConverter.toMinecraft(event.getMessage());
        if (server != null) {
            server.execute(() -> {
                server.getPlayerList().broadcastSystemMessage(fallback, false);
            });
        }
    }

    /**
     * Extracts server prefix from webhook author name (e.g., "[HomeStead]" from
     * "[HomeStead] Player").
     */
    private String extractServerPrefixFromAuthor(String authorName) {
        if (authorName == null)
            return "Cross-Server";

        // Try to extract [Prefix] format
        if (authorName.startsWith("[")) {
            int endBracket = authorName.indexOf("]");
            if (endBracket > 1) {
                return authorName.substring(1, endBracket);
            }
        }
        return "Cross-Server";
    }

    /**
     * Converts text to Minecraft component, parsing Discord markdown links.
     */
    private Component toMinecraftComponentWithLinks(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }

        Matcher matcher = DISCORD_MARKDOWN_LINK.matcher(text);
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

            Component linkComponent = Component.literal(label)
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

    /**
     * Builds an embed displaying the list of online players.
     */
    private org.javacord.api.entity.message.embed.EmbedBuilder buildPlayerListEmbed() {
        java.util.List<net.minecraft.server.level.ServerPlayer> players = server.getPlayerList().getPlayers();
        int onlinePlayers = players.size();
        int maxPlayers = server.getPlayerList().getMaxPlayers();

        String serverName = DiscordConfig.CONFIG.serverName.get();

        org.javacord.api.entity.message.embed.EmbedBuilder embed = new org.javacord.api.entity.message.embed.EmbedBuilder()
                .setTitle("📋 " + serverName)
                .setColor(java.awt.Color.GREEN)
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

    /**
     * Handles the !list text command from Discord.
     */
    private void handleTextListCommand(org.javacord.api.event.message.MessageCreateEvent event) {
        try {
            if (server == null) {
                return;
            }
            org.javacord.api.entity.message.embed.EmbedBuilder embed = buildPlayerListEmbed();
            event.getChannel().sendMessage(embed);
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Error handling !list command", e);
        }
    }

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
