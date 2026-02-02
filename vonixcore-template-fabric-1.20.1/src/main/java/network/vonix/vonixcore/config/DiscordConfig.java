package network.vonix.vonixcore.config;

import java.nio.file.Path;

/**
 * Discord integration configuration for VonixCore.
 * Stored in config/vonixcore-discord.yml
 */
public class DiscordConfig extends BaseConfig {

    private static DiscordConfig instance;

    public static DiscordConfig getInstance() {
        if (instance == null) {
            instance = new DiscordConfig();
        }
        return instance;
    }

    public static void init(Path configDir) {
        getInstance().loadConfig(configDir);
    }

    private DiscordConfig() {
        super("vonixcore-discord.yml");
    }

    private void loadConfig(Path configDir) {
        super.load(configDir);
    }

    @Override
    protected String getHeader() {
        return """
                # VonixCore Discord Integration
                # Bidirectional chat between Minecraft and Discord
                #
                # Setup Guide:
                # 1. Create a Discord bot at https://discord.com/developers/applications
                # 2. Enable MESSAGE CONTENT INTENT in Bot settings
                # 3. Invite bot to your server with Message permissions
                # 4. Copy bot token and paste below
                # 5. Create a webhook in your Discord channel and paste URL below
                """;
    }

    @Override
    protected void setDefaults() {
        // Master toggle
        setDefault("discord.enabled", false);

        // Connection settings
        setDefault("connection.bot_token", "YOUR_BOT_TOKEN_HERE");
        setDefault("connection.channel_id", "YOUR_CHANNEL_ID_HERE");
        setDefault("connection.webhook_url", "https://discord.com/api/webhooks/YOUR_WEBHOOK_URL");
        setDefault("connection.webhook_id", "");
        setDefault("connection.invite_url", "");

        // Server identity
        setDefault("server_identity.prefix", "[MC]");
        setDefault("server_identity.name", "Minecraft Server");
        setDefault("server_identity.avatar_url", "");

        // Message formats
        setDefault("message_formats.discord_to_minecraft", "§b[Discord] §f{username}: {message}");
        setDefault("message_formats.minecraft_to_discord", "{message}");
        setDefault("message_formats.webhook_username", "{prefix}{username}");
        setDefault("message_formats.avatar_url", "https://minotar.net/armor/bust/{uuid}/100.png");

        // Event notifications
        setDefault("events.send_join", true);
        setDefault("events.send_leave", true);
        setDefault("events.send_death", true);
        setDefault("events.send_advancement", true);
        setDefault("events.event_channel_id", "");
        setDefault("events.event_webhook_url", "");

        // Loop prevention
        setDefault("loop_prevention.ignore_bots", false);
        setDefault("loop_prevention.ignore_webhooks", false);
        setDefault("loop_prevention.filter_by_prefix", true);
        setDefault("loop_prevention.show_other_server_events", true);

        // Bot status
        setDefault("bot_status.update_status", true);
        setDefault("bot_status.format", "{online}/{max} players online");

        // Account linking
        setDefault("account_linking.enable_linking", true);
        setDefault("account_linking.code_expiry_seconds", 300);

        // Advanced
        setDefault("advanced.debug_logging", false);
        setDefault("advanced.message_queue_size", 100);
        setDefault("advanced.rate_limit_delay", 1000);
    }

    // ============ Getters ============

    public boolean isEnabled() {
        return getBoolean("discord.enabled", false);
    }

    // Connection
    public String getBotToken() {
        return getString("connection.bot_token", "YOUR_BOT_TOKEN_HERE");
    }

    public String getChannelId() {
        return getString("connection.channel_id", "YOUR_CHANNEL_ID_HERE");
    }

    public String getWebhookUrl() {
        return getString("connection.webhook_url", "");
    }

    public String getWebhookId() {
        return getString("connection.webhook_id", "");
    }

    public String getInviteUrl() {
        return getString("connection.invite_url", "");
    }

    // Server identity
    public String getServerPrefix() {
        return getString("server_identity.prefix", "[MC]");
    }

    public String getServerName() {
        return getString("server_identity.name", "Minecraft Server");
    }

    public String getServerAvatarUrl() {
        return getString("server_identity.avatar_url", "");
    }

    // Message formats
    public String getDiscordToMinecraftFormat() {
        return getString("message_formats.discord_to_minecraft", "§b[Discord] §f<{username}> {message}");
    }

    public String getMinecraftToDiscordFormat() {
        return getString("message_formats.minecraft_to_discord", "{message}");
    }

    public String getWebhookUsernameFormat() {
        return getString("message_formats.webhook_username", "{prefix}{username}");
    }

    public String getAvatarUrl() {
        return getString("message_formats.avatar_url", "https://minotar.net/armor/bust/{uuid}/100.png");
    }

    // Events
    public boolean isSendJoin() {
        return getBoolean("events.send_join", true);
    }

    public boolean isSendLeave() {
        return getBoolean("events.send_leave", true);
    }

    public boolean isSendDeath() {
        return getBoolean("events.send_death", true);
    }

    public boolean isSendAdvancement() {
        return getBoolean("events.send_advancement", true);
    }

    public String getEventChannelId() {
        return getString("events.event_channel_id", "");
    }

    public String getEventWebhookUrl() {
        return getString("events.event_webhook_url", "");
    }

    // Loop prevention
    public boolean isIgnoreBots() {
        return getBoolean("loop_prevention.ignore_bots", false);
    }

    public boolean isIgnoreWebhooks() {
        return getBoolean("loop_prevention.ignore_webhooks", false);
    }

    public boolean isFilterByPrefix() {
        return getBoolean("loop_prevention.filter_by_prefix", true);
    }

    public boolean isShowOtherServerEvents() {
        return getBoolean("loop_prevention.show_other_server_events", true);
    }

    // Bot status
    public boolean isSetBotStatus() {
        return getBoolean("bot_status.update_status", true);
    }

    public String getBotStatusFormat() {
        return getString("bot_status.format", "{online}/{max} players online");
    }

    // Account linking
    public boolean isEnableAccountLinking() {
        return getBoolean("account_linking.enable_linking", true);
    }

    public int getLinkCodeExpiry() {
        return getInt("account_linking.code_expiry_seconds", 300);
    }

    // Advanced
    public boolean isDebugLogging() {
        return getBoolean("advanced.debug_logging", false);
    }

    public int getMessageQueueSize() {
        return getInt("advanced.message_queue_size", 100);
    }

    public int getRateLimitDelay() {
        return getInt("advanced.rate_limit_delay", 1000);
    }
}
