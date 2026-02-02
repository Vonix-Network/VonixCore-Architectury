package network.vonix.vonixcore;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import network.vonix.vonixcore.admin.AdminManager;
import network.vonix.vonixcore.auth.AuthenticationManager;
import network.vonix.vonixcore.command.ProtectionCommands;
import network.vonix.vonixcore.command.ShopCommands;
import network.vonix.vonixcore.command.UtilityCommands;
import network.vonix.vonixcore.command.VonixCoreCommands;
import network.vonix.vonixcore.command.WorldCommands;
import network.vonix.vonixcore.config.*;
import network.vonix.vonixcore.consumer.Consumer;
import network.vonix.vonixcore.database.Database;
import network.vonix.vonixcore.discord.DiscordManager;
import network.vonix.vonixcore.homes.HomeManager;
import network.vonix.vonixcore.kits.KitManager;
import network.vonix.vonixcore.listener.*;
import network.vonix.vonixcore.warps.WarpManager;
import network.vonix.vonixcore.xpsync.XPSyncManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * VonixCore - All-in-one essentials mod for Fabric 1.20.1
 * 
 * Features (all toggleable):
 * - Protection: Block logging and rollback (CoreProtect-like)
 * - Essentials: Homes, warps, TPA, economy, kits
 * - Discord: Bidirectional chat integration
 * - XPSync: XP synchronization to external API
 * - Claims: Land claiming system
 * - Jobs: Earn money by playing
 */
public class VonixCore implements ModInitializer {

    public static final String MODID = "vonixcore";
    public static final String MOD_NAME = "VonixCore";
    public static final String VERSION = "20.1.1";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private static VonixCore instance;
    private Database database;
    private XPSyncManager xpSyncManager;

    // Track enabled modules
    private boolean protectionEnabled = false;
    private boolean essentialsEnabled = false;
    private boolean discordEnabled = false;
    private boolean xpsyncEnabled = false;

    // Executor service for async operations - bounded to prevent thread exhaustion
    private static final ExecutorService ASYNC_EXECUTOR = new ThreadPoolExecutor(
            2, // core threads
            16, // max threads
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000), // bounded queue
            r -> {
                Thread t = new Thread(r, "VonixCore-Async");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // backpressure: caller runs if queue full
    );

    public static VonixCore getInstance() {
        return instance;
    }

    public Database getDatabase() {
        return database;
    }

    public int getMaxHomes() {
        return EssentialsConfig.getInstance().getMaxHomes();
    }

    public boolean isProtectionEnabled() {
        return protectionEnabled;
    }

    public boolean isEssentialsEnabled() {
        return essentialsEnabled;
    }

    public boolean isDiscordEnabled() {
        return discordEnabled;
    }

    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("[{}] Loading v{}...", MOD_NAME, VERSION);

        // Load all configurations
        loadConfigs();

        // Register command registration callback
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // Register commands on both dedicated and integrated servers
            // Commands.CommandSelection.ALL covers both cases
            VonixCoreCommands.register(dispatcher);

            if (EssentialsConfig.getInstance().isEnabled()) {
                UtilityCommands.register(dispatcher);
                WorldCommands.register(dispatcher);

                if (EssentialsConfig.getInstance().isShopsEnabled()) {
                    ShopCommands.register(dispatcher);
                }
            }

