package network.vonix.vonixcore.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.EssentialsConfig;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optimized implementation of RTPRequestManager that addresses the performance bottlenecks
 * identified in the original AsyncRtpManager.
 * 
 * Key optimizations:
 * - Concurrent request processing (configurable concurrency limits)
 * - CompletableFuture-based non-blocking operations
 * - Per-player request state tracking with cancellation support
 * - Performance monitoring and metrics collection
 * - Efficient resource management with automatic cleanup
 */
public class OptimizedRTPRequestManager implements RTPRequestManager {

    // Configuration constants
    private static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 4;
    private static final int DEFAULT_THREAD_POOL_SIZE = 6;
    private static final long DEFAULT_REQUEST_TIMEOUT_MS = 60000; // 1 minute
    private static final long CLEANUP_INTERVAL_MS = 30000; // 30 seconds

    // Core components
    private final ExecutorService executorService;
    private final Semaphore concurrencyLimiter;
    private final ScheduledExecutorService cleanupExecutor;
    
    // Request tracking
    private final Map<UUID, RTPRequest> activeRequests = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<RTPResult>> activeFutures = new ConcurrentHashMap<>();
    
    // Performance tracking
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    private final AtomicLong totalChunkLoadTime = new AtomicLong(0);
    private final AtomicLong mainThreadImpact = new AtomicLong(0);
    private final AtomicInteger currentConcurrency = new AtomicInteger(0);
    private final AtomicInteger maxConcurrency = new AtomicInteger(0);

    // Singleton instance
    private static volatile OptimizedRTPRequestManager instance;
    private volatile boolean shutdown = false;

