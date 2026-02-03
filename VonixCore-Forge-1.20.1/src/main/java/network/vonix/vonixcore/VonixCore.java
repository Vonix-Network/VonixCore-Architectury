package network.vonix.vonixcore;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import network.vonix.vonixcore.admin.AdminManager;
import network.vonix.vonixcore.config.DatabaseConfig;
import network.vonix.vonixcore.config.DiscordConfig;
import network.vonix.vonixcore.config.EssentialsConfig;
import network.vonix.vonixcore.config.ProtectionConfig;
import network.vonix.vonixcore.config.XPSyncConfig;
import network.vonix.vonixcore.config.ClaimsConfig;
import network.vonix.vonixcore.auth.AuthConfig;
import network.vonix.vonixcore.claims.ClaimsManager;
import network.vonix.vonixcore.claims.ClaimsCommands;
import network.vonix.vonixcore.consumer.Consumer;
import network.vonix.vonixcore.database.Database;
import network.vonix.vonixcore.discord.DiscordManager;
import network.vonix.vonixcore.homes.HomeManager;
import network.vonix.vonixcore.kits.KitManager;
import network.vonix.vonixcore.warps.WarpManager;
import network.vonix.vonixcore.xpsync.XPSyncManager;
import network.vonix.vonixcore.auth.AuthenticationManager;
import network.vonix.vonixcore.jobs.JobsManager;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * VonixCore - All-in-one essentials mod for NeoForge 1.21.x
 * 
 * Features (all toggleable):
 * - Protection: Block logging and rollback (CoreProtect-like)
 * - Essentials: Homes, warps, TPA, economy, kits
 * - Discord: Bidirectional chat integration
 * - XPSync: XP synchronization to external API
 */
@Mod(VonixCore.MODID)
public class VonixCore {

    public static final String MODID = "vonixcore";
    public static final String MOD_NAME = "VonixCore";
    public static final String VERSION = "1.0.4";

    public static final Logger LOGGER = LogUtils.getLogger();

    private static VonixCore instance;
    private Database database;
    private XPSyncManager xpSyncManager;

    // Track enabled modules
    private boolean protectionEnabled = false;
    private boolean essentialsEnabled = false;
    private boolean discordEnabled = false;
    private boolean xpsyncEnabled = false;
    private boolean claimsEnabled = false;

    public static VonixCore getInstance() {
        return instance;
    }

    public Database getDatabase() {
        return database;
    }

    public int getMaxHomes() {
        return EssentialsConfig.CONFIG.maxHomes.get();
    }

    public boolean isProtectionEnabled() {
        return protectionEnabled;
    }

    public boolean isEssentialsEnabled() {
        return essentialsEnabled;
    }

    public VonixCore() {
        instance = this;

        // Get the mod event bus using Forge 1.20.1 approach
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);

