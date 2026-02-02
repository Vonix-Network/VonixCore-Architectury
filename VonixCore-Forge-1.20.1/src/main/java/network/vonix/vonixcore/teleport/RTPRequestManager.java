package network.vonix.vonixcore.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Central interface for managing RTP operations with CompletableFuture-based asynchronous processing.
 * 
 * This interface defines the contract for optimized RTP request management that addresses
 * the performance bottlenecks identified in the original AsyncRtpManager implementation.
 * 
 * Key improvements over the original implementation:
 * - CompletableFuture-based non-blocking operations
 * - Configurable concurrency limits (vs. sequential processing)
 * - Per-player request state tracking and cancellation support
 * - Comprehensive performance monitoring and metrics collection
 * - Advanced location search algorithms with biome filtering
 * - Optimized chunk loading with temporary tickets and automatic cleanup
 */
public interface RTPRequestManager {

    /**
     * Process an RTP request asynchronously with full configuration options.
     * 
     * This is the primary method for initiating RTP operations. It returns immediately
     * with a CompletableFuture that will be completed when the RTP operation finishes.
     * 
     * @param player The player requesting teleportation
     * @param options Configuration options for the RTP operation
     * @return CompletableFuture that completes with the RTP result
     */
    CompletableFuture<RTPResult> processRTPRequest(ServerPlayer player, RTPOptions options);

    /**
     * Process an RTP request with default options.
     * Convenience method that uses default configuration from EssentialsConfig.
     * 
     * @param player The player requesting teleportation
     * @return CompletableFuture that completes with the RTP result
     */
    default CompletableFuture<RTPResult> processRTPRequest(ServerPlayer player) {
        return processRTPRequest(player, RTPOptions.createDefault());
    }

    /**
     * Find a safe teleportation location without performing the actual teleport.
     * Useful for pre-validation or custom teleportation logic.
     * 
     * @param world The world to search in
     * @param center The center point for the search
     * @param options Search parameters and constraints
     * @return CompletableFuture that completes with the safe location (if found)
     */
    CompletableFuture<Optional<BlockPos>> findSafeLocation(ServerLevel world, BlockPos center, RTPOptions options);

    /**
     * Cancel an active RTP request for a specific player.
     * If the player has no active request, this method has no effect.
     * 
     * @param playerId The UUID of the player whose request should be cancelled
     * @return true if a request was cancelled, false if no active request existed
     */
    boolean cancelRTPRequest(UUID playerId);

    /**
     * Get the current status of an RTP request for a specific player.
     * 
     * @param playerId The UUID of the player to check
     * @return The current RTP status, or empty if no active request
     */
    Optional<RTPStatus> getRTPStatus(UUID playerId);

    /**
     * Get detailed information about an active RTP request.
     * 
     * @param playerId The UUID of the player to check
     * @return The RTP request details, or empty if no active request
     */
    Optional<RTPRequest> getRTPRequest(UUID playerId);

    /**
     * Check if a player currently has an active RTP request.
     * 
     * @param playerId The UUID of the player to check
     * @return true if the player has an active RTP request
     */
    default boolean hasActiveRequest(UUID playerId) {
        return getRTPStatus(playerId).map(status -> !status.isTerminal()).orElse(false);
    }

    /**
     * Get the current queue depth (number of pending requests).
     * Useful for monitoring system load and providing user feedback.
     * 
     * @return The number of requests currently queued or processing
     */
    int getQueueDepth();

    /**
     * Get performance metrics for monitoring and optimization.
     * 
     * @return Current performance statistics
     */
    RTPPerformanceStats getPerformanceStats();

    /**
     * Shutdown the RTP request manager gracefully.
     * This method should be called during server shutdown to clean up resources.
     * 
     * @param timeoutMs Maximum time to wait for active requests to complete
     * @return CompletableFuture that completes when shutdown is finished
     */
    CompletableFuture<Void> shutdown(long timeoutMs);

    /**
     * Performance statistics for the RTP system.
     * Provides insights into system performance and health.
     */
    interface RTPPerformanceStats {
        long getTotalRequests();
        long getSuccessfulRequests();
        long getFailedRequests();
        double getSuccessRate();
        double getAverageProcessingTimeMs();
        double getAverageChunkLoadTimeMs();
        int getCurrentConcurrency();
        int getMaxConcurrency();
        long getMainThreadImpactMs();
        boolean meetsPerformanceThresholds();
    }
}