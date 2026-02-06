package network.vonix.vonixcore;

import dev.architectury.event.events.common.LifecycleEvent;
import network.vonix.vonixcore.admin.AdminManager;
import network.vonix.vonixcore.auth.AuthenticationManager;
import network.vonix.vonixcore.auth.api.VonixNetworkAPI;
import network.vonix.vonixcore.config.*;
import network.vonix.vonixcore.config.simple.SimpleConfigManager;
import network.vonix.vonixcore.consumer.Consumer;
import network.vonix.vonixcore.database.Database;
import network.vonix.vonixcore.discord.DiscordManager;
import network.vonix.vonixcore.homes.HomeManager;
import network.vonix.vonixcore.kits.KitManager;
import network.vonix.vonixcore.platform.Platform;
import network.vonix.vonixcore.teleport.TeleportManager;
import network.vonix.vonixcore.warps.WarpManager;
import network.vonix.vonixcore.permissions.PermissionManager;
import network.vonix.vonixcore.xpsync.XPSyncManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * VonixCore - All-in-one essentials mod (Architectury Port)
 */
public class VonixCore {

    public static final String MODID = "vonixcore";
    public static final String MOD_NAME = "VonixCore";
    public static final String VERSION = "1.1.7";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private static VonixCore instance;
    private net.minecraft.server.MinecraftServer server;
    private Database database;
    private XPSyncManager xpSyncManager;

    // Track enabled modules
    private boolean essentialsEnabled = false;
    private boolean discordEnabled = false;
    private boolean xpsyncEnabled = false;

