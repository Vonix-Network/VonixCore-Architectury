package network.vonix.vonixcore.config;

import java.nio.file.Path;

/**
 * XP Sync configuration for VonixCore.
 * Stored in config/vonixcore-xpsync.yml
 */
public class XPSyncConfig extends BaseConfig {

    private static XPSyncConfig instance;

    public static XPSyncConfig getInstance() {
        if (instance == null) {
            instance = new XPSyncConfig();
        }
        return instance;
    }

    public static void init(Path configDir) {
        getInstance().loadConfig(configDir);
    }

    private XPSyncConfig() {
        super("vonixcore-xpsync.yml");
    }

    private void loadConfig(Path configDir) {
        super.load(configDir);
    }

    @Override
    protected String getHeader() {
        return """
                # VonixCore XP Sync Configuration
                # Synchronize player XP and stats to an external API
                #
                # This feature is designed for the Vonix Network website
                # but can work with any compatible API endpoint
                """;
    }

    @Override
    protected void setDefaults() {
        // Master toggle
        setDefault("xpsync.enabled", false);

        // API settings
        setDefault("api.endpoint", "https://vonix.network/api/minecraft/sync/xp");
        setDefault("api.api_key", "YOUR_API_KEY_HERE");
        setDefault("api.server_name", "Server-1");
        setDefault("api.sync_interval", 300);

        // Data options
        setDefault("data.track_playtime", true);
        setDefault("data.track_health", true);
        setDefault("data.track_hunger", false);
        setDefault("data.track_position", false);

        // Advanced
        setDefault("advanced.verbose_logging", false);
        setDefault("advanced.connection_timeout", 10000);
        setDefault("advanced.max_retries", 3);
    }

    // ============ Getters ============

    public boolean isEnabled() {
        return getBoolean("xpsync.enabled", false);
    }

    // API
    public String getApiEndpoint() {
        return getString("api.endpoint", "https://vonix.network/api/minecraft/sync/xp");
    }

    public String getApiKey() {
        return getString("api.api_key", "YOUR_API_KEY_HERE");
    }

    public String getServerName() {
        return getString("api.server_name", "Server-1");
    }

    public int getSyncInterval() {
        return getInt("api.sync_interval", 300);
    }

    // Data
    public boolean isTrackPlaytime() {
        return getBoolean("data.track_playtime", true);
    }

    public boolean isTrackHealth() {
        return getBoolean("data.track_health", true);
    }

    public boolean isTrackHunger() {
        return getBoolean("data.track_hunger", false);
    }

    public boolean isTrackPosition() {
        return getBoolean("data.track_position", false);
    }

    // Advanced
    public boolean isVerboseLogging() {
        return getBoolean("advanced.verbose_logging", false);
    }

    public int getConnectionTimeout() {
        return getInt("advanced.connection_timeout", 10000);
    }

    public int getMaxRetries() {
        return getInt("advanced.max_retries", 3);
    }
}
