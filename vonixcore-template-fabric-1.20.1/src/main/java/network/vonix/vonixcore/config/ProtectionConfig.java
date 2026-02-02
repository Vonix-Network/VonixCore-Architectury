package network.vonix.vonixcore.config;

import java.nio.file.Path;

/**
 * Protection/Logging configuration for VonixCore.
 * Stored in config/vonixcore-protection.yml
 */
public class ProtectionConfig extends BaseConfig {

    private static ProtectionConfig instance;

    public static ProtectionConfig getInstance() {
        if (instance == null) {
            instance = new ProtectionConfig();
        }
        return instance;
    }

    public static void init(Path configDir) {
        getInstance().loadConfig(configDir);
    }

    private ProtectionConfig() {
        super("vonixcore-protection.yml");
    }

    private void loadConfig(Path configDir) {
        super.load(configDir);
    }

    @Override
    protected String getHeader() {
        return """
                # VonixCore Protection Configuration
                # Block logging and rollback features (similar to CoreProtect)
                """;
    }

    @Override
    protected void setDefaults() {
        // Master toggle
        setDefault("protection.enabled", true);

        // Logging toggles (matching Forge)
        setDefault("logging.block_break", true);
        setDefault("logging.block_place", true);
        setDefault("logging.block_explode", true);
        setDefault("logging.container_transactions", true);
        setDefault("logging.entity_kills", true);
        setDefault("logging.entity_spawn", false);
        setDefault("logging.player_interactions", true);
        setDefault("logging.chat", false);
        setDefault("logging.commands", false);
        setDefault("logging.signs", true);

        // Rollback settings (matching Forge)
        setDefault("rollback.default_radius", 10);
        setDefault("rollback.max_radius", 100);
        setDefault("rollback.default_time", 259200);
        setDefault("rollback.max_lookup_results", 1000);
    }

    // ============ Getters ============

    public boolean isEnabled() {
        return getBoolean("protection.enabled", true);
    }

    // Logging
    public boolean isLogBlockBreak() {
        return getBoolean("logging.block_break", true);
    }

    public boolean isLogBlockPlace() {
        return getBoolean("logging.block_place", true);
    }

    public boolean isLogBlockExplode() {
        return getBoolean("logging.block_explode", true);
    }

    public boolean isLogContainerTransactions() {
        return getBoolean("logging.container_transactions", true);
    }

    public boolean isLogEntityKills() {
        return getBoolean("logging.entity_kills", true);
    }

    public boolean isLogEntitySpawn() {
        return getBoolean("logging.entity_spawn", false);
    }

    public boolean isLogPlayerInteractions() {
        return getBoolean("logging.player_interactions", true);
    }

    public boolean isLogChat() {
        return getBoolean("logging.chat", false);
    }

    public boolean isLogCommands() {
        return getBoolean("logging.commands", false);
    }

    public boolean isLogSigns() {
        return getBoolean("logging.signs", true);
    }

    // Rollback
    public int getDefaultRadius() {
        return getInt("rollback.default_radius", 10);
    }

    public int getMaxRadius() {
        return getInt("rollback.max_radius", 100);
    }

    public int getDefaultTime() {
        return getInt("rollback.default_time", 259200);
    }

    public int getMaxLookupResults() {
        return getInt("rollback.max_lookup_results", 1000);
    }

    // Backward compatibility aliases
    public int getMaxTimeDays() {
        return getDefaultTime() / 86400; // Convert seconds to days
    }

    public boolean isLogContainerAccess() {
        return isLogContainerTransactions();
    }
}
