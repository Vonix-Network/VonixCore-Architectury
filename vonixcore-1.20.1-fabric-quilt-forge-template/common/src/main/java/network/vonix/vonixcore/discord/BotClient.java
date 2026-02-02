package network.vonix.vonixcore.discord;

import network.vonix.vonixcore.VonixCore;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.javacord.api.entity.activity.ActivityType;
import org.javacord.api.entity.intent.Intent;
import org.javacord.api.event.message.MessageCreateEvent;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Handles the persistent Bot connection via Javacord.
 * Used for receiving messages, events, and updating status.
 */
public class BotClient {

    private DiscordApi api;
    private String token;
    private String channelId;
    private Consumer<MessageCreateEvent> messageHandler;

    public BotClient() {
        // Initialize in disconnected state
    }

    public void setMessageHandler(Consumer<MessageCreateEvent> handler) {
        this.messageHandler = handler;
    }

    public void connect(String token, String channelId) {
        this.token = token;
        this.channelId = channelId;
        connect();
    }

    private void connect() {
        if (token == null || token.isEmpty() || token.equals("YOUR_BOT_TOKEN_HERE")) {
            VonixCore.LOGGER.warn("Bot token not configured.");
            return;
        }

        VonixCore.LOGGER.info("Connecting to Discord...");

        new DiscordApiBuilder()
                .setToken(token)
                .setAllIntentsExcept(Intent.GUILD_PRESENCES, Intent.GUILD_MEMBERS)
                .login()
                .thenAccept(this::onConnected)
                .exceptionally(throwable -> {
                    VonixCore.LOGGER.error("Failed to connect to Discord", throwable);
                    return null;
                });
    }

    private void onConnected(DiscordApi api) {
        this.api = api;
        VonixCore.LOGGER.info("Connected as {}", api.getYourself().getDiscriminatedName());

        // Register Listeners
        api.addMessageCreateListener(event -> {
            if (messageHandler != null) {
                // Basic filter: Check channel
                if (channelId != null && !channelId.isEmpty()) {
                    if (!event.getChannel().getIdAsString().equals(channelId)) return;
                }
                
                // Ignore self
                if (event.getMessageAuthor().isYourself()) return;

                messageHandler.accept(event);
            }
        });
    }

    public void updateStatus(int online, int max) {
        if (api != null) {
            String status = "Online: " + online + "/" + max;
            api.updateActivity(ActivityType.PLAYING, status);
        }
    }

    public void disconnect() {
        if (api != null) {
            api.disconnect();
            api = null;
        }
    }

    public boolean isConnected() {
        return api != null;
    }
}
