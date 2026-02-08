package network.vonix.vonixcore.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * XP Sync configuration for VonixCore.
 * Stored in config/vonixcore-xpsync.toml
 */
public class XPSyncConfig {

    public static final ForgeConfigSpec SPEC;
    public static final XPSyncConfig CONFIG;

    // Master toggle
    public final ForgeConfigSpec.BooleanValue enabled;

    // API settings
    public final ForgeConfigSpec.ConfigValue<String> apiEndpoint;
    public final ForgeConfigSpec.ConfigValue<String> apiKey;
    public final ForgeConfigSpec.ConfigValue<String> serverName;
    public final ForgeConfigSpec.IntValue syncInterval;

    // Data options
    public final ForgeConfigSpec.BooleanValue trackPlaytime;
    public final ForgeConfigSpec.BooleanValue trackHealth;
    public final ForgeConfigSpec.BooleanValue trackHunger;
    public final ForgeConfigSpec.BooleanValue trackPosition;

    // Advanced
    public final ForgeConfigSpec.BooleanValue verboseLogging;
    public final ForgeConfigSpec.IntValue connectionTimeout;
    public final ForgeConfigSpec.IntValue maxRetries;

    static {
        Pair<XPSyncConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder()
                .configure(XPSyncConfig::new);
        CONFIG = pair.getLeft();
        SPEC = pair.getRight();
    }

    private XPSyncConfig(ForgeConfigSpec.Builder builder) {
        builder.comment(
                "VonixCore XP Sync Configuration",
                "Synchronize player XP and stats to an external API",
                "",
                "This feature is designed for the Vonix Network website",
                "but can work with any compatible API endpoint")
                .push("xpsync");

        enabled = builder.comment(
                "Enable XP sync feature",
                "Set to false to completely disable XP synchronization")
                .define("enabled", false);

        builder.pop().comment(
                "API Connection",
                "Configure the external API connection")
                .push("api");

        apiEndpoint = builder.comment(
                "API endpoint URL for syncing XP data",
                "Example: https://yoursite.com/api/minecraft/sync/xp")
                .define("endpoint", "https://vonix.network/api/minecraft/sync/xp");

        apiKey = builder.comment(
                "API key for authentication",
                "Get this from your admin dashboard",
                "IMPORTANT: Keep this secret!")
                .define("api_key", "YOUR_API_KEY_HERE");

        serverName = builder.comment(
                "Unique identifier for this server",
                "Used to distinguish between multiple servers",
                "Examples: 'Survival-1', 'Creative', 'SkyBlock'")
                .define("server_name", "Server-1");

        syncInterval = builder.comment(
                "Sync interval in seconds",
                "How often to send data to the API",
                "Recommended: 300 (5 minutes)")
                .defineInRange("sync_interval", 300, 60, 3600);

        builder.pop().comment(
                "Data Options",
                "Choose what data to sync")
                .push("data");

        trackPlaytime = builder.comment("Track and sync player playtime")
                .define("track_playtime", true);

        trackHealth = builder.comment("Track and sync player health")
                .define("track_health", true);

        trackHunger = builder.comment("Track and sync player hunger level")
                .define("track_hunger", false);

        trackPosition = builder.comment(
                "Track and sync player position",
                "Note: May have privacy implications")
                .define("track_position", false);

        builder.pop().comment(
                "Advanced Settings",
                "Performance and debugging options")
                .push("advanced");

        verboseLogging = builder.comment(
                "Enable verbose logging for debugging",
                "Warning: Generates a lot of log output")
                .define("verbose_logging", false);

        connectionTimeout = builder.comment(
                "Connection timeout in milliseconds")
                .defineInRange("connection_timeout", 10000, 1000, 60000);

        maxRetries = builder.comment(
                "Maximum retry attempts on failure")
                .defineInRange("max_retries", 3, 0, 10);

        builder.pop();
    }
}
