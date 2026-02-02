package network.vonix.vonixcore.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.vonixcore.VonixCore;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Main integration class for the optimized RTP system.
 * 
 * Orchestrates all RTP components:
 * - RTPRequestManager for request processing
 * - LocationSearchEngine for finding safe locations
 * - ChunkLoadingManager for efficient chunk management
 * - SafetyValidationEngine for comprehensive safety checks
 * - PerformanceMonitor for metrics collection
 * 
 * Requirements: 1.1, 1.2, 1.3, 1.4, 10.1, 10.2, 10.4
 */
public class OptimizedRTPManager {
    
    private static volatile OptimizedRTPManager instance;
    
    private final OptimizedRTPRequestManager requestManager;
    private final LocationSearchEngine locationSearchEngine;
    private final ChunkLoadingManager chunkLoadingManager;
    private final SafetyValidationEngine safetyValidator;
    private final PerformanceMonitor performanceMonitor;
    private final RTPConfiguration configuration;
    
    private volatile boolean initialized = false;
    private volatile boolean shutdown = false;
    
    private OptimizedRTPManager() {
        this.requestManager = OptimizedRTPRequestManager.getInstance();
        this.locationSearchEngine = new LocationSearchEngine();
        this.chunkLoadingManager = ChunkLoadingManager.getInstance();
        this.safetyValidator = SafetyValidationEngine.getInstance();
        this.performanceMonitor = PerformanceMonitor.getInstance();
        this.configuration = RTPConfiguration.createDefault();
    }
    
    /**
     * Get the singleton instance of the optimized RTP manager.
     */
    public static OptimizedRTPManager getInstance() {
        if (instance == null) {
            synchronized (OptimizedRTPManager.class) {
                if (instance == null) {
                    instance = new OptimizedRTPManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Initialize the RTP system.
     */
    public void initialize() {
        if (initialized) {
            VonixCore.LOGGER.warn("[RTP] OptimizedRTPManager already initialized");
            return;
        }
        
        try {
            VonixCore.LOGGER.info("[RTP] Initializing OptimizedRTPManager...");
            
            // Components are initialized via their getInstance() methods
            // No explicit initialization needed
            
            initialized = true;
            VonixCore.LOGGER.info("[RTP] OptimizedRTPManager initialized successfully");
        } catch (Exception e) {
            VonixCore.LOGGER.error("[RTP] Failed to initialize OptimizedRTPManager", e);
            throw new RuntimeException("Failed to initialize RTP system", e);
        }
    }
    
    /**
     * Process an RTP request for a player.
     * 
     * @param player The player requesting RTP
     * @param targetWorld The world to teleport to
     * @return CompletableFuture containing the result
     */
    public CompletableFuture<RTPResult> processRTPRequest(ServerPlayer player, ServerLevel targetWorld) {
        if (!initialized) {
            return CompletableFuture.completedFuture(
                RTPResult.failure("RTP system not initialized", 0, 0));
        }
        
        if (shutdown) {
            return CompletableFuture.completedFuture(
                RTPResult.failure("RTP system is shutting down", 0, 0));
        }
        
        long startTime = System.currentTimeMillis();
        
        // Create RTP options from configuration
        RTPOptions options = new RTPOptions.Builder()
            .minRadius(configuration.getMinDistance())
            .maxRadius(configuration.getMaxDistance())
            .maxSearchAttempts(configuration.getMaxRetryAttempts())
            .searchTimeoutMs(configuration.getSearchTimeoutMs())
            .allowedBiomes(configuration.getAllowedBiomes())
            .blockedBiomes(configuration.getBlockedBiomes())
            .respectWorldBorder(true)
            .build();
        
        // Send initial message to player
        player.sendSystemMessage(Component.literal("§eSearching for a safe location..."));
        
        // Process request through the request manager
        return requestManager.processRTPRequest(player, options)
            .thenCompose(result -> {
                if (result.isSuccess()) {
                    // Request was processed, now find a safe location
                    return findAndTeleportToSafeLocation(player, targetWorld, options, startTime);
                } else {
                    // Request processing failed
                    return CompletableFuture.completedFuture(result);
                }
            })
            .exceptionally(throwable -> {
                VonixCore.LOGGER.error("[RTP] Error processing RTP request for {}", player.getName().getString(), throwable);
                player.sendSystemMessage(Component.literal("§cRTP failed: " + throwable.getMessage()));
                return RTPResult.failure("Error: " + throwable.getMessage(), 0, System.currentTimeMillis() - startTime);
            });
    }
    
    /**
     * Find a safe location and teleport the player.
     */
    private CompletableFuture<RTPResult> findAndTeleportToSafeLocation(ServerPlayer player, ServerLevel world, 
                                                                        RTPOptions options, long startTime) {
        BlockPos center = player.blockPosition();
        
        // Search for a safe location
        return locationSearchEngine.findSafeLocation(world, center, options)
            .thenCompose(locationOpt -> {
                if (locationOpt.isPresent()) {
                    BlockPos safeLocation = locationOpt.get();
                    long searchTime = System.currentTimeMillis() - startTime;
                    
                    // Teleport the player
                    return teleportPlayer(player, world, safeLocation, searchTime);
                } else {
                    long searchTime = System.currentTimeMillis() - startTime;
                    String message = "Could not find a safe location. Try again or adjust your search parameters.";
                    player.sendSystemMessage(Component.literal("§c" + message));
                    
                    VonixCore.LOGGER.warn("[RTP] Failed to find safe location for {} after {}ms", 
                                        player.getName().getString(), searchTime);
                    
                    return CompletableFuture.completedFuture(
                        RTPResult.failure(message, 0, searchTime));
                }
            })
            .exceptionally(throwable -> {
                long searchTime = System.currentTimeMillis() - startTime;
                VonixCore.LOGGER.error("[RTP] Error finding safe location for {}", player.getName().getString(), throwable);
                player.sendSystemMessage(Component.literal("§cRTP error: " + throwable.getMessage()));
                return RTPResult.failure("Error: " + throwable.getMessage(), 0, searchTime);
            });
    }
    
    /**
     * Teleport a player to a safe location.
     */
    private CompletableFuture<RTPResult> teleportPlayer(ServerPlayer player, ServerLevel world, BlockPos location, long searchTime) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long teleportStartTime = System.currentTimeMillis();
                
                // Perform final safety check before teleporting
                RTPOptions defaultOptions = new RTPOptions.Builder().build();
                SafetyValidationEngine.SafetyResult safetyResult = safetyValidator.validateLocation(world, location, defaultOptions).join();
                
                if (!safetyResult.isSafe()) {
                    String message = "Final safety check failed: " + safetyResult.getIssues();
                    VonixCore.LOGGER.warn("[RTP] Final safety check failed for {}: {}", 
                                        player.getName().getString(), message);
                    player.sendSystemMessage(Component.literal("§cFinal safety check failed. Try again."));
                    return RTPResult.failure(message, 0, searchTime);
                }
                
                // Teleport the player
                player.teleportTo(world, location.getX() + 0.5, location.getY() + 1.0, location.getZ() + 0.5, 
                                player.getYRot(), player.getXRot());
                
                long totalTime = System.currentTimeMillis() - teleportStartTime + searchTime;
                
                // Send success message
                player.sendSystemMessage(Component.literal("§aSuccessfully teleported to " + location.toShortString() + 
                                                          " (took " + totalTime + "ms)"));
                
                VonixCore.LOGGER.info("[RTP] Successfully teleported {} to {} in {}ms", 
                                    player.getName().getString(), location, totalTime);
                
                // Record metrics
                RTPMetrics metrics = RTPMetrics.builder(UUID.randomUUID().getMostSignificantBits())
                    .startTime(System.currentTimeMillis() - totalTime)
                    .endTime(System.currentTimeMillis())
                    .successful(true)
                    .build();
                performanceMonitor.recordMetrics(metrics);
                
                return RTPResult.success(location, (int)searchTime, (int)totalTime);
                
            } catch (Exception e) {
                VonixCore.LOGGER.error("[RTP] Error teleporting player {}", player.getName().getString(), e);
                player.sendSystemMessage(Component.literal("§cTeleportation failed: " + e.getMessage()));
                return RTPResult.failure("Teleportation error: " + e.getMessage(), 0, (int)searchTime);
            }
        });
    }
    
