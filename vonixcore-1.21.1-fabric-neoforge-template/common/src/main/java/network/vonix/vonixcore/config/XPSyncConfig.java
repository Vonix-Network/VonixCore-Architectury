package network.vonix.vonixcore.config;

import network.vonix.vonixcore.config.simple.SimpleConfigBuilder;
import network.vonix.vonixcore.config.simple.SimpleConfigSpec;
import network.vonix.vonixcore.config.simple.SimpleConfigValue;
import org.apache.commons.lang3.tuple.Pair;

public class XPSyncConfig {

    public static final SimpleConfigSpec SPEC;
    public static final XPSyncConfig CONFIG;

    public final SimpleConfigValue<Boolean> enabled;
    public final SimpleConfigValue<String> apiEndpoint;
    public final SimpleConfigValue<String> apiKey;
    public final SimpleConfigValue<String> serverName;
    public final SimpleConfigValue<Integer> syncInterval;
    public final SimpleConfigValue<Boolean> trackPlaytime;
    public final SimpleConfigValue<Boolean> trackHealth;
    public final SimpleConfigValue<Boolean> trackHunger;
    public final SimpleConfigValue<Boolean> trackPosition;
    public final SimpleConfigValue<Boolean> verboseLogging;
    public final SimpleConfigValue<Integer> connectionTimeout;
    public final SimpleConfigValue<Integer> maxRetries;

    static {
        Pair<XPSyncConfig, SimpleConfigSpec> pair = new SimpleConfigBuilder()
                .configure(XPSyncConfig::new);
        CONFIG = pair.getLeft();
        SPEC = pair.getRight();
    }

    private XPSyncConfig(SimpleConfigBuilder builder) {
        builder.push("xpsync");
        enabled = builder.define("enabled", false);
        builder.pop().push("api");
        apiEndpoint = builder.define("endpoint", "https://vonix.network/api/minecraft/sync/xp");
        apiKey = builder.define("api_key", "YOUR_API_KEY_HERE");
        serverName = builder.define("server_name", "Server-1");
        syncInterval = builder.defineInRange("sync_interval", 300, 60, 3600);
        builder.pop().push("data");
        trackPlaytime = builder.define("track_playtime", true);
        trackHealth = builder.define("track_health", true);
        trackHunger = builder.define("track_hunger", false);
        trackPosition = builder.define("track_position", false);
        builder.pop().push("advanced");
        verboseLogging = builder.define("verbose_logging", false);
        connectionTimeout = builder.defineInRange("connection_timeout", 10000, 1000, 60000);
        maxRetries = builder.defineInRange("max_retries", 3, 0, 10);
        builder.pop();
    }
}
