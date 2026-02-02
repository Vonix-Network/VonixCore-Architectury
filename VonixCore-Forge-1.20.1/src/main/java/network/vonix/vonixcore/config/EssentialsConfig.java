package network.vonix.vonixcore.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Essentials configuration for VonixCore.
 * Stored in config/vonixcore-essentials.toml
 */
public class EssentialsConfig {

        public static final ForgeConfigSpec SPEC;
        public static final EssentialsConfig CONFIG;

        // Master toggle
        public final ForgeConfigSpec.BooleanValue enabled;

        // Sub-module toggles
        public final ForgeConfigSpec.BooleanValue homesEnabled;
        public final ForgeConfigSpec.BooleanValue warpsEnabled;
        public final ForgeConfigSpec.BooleanValue tpaEnabled;
        public final ForgeConfigSpec.BooleanValue kitsEnabled;
        public final ForgeConfigSpec.BooleanValue rtpEnabled;
        public final ForgeConfigSpec.BooleanValue chatFormattingEnabled;

        // RTP settings
        public final ForgeConfigSpec.IntValue rtpCooldown;
        public final ForgeConfigSpec.IntValue rtpMaxRange;
        public final ForgeConfigSpec.IntValue rtpMinRange;

        // Homes settings
        public final ForgeConfigSpec.IntValue maxHomes;
        public final ForgeConfigSpec.IntValue homeCooldown;
        public final ForgeConfigSpec.IntValue teleportDelay;
        public final ForgeConfigSpec.IntValue tpaTimeout;
        public final ForgeConfigSpec.IntValue cooldown;
        public final ForgeConfigSpec.IntValue backTimeout;
        public final ForgeConfigSpec.IntValue deathBackDelay;
        public final ForgeConfigSpec.BooleanValue warpGuiEnabled;

        // TPA settings
        public final ForgeConfigSpec.IntValue tpaCooldown;

        // Kits settings
        public final ForgeConfigSpec.IntValue defaultKitCooldown;

        static {
                Pair<EssentialsConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder()
                                .configure(EssentialsConfig::new);
                CONFIG = pair.getLeft();
                SPEC = pair.getRight();
        }

        private EssentialsConfig(ForgeConfigSpec.Builder builder) {
                builder.comment(
                                "VonixCore Essentials Configuration",
                                "Homes, warps, teleportation, economy, and kits")
                                .push("essentials");

                enabled = builder.comment(
                                "Enable the essentials module",
                                "Set to false to completely disable all essentials features")
                                .define("enabled", true);

                builder.pop().comment(
                                "Feature Toggles",
                                "Enable or disable individual essentials features")
                                .push("features");

                homesEnabled = builder.comment("Enable home commands (/sethome, /home, /delhome)")
                                .define("homes_enabled", true);

                warpsEnabled = builder.comment("Enable warp commands (/setwarp, /warp, /delwarp)")
                                .define("warps_enabled", true);

                tpaEnabled = builder.comment("Enable teleport request commands (/tpa, /tpaccept, /tpadeny)")
                                .define("tpa_enabled", true);

                kitsEnabled = builder.comment("Enable kit system (/kit)")
                                .define("kits_enabled", true);

                rtpEnabled = builder.comment("Enable random teleport command (/rtp)")
                                .define("rtp_enabled", true);

                chatFormattingEnabled = builder.comment("Enable custom chat formatting (prefixes, suffixes, nicknames)")
                                .define("chat_formatting_enabled", true);

                builder.pop().comment(
                                "Homes Settings",
                                "Configure the player homes feature")
                                .push("homes");

                maxHomes = builder.comment(
                                "Maximum number of homes per player",
                                "Can be overridden with permissions for VIPs")
                                .defineInRange("max_homes", 5, 1, 100);

                homeCooldown = builder.comment(
                                "Cooldown between home teleports in seconds",
                                "Set to 0 to disable cooldown")
                                .defineInRange("cooldown", 5, 0, 300);

                teleportDelay = builder.comment(
                                "Teleport delay in seconds")
                                .defineInRange("teleport_delay", 0, 0, 10);

                tpaTimeout = builder.comment(
                                "TPA request timeout in seconds")
                                .defineInRange("tpa_timeout", 60, 10, 300);

                cooldown = builder.comment(
                                "Teleport cooldown in seconds")
                                .defineInRange("teleport.cooldown", 0, 0, 300);

                backTimeout = builder.comment(
                                "Back command timeout in seconds")
                                .defineInRange("teleport.back_timeout", 0, 0, 300);

                deathBackDelay = builder.comment(
                                "Minimum time to wait before using /back after death (seconds)")
                                .comment("Prevents immediate return to boss fights etc.")
                                .defineInRange("teleport.death_back_delay", 60, 0, 3600);

                warpGuiEnabled = builder.comment(
                                "Enable warp GUI instead of command-based warps")
                                .define("warp_gui_enabled", true);

                builder.pop().comment(
                                "TPA Settings",
                                "Configure teleport request feature")
                                .push("tpa");

                tpaCooldown = builder.comment(
                                "Cooldown between TPA requests in seconds")
                                .defineInRange("cooldown", 30, 0, 3600);

                builder.pop().comment(
                                "Kits Settings",
                                "Configure the kit system")
                                .push("kits");

                defaultKitCooldown = builder.comment(
                                "Default cooldown for kits in seconds",
                                "Individual kits can override this")
                                .defineInRange("default_cooldown", 86400, 0, 604800);

                builder.pop().comment(
                                "RTP Settings",
                                "Configure the random teleport feature")
                                .push("rtp");

                rtpCooldown = builder.comment(
                                "Cooldown between /rtp uses in seconds",
                                "Set to 0 to disable cooldown")
                                .defineInRange("cooldown", 600, 0, 86400);

                rtpMaxRange = builder.comment(
                                "Maximum distance from spawn for random teleport")
                                .defineInRange("max_range", 10000, 100, 100000);

                rtpMinRange = builder.comment(
                                "Minimum distance from spawn for random teleport")
                                .defineInRange("min_range", 500, 0, 50000);

                builder.pop();
        }
}