    /**
     * Get the current RTP configuration.
     */
    public RTPConfiguration getConfiguration() {
        return configuration;
    }
    
    /**
     * Get performance statistics.
     */
    public RTPRequestManager.RTPPerformanceStats getPerformanceStats() {
        return requestManager.getPerformanceStats();
    }
    
    /**
     * Get the current queue depth.
     */
    public int getQueueDepth() {
        return requestManager.getQueueDepth();
    }
    
    /**
     * Cancel an RTP request for a player.
     */
    public boolean cancelRTPRequest(UUID playerId) {
        return requestManager.cancelRTPRequest(playerId);
    }
    
    /**
     * Get the status of an RTP request.
     */
    public Optional<RTPStatus> getRTPStatus(UUID playerId) {
        return requestManager.getRTPStatus(playerId);
    }
    
    /**
     * Shutdown the RTP system.
     */
    public CompletableFuture<Void> shutdown() {
        if (shutdown) {
            return CompletableFuture.completedFuture(null);
        }
        
        shutdown = true;
        VonixCore.LOGGER.info("[RTP] Shutting down OptimizedRTPManager...");
        
        return CompletableFuture.allOf(
            requestManager.shutdown(30000),
            chunkLoadingManager.shutdown(30000),
            safetyValidator.shutdown(30000),
            CompletableFuture.runAsync(() -> {
                try {
                    performanceMonitor.shutdown();
                    locationSearchEngine.clearCache();
                    
                    VonixCore.LOGGER.info("[RTP] OptimizedRTPManager shutdown complete");
                } catch (Exception e) {
                    VonixCore.LOGGER.error("[RTP] Error during shutdown", e);
                }
            })
        );
    }
    
    /**
     * Check if the RTP system is initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Check if the RTP system is shutting down.
     */
    public boolean isShuttingDown() {
        return shutdown;
    }
    
    /**
     * Get diagnostic information about the RTP system.
     */
    public String getDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== RTP System Diagnostics ===\n");
        sb.append("Initialized: ").append(initialized).append("\n");
        sb.append("Shutting Down: ").append(shutdown).append("\n");
        sb.append("Queue Depth: ").append(getQueueDepth()).append("\n");
        sb.append("Performance Stats: ").append(getPerformanceStats()).append("\n");
        sb.append("Location Cache: ").append(locationSearchEngine.getCacheStats()).append("\n");
        sb.append("Spiral Search Stats: ").append(new SpiralSearchAlgorithm().getStats()).append("\n");
        return sb.toString();
    }
}
