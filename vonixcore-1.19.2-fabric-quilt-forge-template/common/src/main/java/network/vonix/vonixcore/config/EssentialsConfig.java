package network.vonix.vonixcore.config;

import network.vonix.vonixcore.config.simple.SimpleConfigBuilder;
import network.vonix.vonixcore.config.simple.SimpleConfigSpec;
import network.vonix.vonixcore.config.simple.SimpleConfigValue;
import org.apache.commons.lang3.tuple.Pair;

public class EssentialsConfig {

        public static final SimpleConfigSpec SPEC;
        public static final EssentialsConfig CONFIG;

        public final SimpleConfigValue<Boolean> enabled;
        public final SimpleConfigValue<Boolean> homesEnabled;
        public final SimpleConfigValue<Boolean> warpsEnabled;
        public final SimpleConfigValue<Boolean> tpaEnabled;
        public final SimpleConfigValue<Boolean> kitsEnabled;
        public final SimpleConfigValue<Boolean> rtpEnabled;
        public final SimpleConfigValue<Boolean> chatFormattingEnabled;
        public final SimpleConfigValue<Integer> rtpCooldown;
        public final SimpleConfigValue<Integer> rtpMaxRange;
        public final SimpleConfigValue<Integer> rtpMinRange;
        public final SimpleConfigValue<Integer> maxHomes;
        public final SimpleConfigValue<Integer> homeCooldown;
        public final SimpleConfigValue<Integer> teleportDelay;
        public final SimpleConfigValue<Integer> tpaTimeout;
        public final SimpleConfigValue<Integer> cooldown;
        public final SimpleConfigValue<Integer> backTimeout;
        public final SimpleConfigValue<Integer> deathBackDelay;
        public final SimpleConfigValue<Boolean> warpGuiEnabled;
        public final SimpleConfigValue<Integer> tpaCooldown;
        public final SimpleConfigValue<Integer> defaultKitCooldown;

        static {
                Pair<EssentialsConfig, SimpleConfigSpec> pair = new SimpleConfigBuilder()
                                .configure(EssentialsConfig::new);
                CONFIG = pair.getLeft();
                SPEC = pair.getRight();
        }

        private EssentialsConfig(SimpleConfigBuilder builder) {
                builder.push("essentials");
                enabled = builder.define("enabled", true);
                builder.pop().push("features");
                homesEnabled = builder.define("homes_enabled", true);
                warpsEnabled = builder.define("warps_enabled", true);
                tpaEnabled = builder.define("tpa_enabled", true);
                kitsEnabled = builder.define("kits_enabled", true);
                rtpEnabled = builder.define("rtp_enabled", true);
                chatFormattingEnabled = builder.define("chat_formatting_enabled", true);
                builder.pop().push("homes");
                maxHomes = builder.defineInRange("max_homes", 5, 1, 100);
                homeCooldown = builder.defineInRange("cooldown", 5, 0, 300);
                teleportDelay = builder.defineInRange("teleport_delay", 0, 0, 10);
                tpaTimeout = builder.defineInRange("tpa_timeout", 60, 10, 300);
                cooldown = builder.defineInRange("teleport.cooldown", 0, 0, 300);
                backTimeout = builder.defineInRange("teleport.back_timeout", 0, 0, 300);
                deathBackDelay = builder.defineInRange("teleport.death_back_delay", 60, 0, 3600);
                warpGuiEnabled = builder.define("warp_gui_enabled", true);
                builder.pop().push("tpa");
                tpaCooldown = builder.defineInRange("cooldown", 30, 0, 3600);
                builder.pop().push("kits");
                defaultKitCooldown = builder.defineInRange("default_cooldown", 86400, 0, 604800);
                builder.pop().push("rtp");
                rtpCooldown = builder.defineInRange("cooldown", 600, 0, 86400);
                rtpMaxRange = builder.defineInRange("max_range", 10000, 100, 100000);
                rtpMinRange = builder.defineInRange("min_range", 500, 0, 50000);
                builder.pop();
        }
}