    // Executor service for async operations
    public static final ExecutorService ASYNC_EXECUTOR = new ThreadPoolExecutor(
            2, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            r -> {
                Thread t = new Thread(r, "VonixCore-Async");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    public static void init() {
        instance = new VonixCore();
    }

    public static VonixCore getInstance() {
        return instance;
    }

    public net.minecraft.server.MinecraftServer getServer() {
        return server;
    }

    public Database getDatabase() {
        return database;
    }

    public int getMaxHomes() {
        return EssentialsConfig.CONFIG.maxHomes.get();
    }

    public boolean isEssentialsEnabled() {
        return essentialsEnabled;
    }

    private VonixCore() {
        LOGGER.info("[{}] Loading v{}...", MOD_NAME, VERSION);

        // Load Configs
        Path configDir = Platform.getConfigDirectory();
        SimpleConfigManager.load(configDir.resolve("vonixcore-database.json"), DatabaseConfig.SPEC);
        SimpleConfigManager.load(configDir.resolve("vonixcore-essentials.json"), EssentialsConfig.SPEC);

        // Load Discord config and ensure it exists
        Path discordConfigPath = configDir.resolve("vonixcore-discord.json");
        LOGGER.info("[{}] Checking for Discord config at: {}", MOD_NAME, discordConfigPath.toAbsolutePath());

        SimpleConfigManager.load(discordConfigPath, DiscordConfig.SPEC);

        if (discordConfigPath.toFile().exists()) {
            LOGGER.info("[{}] Discord config file found and loaded.", MOD_NAME);
        } else {
            LOGGER.warn("[{}] Discord config file NOT found after load attempt! Attempting to force save defaults...",
                    MOD_NAME);
            // Force save if not exists (though SimpleConfigManager should handle this)
            // SimpleConfigManager.save(discordConfigPath, DiscordConfig.SPEC); // Assuming
            // such method exists or is internal
        }

        SimpleConfigManager.load(configDir.resolve("vonixcore-xpsync.json"), XPSyncConfig.SPEC);
        SimpleConfigManager.load(configDir.resolve("vonixcore-auth.json"), AuthConfig.SPEC);

        LifecycleEvent.SERVER_STARTING.register(this::onServerStarting);
        LifecycleEvent.SERVER_STARTED.register(this::onServerStarted);
        LifecycleEvent.SERVER_STOPPING.register(this::onServerStopping);

        // Register Discord events
        network.vonix.vonixcore.discord.DiscordEventHandler.register();

        // Register Essentials events
        network.vonix.vonixcore.listener.EssentialsEventHandler.init();
    }

    private void onServerStarting(net.minecraft.server.MinecraftServer server) {
        this.server = server;
        LOGGER.info("[{}] Initializing modules...", MOD_NAME);
        List<String> enabledModules = new ArrayList<>();

        // Initialize database (always needed)
        try {
            database = new Database(server);
            database.initialize();
            LOGGER.info("[{}] Database initialized", MOD_NAME);
        } catch (Exception e) {
            LOGGER.error("[{}] Failed to initialize database: {}", MOD_NAME, e.getMessage());
            e.printStackTrace();
            return; // Cannot continue without database
        }

        // Force initialize TeleportManager to catch class loading errors early
        try {
            LOGGER.info("[{}] Initializing TeleportManager...", MOD_NAME);
            TeleportManager.getInstance();
            LOGGER.info("[{}] TeleportManager initialized", MOD_NAME);
        } catch (Throwable t) {
            LOGGER.error("[{}] Failed to initialize TeleportManager!", MOD_NAME, t);
        }

        // Initialize Essentials module
        if (EssentialsConfig.CONFIG.enabled.get()) {
            try (Connection conn = database.getConnection()) {
                if (EssentialsConfig.CONFIG.homesEnabled.get()) {
                    HomeManager.getInstance().initializeTable(conn);
                }
                if (EssentialsConfig.CONFIG.warpsEnabled.get()) {
                    WarpManager.getInstance().initializeTable(conn);
                }
                if (EssentialsConfig.CONFIG.kitsEnabled.get()) {
                    KitManager.getInstance().initializeTable(conn);
                    KitManager.getInstance().loadDefaultKits();
                }
                AdminManager.getInstance().initializeTable(conn);

                PermissionManager.getInstance().initialize(conn);
                LOGGER.info("[{}] Permission system initialized", MOD_NAME);

                // Jobs excluded

                essentialsEnabled = true;
                enabledModules.add("Essentials");
                LOGGER.info("[{}] Essentials module enabled", MOD_NAME);
            } catch (Exception e) {
                LOGGER.error("[{}] Failed to initialize Essentials: {}", MOD_NAME, e.getMessage());
            }
        }

        // Initialize XPSync module
        if (XPSyncConfig.CONFIG.enabled.get()) {
            String apiKey = XPSyncConfig.CONFIG.apiKey.get();
            if (apiKey == null || apiKey.isEmpty() || apiKey.equals("YOUR_API_KEY_HERE")) {
                LOGGER.warn("[{}] XPSync is enabled but API key not configured", MOD_NAME);
            } else {
                try {
                    xpSyncManager = new XPSyncManager(server);
                    xpSyncManager.start();
                    xpsyncEnabled = true;
                    enabledModules.add("XPSync");
                    LOGGER.info("[{}] XPSync module enabled", MOD_NAME);
                } catch (Exception e) {
                    LOGGER.error("[{}] Failed to initialize XPSync: {}", MOD_NAME, e.getMessage());
                }
            }
        }

        // Log status
        if (enabledModules.isEmpty()) {
            LOGGER.warn("[{}] No modules enabled! Check your config files.", MOD_NAME);
        } else {
            LOGGER.info("[{}] ✓ Loaded successfully with modules: {}", MOD_NAME, String.join(", ", enabledModules));
        }
    }

    private void onServerStarted(net.minecraft.server.MinecraftServer server) {
        // Initialize Discord module (requires server to be fully started)
        if (DiscordConfig.CONFIG.enabled.get()) {
            try {
                DiscordManager.getInstance().initialize(server);
                discordEnabled = true;
                LOGGER.info("[{}] Discord module enabled", MOD_NAME);
            } catch (Exception e) {
                LOGGER.error("[{}] Failed to initialize Discord: {}", MOD_NAME, e.getMessage());
            }
        }
    }

    private void onServerStopping(net.minecraft.server.MinecraftServer server) {
        LOGGER.info("[{}] Shutting down...", MOD_NAME);

        // Shutdown Discord with timeout
        if (discordEnabled) {
            try {
                DiscordManager.getInstance().shutdown();
                LOGGER.debug("[{}] Discord shutdown complete", MOD_NAME);
            } catch (Throwable e) {
                LOGGER.error("[{}] Error during Discord shutdown", MOD_NAME, e);
            }
        }

        // Shutdown XPSync
        if (xpsyncEnabled && xpSyncManager != null) {
            try {
                xpSyncManager.stop();
                xpSyncManager = null;
                LOGGER.debug("[{}] XPSync shutdown complete", MOD_NAME);
            } catch (Throwable e) {
                LOGGER.error("[{}] Error during XPSync shutdown", MOD_NAME, e);
            }
        }

        // Shutdown Authentication
        try {
            AuthenticationManager.clearAll();
            AuthenticationManager.shutdown();
            VonixNetworkAPI.shutdown();
            LOGGER.debug("[{}] Auth shutdown complete", MOD_NAME);
        } catch (Throwable e) {
            LOGGER.error("[{}] Error during Auth shutdown", MOD_NAME, e);
        }

        // Shutdown Teleport
        if (essentialsEnabled) {
            try {
                TeleportManager.getInstance().clear();
                LOGGER.debug("[{}] Teleport shutdown complete", MOD_NAME);
            } catch (Throwable e) {
                LOGGER.error("[{}] Error during Teleport shutdown", MOD_NAME, e);
            }
        }

        // Shutdown async executor
        try {
            ASYNC_EXECUTOR.shutdown();
            if (!ASYNC_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                ASYNC_EXECUTOR.shutdownNow();
            }
        } catch (Throwable e) {
            ASYNC_EXECUTOR.shutdownNow();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        // Close database last
        if (database != null) {
            try {
                database.close();
                database = null;
                LOGGER.debug("[{}] Database closed", MOD_NAME);
            } catch (Throwable e) {
                LOGGER.error("[{}] Error closing database", MOD_NAME, e);
            }
        }

        LOGGER.info("[{}] Shutdown complete", MOD_NAME);
    }

    public static void executeAsync(Runnable task) {
        ASYNC_EXECUTOR.submit(task);
    }

    public static void execute(Runnable task) {
        if (instance != null && instance.server != null) {
            instance.server.execute(task);
        } else {
            LOGGER.warn("[VonixCore] Cannot execute task on main thread - server not available");
        }
    }
}