            if (ProtectionConfig.getInstance().isEnabled()) {
                ProtectionCommands.register(dispatcher);
            }
        });

        // Register server lifecycle events
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        // Register event listeners
        // Only register protection listeners if protection module is enabled
        if (ProtectionConfig.getInstance().isEnabled()) {
            BlockEventListener.register();
            EntityEventListener.register();
        }
        PlayerEventListener.register();
        EssentialsEventHandler.register();

        // Register Auth event handlers
        network.vonix.vonixcore.auth.events.AuthEventHandler.register();

        // Register Discord event listeners (join/leave/death/advancements)
        network.vonix.vonixcore.discord.DiscordEventHandler.register();

        LOGGER.info("[{}] Mod initialized, waiting for server start...", MOD_NAME);
    }

    private void loadConfigs() {
        Path configDir = getConfigPath();

        DatabaseConfig.init(configDir);
        ProtectionConfig.init(configDir);
        EssentialsConfig.init(configDir);
        DiscordConfig.init(configDir);
        XPSyncConfig.init(configDir);
        AuthConfig.init(configDir);

        LOGGER.info("[{}] All configurations loaded", MOD_NAME);
    }

    private void onServerStarting(net.minecraft.server.MinecraftServer server) {
        LOGGER.info("[{}] Initializing modules...", MOD_NAME);

        List<String> enabledModules = new ArrayList<>();

        // Initialize Auth (ensure config is loaded)
        try {
            AuthenticationManager.updateFreezeCache();
            network.vonix.vonixcore.auth.integrations.LuckPermsIntegration.initialize();
            LOGGER.info("[{}] Auth module initialized", MOD_NAME);
        } catch (Exception e) {
            LOGGER.error("[{}] Failed to initialize Auth: {}", MOD_NAME, e.getMessage());
        }

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

        // Initialize Protection module
        if (ProtectionConfig.getInstance().isEnabled()) {
            try {
                Consumer.getInstance().start();
                protectionEnabled = true;
                enabledModules.add("Protection");
                LOGGER.info("[{}] Protection module enabled", MOD_NAME);
            } catch (Exception e) {
                LOGGER.error("[{}] Failed to initialize Protection: {}", MOD_NAME, e.getMessage());
            }
        }

        // Initialize Essentials module
        if (EssentialsConfig.getInstance().isEnabled()) {
            try (Connection conn = database.getConnection()) {
                if (EssentialsConfig.getInstance().isHomesEnabled()) {
                    HomeManager.getInstance().initializeTable(conn);
                }
                if (EssentialsConfig.getInstance().isWarpsEnabled()) {
                    WarpManager.getInstance().initializeTable(conn);
                }
                if (EssentialsConfig.getInstance().isKitsEnabled()) {
                    KitManager.getInstance().initializeTable(conn);
                    KitManager.getInstance().loadDefaultKits();
                }
                AdminManager.getInstance().initializeTable(conn);

                essentialsEnabled = true;
                enabledModules.add("Essentials");
                LOGGER.info("[{}] Essentials module enabled", MOD_NAME);
            } catch (Exception e) {
                LOGGER.error("[{}] Failed to initialize Essentials: {}", MOD_NAME, e.getMessage());
            }
        }

        // Initialize XPSync module (moved to onServerStarted for proper initialization)

        // Log status
        if (enabledModules.isEmpty()) {
            LOGGER.warn("[{}] No modules enabled! Check your config files.", MOD_NAME);
        } else {
            LOGGER.info("[{}] ✓ Loaded successfully with modules: {}", MOD_NAME, String.join(", ", enabledModules));
        }
    }

    private void onServerStarted(net.minecraft.server.MinecraftServer server) {
        // Initialize Discord module (requires server to be fully started)
        if (DiscordConfig.getInstance().isEnabled()) {
            try {
                DiscordManager.getInstance().initialize(server);
                discordEnabled = true;
                LOGGER.info("[{}] Discord module enabled", MOD_NAME);
            } catch (Exception e) {
                LOGGER.error("[{}] Failed to initialize Discord: {}", MOD_NAME, e.getMessage());
            }
        }

        // Initialize XPSync module (requires server to be fully started for player list access)
        if (XPSyncConfig.getInstance().isEnabled()) {
            String apiKey = XPSyncConfig.getInstance().getApiKey();
            if (apiKey == null || apiKey.isEmpty() || apiKey.equals("YOUR_API_KEY_HERE")) {
                LOGGER.warn("[{}] XPSync is enabled but API key not configured", MOD_NAME);
            } else {
                try {
                    xpSyncManager = new XPSyncManager(server);
                    xpSyncManager.start();
                    xpsyncEnabled = true;
                    LOGGER.info("[{}] XPSync module enabled", MOD_NAME);
                } catch (Exception e) {
                    LOGGER.error("[{}] Failed to initialize XPSync: {}", MOD_NAME, e.getMessage());
                }
            }
        }
    }

    private void onServerStopping(net.minecraft.server.MinecraftServer server) {
        LOGGER.info("[{}] Shutting down...", MOD_NAME);

        // Shutdown Discord with timeout
        if (discordEnabled) {
            try {
                if (DiscordManager.getInstance().isRunning()) {
                    String serverName = DiscordConfig.getInstance().getServerName();

                    // Send shutdown embed with timeout
                    CompletableFuture<Void> shutdownMessage = CompletableFuture.runAsync(() -> {
                        DiscordManager.getInstance().sendShutdownEmbed(serverName);
                    });

                    try {
                        shutdownMessage.get(2, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        LOGGER.debug("[{}] Discord shutdown message timed out", MOD_NAME);
                    }
                }
                DiscordManager.getInstance().shutdown();
                LOGGER.debug("[{}] Discord shutdown complete", MOD_NAME);
            } catch (Exception e) {
                LOGGER.error("[{}] Error during Discord shutdown", MOD_NAME, e);
            }
        }

        // Shutdown XPSync
        if (xpsyncEnabled && xpSyncManager != null) {
            try {
                xpSyncManager.stop();
                xpSyncManager = null;
                LOGGER.debug("[{}] XPSync shutdown complete", MOD_NAME);
            } catch (Exception e) {
                LOGGER.error("[{}] Error during XPSync shutdown", MOD_NAME, e);
            }
        }

        // Shutdown Protection consumer
        if (protectionEnabled) {
            try {
                Consumer.getInstance().stop();
                LOGGER.debug("[{}] Protection consumer shutdown complete", MOD_NAME);
            } catch (Exception e) {
                LOGGER.error("[{}] Error during Consumer shutdown", MOD_NAME, e);
            }
        }

        // Shutdown Authentication
        try {
            AuthenticationManager.clearAll();
            AuthenticationManager.shutdown();
            network.vonix.vonixcore.auth.api.VonixNetworkAPI.shutdown();
            LOGGER.debug("[{}] Auth shutdown complete", MOD_NAME);
        } catch (Exception e) {
            LOGGER.error("[{}] Error during Auth shutdown", MOD_NAME, e);
        }

        // Shutdown Jobs and Teleport
        if (essentialsEnabled) {
            try {
                network.vonix.vonixcore.teleport.TeleportManager.getInstance().clear();
                LOGGER.debug("[{}] Jobs/Teleport shutdown complete", MOD_NAME);
            } catch (Exception e) {
                LOGGER.error("[{}] Error during Jobs shutdown", MOD_NAME, e);
            }
        }

        // Shutdown async executor
        try {
            ASYNC_EXECUTOR.shutdown();
            if (!ASYNC_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                ASYNC_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            ASYNC_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Close database last
        if (database != null) {
            try {
                database.close();
                database = null;
                LOGGER.debug("[{}] Database closed", MOD_NAME);
            } catch (Exception e) {
                LOGGER.error("[{}] Error closing database", MOD_NAME, e);
            }
        }

        LOGGER.info("[{}] Shutdown complete", MOD_NAME);
    }

    /**
     * Execute a task asynchronously
     */
    public static void executeAsync(Runnable task) {
        ASYNC_EXECUTOR.submit(task);
    }

    /**
     * Get the config path
     */
    public Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir();
    }
}
