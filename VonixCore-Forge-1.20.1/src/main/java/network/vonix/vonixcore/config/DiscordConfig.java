package network.vonix.vonixcore.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Discord integration configuration for VonixCore.
 * Stored in config/vonixcore-discord.toml
 */
public class DiscordConfig {

        public static final ForgeConfigSpec SPEC;
        public static final DiscordConfig CONFIG;

        // Master toggle
        public final ForgeConfigSpec.BooleanValue enabled;

        // Connection settings
        public final ForgeConfigSpec.ConfigValue<String> botToken;
        public final ForgeConfigSpec.ConfigValue<String> channelId;
        public final ForgeConfigSpec.ConfigValue<String> webhookUrl;
        public final ForgeConfigSpec.ConfigValue<String> webhookId;
        public final ForgeConfigSpec.ConfigValue<String> inviteUrl;

        // Server identity
        public final ForgeConfigSpec.ConfigValue<String> serverPrefix;
        public final ForgeConfigSpec.ConfigValue<String> serverName;
        public final ForgeConfigSpec.ConfigValue<String> serverAvatarUrl;

        // Message formats
        public final ForgeConfigSpec.ConfigValue<String> discordToMinecraftFormat;
        public final ForgeConfigSpec.ConfigValue<String> minecraftToDiscordFormat;
        public final ForgeConfigSpec.ConfigValue<String> webhookUsernameFormat;
        public final ForgeConfigSpec.ConfigValue<String> avatarUrl;

        // Event settings
        public final ForgeConfigSpec.BooleanValue sendJoin;
        public final ForgeConfigSpec.BooleanValue sendLeave;
        public final ForgeConfigSpec.BooleanValue sendDeath;
        public final ForgeConfigSpec.BooleanValue sendAdvancement;
        public final ForgeConfigSpec.ConfigValue<String> eventChannelId;
        public final ForgeConfigSpec.ConfigValue<String> eventWebhookUrl;

        // Loop prevention
        public final ForgeConfigSpec.BooleanValue ignoreBots;
        public final ForgeConfigSpec.BooleanValue ignoreWebhooks;
        public final ForgeConfigSpec.BooleanValue filterByPrefix;
        public final ForgeConfigSpec.BooleanValue showOtherServerEvents;

        // Bot status
        public final ForgeConfigSpec.BooleanValue setBotStatus;
        public final ForgeConfigSpec.ConfigValue<String> botStatusFormat;

        // Account linking
        public final ForgeConfigSpec.BooleanValue enableAccountLinking;
        public final ForgeConfigSpec.IntValue linkCodeExpiry;

        // Advanced
        public final ForgeConfigSpec.BooleanValue debugLogging;
        public final ForgeConfigSpec.IntValue messageQueueSize;
        public final ForgeConfigSpec.IntValue rateLimitDelay;

        static {
                Pair<DiscordConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder()
                                .configure(DiscordConfig::new);
                CONFIG = pair.getLeft();
                SPEC = pair.getRight();
        }