    private OptimizedRTPRequestManager() {
        // Initialize thread pool with daemon threads
        this.executorService = Executors.newFixedThreadPool(DEFAULT_THREAD_POOL_SIZE, r -> {
            Thread t = new Thread(r, "VonixCore-RTP-Worker");
            t.setDaemon(true);
            return t;
        });
        
        // Initialize concurrency limiter
        this.concurrencyLimiter = new Semaphore(DEFAULT_MAX_CONCURRENT_REQUESTS, true);
        
        // Initialize cleanup executor
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VonixCore-RTP-Cleanup");
            t.setDaemon(true);
            return t;
        });
        
        // Start periodic cleanup task
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredRequests, 
                                          CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        VonixCore.LOGGER.info("[RTP] OptimizedRTPRequestManager initialized with {} worker threads and {} max concurrent requests", 
                            DEFAULT_THREAD_POOL_SIZE, DEFAULT_MAX_CONCURRENT_REQUESTS);
    }

    /**
     * Get the singleton instance of the RTP request manager
     */
    public static OptimizedRTPRequestManager getInstance() {
        if (instance == null) {
            synchronized (OptimizedRTPRequestManager.class) {
                if (instance == null) {
                    instance = new OptimizedRTPRequestManager();
                }
            }
        }
        return instance;
    }

    @Override
    public CompletableFuture<RTPResult> processRTPRequest(ServerPlayer player, RTPOptions options) {
        if (shutdown) {
            return CompletableFuture.completedFuture(
                RTPResult.failure("RTP system is shutting down", 0, 0));
        }

        UUID playerId = player.getUUID();
        
        // Check if player already has an active request
        if (hasActiveRequest(playerId)) {
            return CompletableFuture.completedFuture(
                RTPResult.failure("You already have an active RTP request", 0, 0));
        }

        // Create request
        UUID requestId = UUID.randomUUID();
        RTPRequest request = new RTPRequest(requestId, player, player.serverLevel(), options);
        
        // Track request
        activeRequests.put(playerId, request);
        totalRequests.incrementAndGet();
        
        // Create and track future
        CompletableFuture<RTPResult> future = processRequestAsync(request);
        activeFutures.put(playerId, future);
        
        // Clean up tracking when complete
        future.whenComplete((result, throwable) -> {
            activeRequests.remove(playerId);
            activeFutures.remove(playerId);
            
            // Update statistics
            if (throwable == null && result != null) {
                if (result.isSuccess()) {
                    successfulRequests.incrementAndGet();
                } else {
                    failedRequests.incrementAndGet();
                }
                totalProcessingTime.addAndGet(result.getProcessingTimeMs());
                totalChunkLoadTime.addAndGet(result.getChunkLoadTimeMs());
            } else {
                failedRequests.incrementAndGet();
            }
        });
        
        return future;
    }

    @Override
    public CompletableFuture<Optional<BlockPos>> findSafeLocation(ServerLevel world, BlockPos center, RTPOptions options) {
        if (shutdown) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return CompletableFuture.supplyAsync(() -> {
            // This will be implemented with the location search engine
            // For now, return empty to satisfy the interface
            return Optional.<BlockPos>empty();
        }, executorService);
    }

    @Override
    public boolean cancelRTPRequest(UUID playerId) {
        RTPRequest request = activeRequests.get(playerId);
        if (request == null) {
            return false;
        }

        // Update request status
        request.setStatus(RTPStatus.CANCELLED);
        request.setFailureReason("Cancelled by user");

        // Cancel the future if it exists
        CompletableFuture<RTPResult> future = activeFutures.get(playerId);
        if (future != null) {
            future.cancel(true);
        }

        // Clean up tracking
        activeRequests.remove(playerId);
        activeFutures.remove(playerId);

        VonixCore.LOGGER.debug("[RTP] Cancelled request {} for player {}", 
                             request.getRequestId(), request.getPlayer().getName().getString());
        return true;
    }

    @Override
    public Optional<RTPStatus> getRTPStatus(UUID playerId) {
        RTPRequest request = activeRequests.get(playerId);
        return request != null ? Optional.of(request.getStatus()) : Optional.empty();
    }

    @Override
    public Optional<RTPRequest> getRTPRequest(UUID playerId) {
        return Optional.ofNullable(activeRequests.get(playerId));
    }

    @Override
    public int getQueueDepth() {
        return activeRequests.size();
    }

    @Override
    public RTPPerformanceStats getPerformanceStats() {
        return new RTPPerformanceStatsImpl();
    }

    @Override
    public CompletableFuture<Void> shutdown(long timeoutMs) {
        if (shutdown) {
            return CompletableFuture.completedFuture(null);
        }
        
        shutdown = true;
        VonixCore.LOGGER.info("[RTP] Shutting down OptimizedRTPRequestManager...");

        return CompletableFuture.runAsync(() -> {
            try {
                // Cancel all active requests
                activeRequests.keySet().forEach(this::cancelRTPRequest);
                
                // Shutdown executors
                cleanupExecutor.shutdown();
                executorService.shutdown();
                
                // Wait for termination
                if (!executorService.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                    VonixCore.LOGGER.warn("[RTP] Executor did not terminate within {}ms, forcing shutdown", timeoutMs);
                    executorService.shutdownNow();
                }
                
                if (!cleanupExecutor.awaitTermination(5000, TimeUnit.MILLISECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
                
                VonixCore.LOGGER.info("[RTP] OptimizedRTPRequestManager shutdown complete");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                VonixCore.LOGGER.error("[RTP] Shutdown interrupted", e);
            }
        });
    }

    /**
     * Process an RTP request asynchronously with concurrency control
     */
    private CompletableFuture<RTPResult> processRequestAsync(RTPRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            // Acquire concurrency permit
            try {
                concurrencyLimiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return RTPResult.failure("Request interrupted", 0, 0);
            }

            try {
                // Update concurrency tracking
                int current = currentConcurrency.incrementAndGet();
                maxConcurrency.updateAndGet(max -> Math.max(max, current));
                
                // Update request status
                request.setStatus(RTPStatus.PROCESSING);
                
                // Validate request is still valid
                if (!request.isValid()) {
                    return RTPResult.failure("Request expired or player disconnected", 0, request.getAge());
                }

                // Send progress message to player
                scheduleMainThreadTask(() -> {
                    if (request.getPlayer().isAlive() && !request.getPlayer().hasDisconnected()) {
                        request.getPlayer().sendSystemMessage(
                            Component.literal("§eSearching for a safe location... (Queue position: " + getQueueDepth() + ")"));
                    }
                });

                // Process the request (this will be expanded with actual location finding logic)
                return processRTPRequestInternal(request);
                
            } finally {
                // Release concurrency permit and update tracking
                concurrencyLimiter.release();
                currentConcurrency.decrementAndGet();
            }
        }, executorService).orTimeout(DEFAULT_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
          .exceptionally(throwable -> {
              if (throwable instanceof TimeoutException) {
                  return RTPResult.failure("Request timed out", 0, DEFAULT_REQUEST_TIMEOUT_MS);
              } else if (throwable instanceof CancellationException) {
                  return RTPResult.failure("Request cancelled", 0, request.getAge());
              } else {
                  VonixCore.LOGGER.error("[RTP] Error processing request {}", request.getRequestId(), throwable);
                  return RTPResult.failure("Internal error: " + throwable.getMessage(), 0, request.getAge());
              }
          });
    }

    /**
     * Internal request processing logic (placeholder for now)
     */
    private RTPResult processRTPRequestInternal(RTPRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            // This is a placeholder implementation
            // The actual implementation will integrate with:
            // - LocationSearchEngine for finding safe locations
            // - ChunkLoadingManager for efficient chunk loading
            // - SafetyValidationEngine for comprehensive safety checks
            
            // For now, return a failure to maintain interface compliance
            return RTPResult.failure("Implementation in progress", 1, System.currentTimeMillis() - startTime);
            
        } catch (Exception e) {
            VonixCore.LOGGER.error("[RTP] Error in internal processing for request {}", request.getRequestId(), e);
            return RTPResult.failure("Processing error: " + e.getMessage(), 0, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Clean up expired requests periodically
     */
    private void cleanupExpiredRequests() {
        try {
            activeRequests.entrySet().removeIf(entry -> {
                RTPRequest request = entry.getValue();
                if (!request.isValid()) {
                    UUID playerId = entry.getKey();
                    
                    // Cancel associated future
                    CompletableFuture<RTPResult> future = activeFutures.remove(playerId);
                    if (future != null) {
                        future.cancel(true);
                    }
                    
                    VonixCore.LOGGER.debug("[RTP] Cleaned up expired request {} for player {}", 
                                         request.getRequestId(), request.getPlayer().getName().getString());
                    return true;
                }
                return false;
            });
        } catch (Exception e) {
            VonixCore.LOGGER.error("[RTP] Error during cleanup", e);
        }
    }

    /**
     * Schedule a task to run on the main server thread
     */
    private void scheduleMainThreadTask(Runnable task) {
        try {
            // Get the server instance from the first active request's player
            // This is safe because we only call this method when processing active requests
            Optional<RTPRequest> activeRequest = activeRequests.values().stream().findFirst();
            if (activeRequest.isPresent() && activeRequest.get().getPlayer().getServer() != null) {
                activeRequest.get().getPlayer().getServer().execute(task);
            }
        } catch (Exception e) {
            VonixCore.LOGGER.warn("[RTP] Failed to schedule main thread task", e);
        }
    }

    /**
     * Implementation of performance statistics
     */
    private class RTPPerformanceStatsImpl implements RTPPerformanceStats {
        @Override
        public long getTotalRequests() {
            return totalRequests.get();
        }

        @Override
        public long getSuccessfulRequests() {
            return successfulRequests.get();
        }

        @Override
        public long getFailedRequests() {
            return failedRequests.get();
        }

        @Override
        public double getSuccessRate() {
            long total = getTotalRequests();
            return total > 0 ? (double) getSuccessfulRequests() / total : 0.0;
        }

        @Override
        public double getAverageProcessingTimeMs() {
            long total = getTotalRequests();
            return total > 0 ? (double) totalProcessingTime.get() / total : 0.0;
        }

        @Override
        public double getAverageChunkLoadTimeMs() {
            long total = getTotalRequests();
            return total > 0 ? (double) totalChunkLoadTime.get() / total : 0.0;
        }

        @Override
        public int getCurrentConcurrency() {
            return currentConcurrency.get();
        }

        @Override
        public int getMaxConcurrency() {
            return maxConcurrency.get();
        }

        @Override
        public long getMainThreadImpactMs() {
            return mainThreadImpact.get();
        }

        @Override
        public boolean meetsPerformanceThresholds() {
            return getMainThreadImpactMs() <= 1 && // Max 1ms main thread impact
                   getAverageProcessingTimeMs() <= 5000 && // Max 5 seconds average processing
                   getAverageChunkLoadTimeMs() <= 2000; // Max 2 seconds average chunk loading
        }

        @Override
        public String toString() {
            return String.format("RTPPerformanceStats{total=%d, success=%.2f%%, avgTime=%.1fms, concurrency=%d/%d, thresholds=%s}",
                               getTotalRequests(), getSuccessRate() * 100, getAverageProcessingTimeMs(),
                               getCurrentConcurrency(), getMaxConcurrency(), meetsPerformanceThresholds());
        }
    }
}