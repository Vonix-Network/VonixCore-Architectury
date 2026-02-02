package network.vonix.vonixcore.config;

import java.nio.file.Path;

/**
 * Authentication configuration for VonixCore.
 * Ported from Forge for 1:1 parity.
 * Stored in config/vonixcore-auth.yml
 */
public class AuthConfig extends BaseConfig {

    private static AuthConfig instance;

    public static AuthConfig getInstance() {
        if (instance == null) {
            instance = new AuthConfig();
        }
        return instance;
    }

    public static void init(Path configDir) {
        getInstance().loadConfig(configDir);
    }

    private AuthConfig() {
        super("vonixcore-auth.yml");
    }

    private void loadConfig(Path configDir) {
        super.load(configDir);
    }

    @Override
    protected String getHeader() {
        return """
                # VonixCore Authentication Configuration
                # Player verification and freeze settings
                """;
    }

    @Override
    protected void setDefaults() {
        // Auth Settings
        setDefault("auth.require_authentication", true);
        setDefault("auth.freeze_unauthenticated", true);
        setDefault("auth.warn_of_auth", false);
        setDefault("auth.login_timeout", 60);
        setDefault("auth.enable_luckperms_sync", true);
        setDefault("auth.admin_rank_ids", "admin,owner,developer");

        // URLs / API
        setDefault("urls.api_base_url", "https://vonix.network/api");
        setDefault("urls.registration_api_key", "YOUR_API_KEY_HERE");
        setDefault("urls.api_timeout", 5000);
        setDefault("urls.registration_url", "https://vonix.network/register");

        // Messages
        setDefault("messages.login_required_message", "§c§l⚠ §cYou must authenticate to play on this server.");
        setDefault("messages.auth_warning_message", "§e⚠ It is recommended to authenticate with Vonix Network.");
        setDefault("messages.generating_code_message", "§6⏳ §7Generating registration code...");
        setDefault("messages.registration_code_message", "§aYour registration code is: §e{code}");
        setDefault("messages.already_authenticated_message", "§aYou are already authenticated!");
        setDefault("messages.authenticating_message", "§6⏳ §7Authenticating...");
        setDefault("messages.authentication_success_message", "§a§l✓ §7Successfully authenticated as §e{username}");
        setDefault("messages.login_failed_message", "§c§l✗ §7Login failed: §c{error}");
    }

    // ============ Getters ============

    public boolean isEnabled() {
        return getBoolean("auth.require_authentication", true);
    }

    public boolean isFreezeEnabled() {
        return getBoolean("auth.freeze_unauthenticated", true);
    }

    public boolean isWarnOfAuth() {
        return getBoolean("auth.warn_of_auth", false);
    }

    public int getLoginTimeout() {
        return getInt("auth.login_timeout", 60);
    }

    public boolean isLuckPermsSyncEnabled() {
        return getBoolean("auth.enable_luckperms_sync", true);
    }

    public String getAdminRankIds() {
        return getString("auth.admin_rank_ids", "admin,owner,developer");
    }

    public String getApiBaseUrl() {
        return getString("urls.api_base_url", "https://vonix.network/api");
    }

    public String getRegistrationApiKey() {
        return getString("urls.registration_api_key", "YOUR_API_KEY_HERE");
    }

    public int getApiTimeout() {
        return getInt("urls.api_timeout", 5000);
    }

    public String getRegistrationUrl() {
        return getString("urls.registration_url", "https://vonix.network/register");
    }

    // Messages
    public String getLoginRequiredMessage() {
        return getString("messages.login_required_message", "§c§l⚠ §cYou must authenticate to play on this server.");
    }

    public String getAuthWarningMessage() {
        return getString("messages.auth_warning_message", "§e⚠ It is recommended to authenticate with Vonix Network.");
    }

    public String getGeneratingCodeMessage() {
        return getString("messages.generating_code_message", "§6⏳ §7Generating registration code...");
    }

    public String getRegistrationCodeMessage() {
        return getString("messages.registration_code_message", "§aYour registration code is: §e{code}");
    }

    public String getAlreadyAuthenticatedMessage() {
        return getString("messages.already_authenticated_message", "§aYou are already authenticated!");
    }

    public String getAuthenticatingMessage() {
        return getString("messages.authenticating_message", "§6⏳ §7Authenticating...");
    }

    public String getAuthenticationSuccessMessage() {
        return getString("messages.authentication_success_message",
                "§a§l✓ §7Successfully authenticated as §e{username}");
    }

    public String getLoginFailedMessage() {
        return getString("messages.login_failed_message", "§c§l✗ §7Login failed: §c{error}");
    }
}
