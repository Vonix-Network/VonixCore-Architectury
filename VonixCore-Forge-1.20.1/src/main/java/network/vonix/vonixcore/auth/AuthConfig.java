package network.vonix.vonixcore.auth;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class AuthConfig {
    public static final ForgeConfigSpec SPEC;
    public static final AuthConfig CONFIG;

    public static final ForgeConfigSpec.BooleanValue ENABLE_LUCKPERMS_SYNC;
    public static final ForgeConfigSpec.ConfigValue<String> ADMIN_RANK_IDS;

    public static final ForgeConfigSpec.BooleanValue REQUIRE_AUTHENTICATION;
    public static final ForgeConfigSpec.BooleanValue FREEZE_UNAUTHENTICATED;
    public static final ForgeConfigSpec.BooleanValue WARN_OF_AUTH;
    public static final ForgeConfigSpec.IntValue LOGIN_TIMEOUT;

    public static final ForgeConfigSpec.ConfigValue<String> API_BASE_URL;
    public static final ForgeConfigSpec.ConfigValue<String> REGISTRATION_API_KEY;
    public static final ForgeConfigSpec.IntValue API_TIMEOUT;
    public static final ForgeConfigSpec.ConfigValue<String> REGISTRATION_URL;

    public static final ForgeConfigSpec.ConfigValue<String> LOGIN_REQUIRED_MESSAGE;
    public static final ForgeConfigSpec.ConfigValue<String> AUTH_WARNING_MESSAGE;
    public static final ForgeConfigSpec.ConfigValue<String> GENERATING_CODE_MESSAGE;
    public static final ForgeConfigSpec.ConfigValue<String> REGISTRATION_CODE_MESSAGE;
    public static final ForgeConfigSpec.ConfigValue<String> ALREADY_AUTHENTICATED_MESSAGE;
    public static final ForgeConfigSpec.ConfigValue<String> AUTHENTICATING_MESSAGE;
    public static final ForgeConfigSpec.ConfigValue<String> AUTHENTICATION_SUCCESS_MESSAGE;
    public static final ForgeConfigSpec.ConfigValue<String> LOGIN_FAILED_MESSAGE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("auth");

        ENABLE_LUCKPERMS_SYNC = builder
                .comment("Enable LuckPerms rank synchronization")
                .define("enable_luckperms_sync", true);

        ADMIN_RANK_IDS = builder
                .comment("Comma-separated list of admin rank IDs that should not be overwritten")
                .define("admin_rank_ids", "admin,owner,developer");

        REQUIRE_AUTHENTICATION = builder
                .comment("Whether to require players to authenticate with Vonix Network")
                .define("require_authentication", true);

        FREEZE_UNAUTHENTICATED = builder
                .comment("Whether to freeze players until they authenticate")
                .define("freeze_unauthenticated", true);

        WARN_OF_AUTH = builder
                .comment("Whether to warn players if authentication is optional but recommended")
                .define("warn_of_auth", false);

        LOGIN_TIMEOUT = builder
                .comment("Time in seconds before kicking unauthenticated players (0 to disable)")
                .defineInRange("login_timeout", 60, 0, 3600);

        builder.push("urls");

        API_BASE_URL = builder
                .comment("Base URL for Vonix Network API")
                .define("api_base_url", "https://vonix.network/api");

        REGISTRATION_API_KEY = builder
                .comment("API Key for server authentication")
                .define("registration_api_key", "YOUR_API_KEY_HERE");

        API_TIMEOUT = builder
                .comment("Timeout for API requests in milliseconds")
                .defineInRange("api_timeout", 5000, 1000, 30000);

        REGISTRATION_URL = builder
                .comment("URL for player registration")
                .define("registration_url", "https://vonix.network/register");

        builder.pop().push("messages");

        LOGIN_REQUIRED_MESSAGE = builder
                .define("login_required_message", "§c§l⚠ §cYou must authenticate to play on this server.");

        AUTH_WARNING_MESSAGE = builder
                .define("auth_warning_message", "§e⚠ It is recommended to authenticate with Vonix Network.");

        GENERATING_CODE_MESSAGE = builder
                .define("generating_code_message", "§6⏳ §7Generating registration code...");

        REGISTRATION_CODE_MESSAGE = builder
                .define("registration_code_message", "§aYour registration code is: §e{code}");

        ALREADY_AUTHENTICATED_MESSAGE = builder
                .define("already_authenticated_message", "§aYou are already authenticated!");

        AUTHENTICATING_MESSAGE = builder
                .define("authenticating_message", "§6⏳ §7Authenticating...");

        AUTHENTICATION_SUCCESS_MESSAGE = builder
                .define("authentication_success_message", "§a§l✓ §7Successfully authenticated as §e{username}");

        LOGIN_FAILED_MESSAGE = builder
                .define("login_failed_message", "§c§l✗ §7Login failed: §c{error}");

        builder.pop();

        SPEC = builder.build();
        CONFIG = new AuthConfig(); // Dummy instance if needed, but fields are static
    }
}