        private DiscordConfig(ForgeConfigSpec.Builder builder) {
                builder.comment(
                                "VonixCore Discord Integration",
                                "Bidirectional chat between Minecraft and Discord",
                                "",
                                "Setup Guide:",
                                "1. Create a Discord bot at https://discord.com/developers/applications",
                                "2. Enable MESSAGE CONTENT INTENT in Bot settings",
                                "3. Invite bot to your server with Message permissions",
                                "4. Copy bot token and paste below",
                                "5. Create a webhook in your Discord channel and paste URL below")
                                .push("discord");

                enabled = builder.comment(
                                "Enable Discord integration",
                                "Set to false to completely disable Discord features")
                                .define("enabled", false);

                builder.pop().comment(
                                "Connection Settings",
                                "Required settings to connect to Discord")
                                .push("connection");

                botToken = builder.comment(
                                "Discord Bot Token",
                                "Get from: Discord Developer Portal -> Your App -> Bot -> Token",
                                "IMPORTANT: Keep this secret! Never share your bot token.")
                                .define("bot_token", "YOUR_BOT_TOKEN_HERE");

                channelId = builder.comment(
                                "Discord Channel ID for chat messages",
                                "Right-click the channel -> Copy Channel ID",
                                "(Enable Developer Mode in Discord settings first)")
                                .define("channel_id", "YOUR_CHANNEL_ID_HERE");

                webhookUrl = builder.comment(
                                "Discord Webhook URL for sending messages",
                                "Channel Settings -> Integrations -> Webhooks -> New Webhook -> Copy URL")
                                .define("webhook_url", "https://discord.com/api/webhooks/YOUR_WEBHOOK_URL");

                webhookId = builder.comment(
                                "Webhook ID (leave empty to auto-extract from URL)",
                                "Only set this if auto-detection doesn't work")
                                .define("webhook_id", "");

                inviteUrl = builder.comment(
                                "Public Discord invite URL",
                                "Shown when players use /discord command")
                                .define("invite_url", "");

                builder.pop().comment(
                                "Server Identity",
                                "How your server appears in Discord")
                                .push("server_identity");

                serverPrefix = builder.comment(
                                "Server prefix shown in Discord messages",
                                "Example: [Survival], [Creative], [SMP]")
                                .define("prefix", "[MC]");

                serverName = builder.comment(
                                "Server name for embeds and bot messages")
                                .define("name", "Minecraft Server");

                serverAvatarUrl = builder.comment(
                                "Server avatar URL for event messages",
                                "Leave empty to use default")
                                .define("avatar_url", "");

                builder.pop().comment(
                                "Message Formats",
                                "Customize how messages appear")
                                .push("message_formats");

                discordToMinecraftFormat = builder.comment(
                                "Format for Discord -> Minecraft messages",
                                "Placeholders: {username}, {message}")
                                .define("discord_to_minecraft", "§b[Discord] §f{username}: {message}");

                minecraftToDiscordFormat = builder.comment(
                                "Format for Minecraft -> Discord messages",
                                "Placeholder: {message}")
                                .define("minecraft_to_discord", "{message}");

                webhookUsernameFormat = builder.comment(
                                "Webhook display name format",
                                "Placeholders: {prefix}, {username}")
                                .define("webhook_username", "{prefix}{username}");

                avatarUrl = builder.comment(
                                "Player avatar URL template",
                                "Placeholders: {uuid}, {username}")
                                .define("avatar_url", "https://minotar.net/armor/bust/{uuid}/100.png");

                builder.pop().comment(
                                "Event Notifications",
                                "Choose which events to send to Discord")
                                .push("events");

                sendJoin = builder.comment("Send player join notifications")
                                .define("send_join", true);

                sendLeave = builder.comment("Send player leave notifications")
                                .define("send_leave", true);

                sendDeath = builder.comment("Send player death messages")
                                .define("send_death", true);

                sendAdvancement = builder.comment("Send advancement notifications")
                                .define("send_advancement", true);

                eventChannelId = builder.comment(
                                "Separate channel ID for events (optional)",
                                "Leave empty to use main channel")
                                .define("event_channel_id", "");

                eventWebhookUrl = builder.comment(
                                "Separate webhook URL for events (optional)",
                                "Leave empty to use main webhook")
                                .define("event_webhook_url", "");

                builder.pop().comment(
                                "Loop Prevention",
                                "Prevent message loops in multi-server setups")
                                .push("loop_prevention");

                ignoreBots = builder.comment("Ignore messages from Discord bots")
                                .define("ignore_bots", false);

                ignoreWebhooks = builder.comment("Ignore messages from other webhooks")
                                .define("ignore_webhooks", false);

                filterByPrefix = builder.comment(
                                "Filter webhooks by server prefix",
                                "Useful for multi-server setups sharing a channel")
                                .define("filter_by_prefix", true);

                showOtherServerEvents = builder.comment(
                                "Show events from other servers in Minecraft",
                                "Only applies to multi-server setups")
                                .define("show_other_server_events", true);

                builder.pop().comment(
                                "Bot Status",
                                "Configure the bot's Discord presence")
                                .push("bot_status");

                setBotStatus = builder.comment("Update bot status with player count")
                                .define("enabled", true);

                botStatusFormat = builder.comment(
                                "Bot status format",
                                "Placeholders: {online}, {max}")
                                .define("format", "{online}/{max} players online");

                builder.pop().comment(
                                "Account Linking",
                                "Allow players to link Minecraft and Discord accounts")
                                .push("account_linking");

                enableAccountLinking = builder.comment("Enable Discord account linking")
                                .define("enabled", true);

                linkCodeExpiry = builder.comment(
                                "How long link codes remain valid (seconds)")
                                .defineInRange("code_expiry_seconds", 300, 60, 600);

                builder.pop().comment(
                                "Advanced Settings",
                                "Performance and debugging options")
                                .push("advanced");

                debugLogging = builder.comment("Enable verbose debug logging")
                                .define("debug_logging", false);

                messageQueueSize = builder.comment(
                                "Maximum queued messages before dropping",
                                "Increase if messages are being lost during high traffic")
                                .defineInRange("message_queue_size", 100, 10, 1000);

                rateLimitDelay = builder.comment(
                                "Minimum delay between webhook messages (ms)",
                                "Prevents rate limiting from Discord")
                                .defineInRange("rate_limit_delay", 1000, 100, 5000);

                builder.pop();
        }
}