        // Register separate config files for each module using Forge 1.20.1 API
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, DatabaseConfig.SPEC, "vonixcore-database.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ProtectionConfig.SPEC,
                "vonixcore-protection.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, EssentialsConfig.SPEC,
                "vonixcore-essentials.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, DiscordConfig.SPEC, "vonixcore-discord.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, XPSyncConfig.SPEC, "vonixcore-xpsync.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ClaimsConfig.SPEC, "vonixcore-claims.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, AuthConfig.SPEC, "vonixcore-auth.toml");

        LOGGER.info("[{}] Loading v{}...", MOD_NAME, VERSION);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("[{}] Common setup complete", MOD_NAME);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[{}] Initializing modules...", MOD_NAME);

        List<String> enabledModules = new ArrayList<>();

        // Initialize database (always needed)
        try {
            database = new Database(event.getServer());
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
            network.vonix.vonixcore.teleport.TeleportManager.getInstance();
            LOGGER.info("[{}] TeleportManager initialized", MOD_NAME);
        } catch (Throwable t) {
            LOGGER.error("[{}] Failed to initialize TeleportManager!", MOD_NAME, t);
        }

        // Initialize Protection module
        if (ProtectionConfig.CONFIG.enabled.get()) {
            try {
                // Consumer handles protection data batching
                Consumer.getInstance().start();
                protectionEnabled = true;
                enabledModules.add("Protection");
                LOGGER.info("[{}] Protection module enabled", MOD_NAME);
            } catch (Exception e) {
                LOGGER.error("[{}] Failed to initialize Protection: {}", MOD_NAME, e.getMessage());
            }
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

                // Initialize Jobs system
                if (EssentialsConfig.CONFIG.jobsEnabled.get()) {
                    network.vonix.vonixcore.jobs.JobsManager.getInstance().initialize(conn);
                    network.vonix.vonixcore.jobs.JobsCommands.register(event.getServer().getCommands().getDispatcher());
                    enabledModules.add("Jobs");
                }

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
                    xpSyncManager = new XPSyncManager(event.getServer());
                    xpSyncManager.start();
                    xpsyncEnabled = true;
                    enabledModules.add("XPSync");
                    LOGGER.info("[{}] XPSync module enabled", MOD_NAME);
                } catch (Exception e) {
                    LOGGER.error("[{}] Failed to initialize XPSync: {}", MOD_NAME, e.getMessage());
                }
            }
        }

        // Initialize Claims module
        if (ClaimsConfig.CONFIG.enabled.get()) {
            try (Connection conn = database.getConnection()) {
                ClaimsManager.getInstance().initializeTable(conn);
                ClaimsCommands.register(event.getServer().getCommands().getDispatcher());
                claimsEnabled = true;
                enabledModules.add("Claims");
                LOGGER.info("[{}] Claims module enabled", MOD_NAME);
            } catch (Exception e) {
                LOGGER.error("[{}] Failed to initialize Claims: {}", MOD_NAME, e.getMessage());
            }
        }

        // Log status
        if (enabledModules.isEmpty()) {
            LOGGER.warn("[{}] No modules enabled! Check your config files.", MOD_NAME);
        } else {
            LOGGER.info("[{}] ✓ Loaded successfully with modules: {}", MOD_NAME, String.join(", ", enabledModules));
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // Initialize Discord module (requires server to be fully started)
        if (DiscordConfig.CONFIG.enabled.get()) {
            try {
                DiscordManager.getInstance().initialize(event.getServer());
                discordEnabled = true;
                LOGGER.info("[{}] Discord module enabled", MOD_NAME);
            } catch (Exception e) {
                LOGGER.error("[{}] Failed to initialize Discord: {}", MOD_NAME, e.getMessage());
            }
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("[{}] Shutting down...", MOD_NAME);

        // Shutdown Discord with timeout
        if (discordEnabled) {
            try {
                if (DiscordManager.getInstance().isRunning()) {
                    String serverName = DiscordConfig.CONFIG.serverName.get();

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

        // Shutdown Protection consumer
        if (protectionEnabled) {
            try {
                Consumer.getInstance().stop();
                LOGGER.debug("[{}] Protection consumer shutdown complete", MOD_NAME);
            } catch (Throwable e) {
                LOGGER.error("[{}] Error during Consumer shutdown", MOD_NAME, e);
            }
        }

        // Shutdown Authentication
        try {
            AuthenticationManager.clearAll();
            AuthenticationManager.shutdown();
            network.vonix.vonixcore.auth.api.VonixNetworkAPI.shutdown();
            LOGGER.debug("[{}] Auth shutdown complete", MOD_NAME);
        } catch (Throwable e) {
            LOGGER.error("[{}] Error during Auth shutdown", MOD_NAME, e);
        }

        // Shutdown Jobs and Teleport
        if (essentialsEnabled) {
            try {
                JobsManager.getInstance().shutdown();
                network.vonix.vonixcore.teleport.TeleportManager.getInstance().clear();
                LOGGER.debug("[{}] Jobs/Teleport shutdown complete", MOD_NAME);
            } catch (Throwable e) {
                LOGGER.error("[{}] Error during Jobs shutdown", MOD_NAME, e);
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

    // Executor service for async operations - bounded to prevent thread exhaustion
    private static final java.util.concurrent.ExecutorService ASYNC_EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(
            2, // core threads
            16, // max threads
            60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(1000), // bounded queue
            r -> {
                Thread t = new Thread(r, "VonixCore-Async");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy() // backpressure: caller runs if queue full
    );

    /**
     * Execute a task asynchronously
     */
    public static void executeAsync(Runnable task) {
        ASYNC_EXECUTOR.submit(task);
    }

    /**
     * Execute a task on the main server thread
     */
    public static void execute(Runnable task) {
        net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().execute(task);
    }

    /**
     * Get the config path
     */
    public java.nio.file.Path getConfigPath() {
        return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
    }

}
