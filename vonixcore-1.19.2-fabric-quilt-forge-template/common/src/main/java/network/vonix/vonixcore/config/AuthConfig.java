package network.vonix.vonixcore.config;

import network.vonix.vonixcore.config.simple.SimpleConfigBuilder;
import network.vonix.vonixcore.config.simple.SimpleConfigSpec;
import network.vonix.vonixcore.config.simple.SimpleConfigValue;
import org.apache.commons.lang3.tuple.Pair;

public class AuthConfig {
    public static final SimpleConfigSpec SPEC;
    public static final AuthConfig CONFIG;

    public final SimpleConfigValue<Boolean> ENABLE_LUCKPERMS_SYNC;
    public final SimpleConfigValue<String> ADMIN_RANK_IDS;
    public final SimpleConfigValue<Boolean> REQUIRE_AUTHENTICATION;
    public final SimpleConfigValue<Boolean> FREEZE_UNAUTHENTICATED;
    public final SimpleConfigValue<Boolean> WARN_OF_AUTH;
    public final SimpleConfigValue<Integer> LOGIN_TIMEOUT;
    public final SimpleConfigValue<String> API_BASE_URL;
    public final SimpleConfigValue<String> REGISTRATION_API_KEY;
    public final SimpleConfigValue<Integer> API_TIMEOUT;
    public final SimpleConfigValue<String> REGISTRATION_URL;
    public final SimpleConfigValue<String> LOGIN_REQUIRED_MESSAGE;
    public final SimpleConfigValue<String> AUTH_WARNING_MESSAGE;
    public final SimpleConfigValue<String> GENERATING_CODE_MESSAGE;
    public final SimpleConfigValue<String> REGISTRATION_CODE_MESSAGE;
    public final SimpleConfigValue<String> ALREADY_AUTHENTICATED_MESSAGE;
    public final SimpleConfigValue<String> AUTHENTICATING_MESSAGE;
    public final SimpleConfigValue<String> AUTHENTICATION_SUCCESS_MESSAGE;
    public final SimpleConfigValue<String> LOGIN_FAILED_MESSAGE;

    static {
        Pair<AuthConfig, SimpleConfigSpec> pair = new SimpleConfigBuilder()
                .configure(AuthConfig::new);
        CONFIG = pair.getLeft();
        SPEC = pair.getRight();
    }

    private AuthConfig(SimpleConfigBuilder builder) {
        builder.push("auth");
        ENABLE_LUCKPERMS_SYNC = builder.define("enable_luckperms_sync", true);
        ADMIN_RANK_IDS = builder.define("admin_rank_ids", "admin,owner,developer");
        REQUIRE_AUTHENTICATION = builder.define("require_authentication", true);
        FREEZE_UNAUTHENTICATED = builder.define("freeze_unauthenticated", true);
        WARN_OF_AUTH = builder.define("warn_of_auth", false);
        LOGIN_TIMEOUT = builder.defineInRange("login_timeout", 60, 0, 3600);
        builder.pop().push("urls");
        API_BASE_URL = builder.define("api_base_url", "https://vonix.network/api");
        REGISTRATION_API_KEY = builder.define("registration_api_key", "YOUR_API_KEY_HERE");
        API_TIMEOUT = builder.defineInRange("api_timeout", 5000, 1000, 30000);
        REGISTRATION_URL = builder.define("registration_url", "https://vonix.network/register");
        builder.pop().push("messages");
        LOGIN_REQUIRED_MESSAGE = builder.define("login_required_message", "§c§l⚠ §cYou must authenticate to play on this server.");
        AUTH_WARNING_MESSAGE = builder.define("auth_warning_message", "§e⚠ It is recommended to authenticate with Vonix Network.");
        GENERATING_CODE_MESSAGE = builder.define("generating_code_message", "§6⏳ §7Generating registration code...");
        REGISTRATION_CODE_MESSAGE = builder.define("registration_code_message", "§aYour registration code is: §e{code}");
        ALREADY_AUTHENTICATED_MESSAGE = builder.define("already_authenticated_message", "§aYou are already authenticated!");
        AUTHENTICATING_MESSAGE = builder.define("authenticating_message", "§6⏳ §7Authenticating...");
        AUTHENTICATION_SUCCESS_MESSAGE = builder.define("authentication_success_message", "§a§l✓ §7Successfully authenticated as §e{username}");
        LOGIN_FAILED_MESSAGE = builder.define("login_failed_message", "§c§l✗ §7Login failed: §c{error}");
        builder.pop();
    }
}
