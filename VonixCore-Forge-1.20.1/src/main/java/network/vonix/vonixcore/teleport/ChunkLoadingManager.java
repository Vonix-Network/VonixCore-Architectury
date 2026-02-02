package network.vonix.vonixcore.teleport;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import network.vonix.vonixcore.VonixCore;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages asynchronous chunk loading with temporary tickets and automatic cleanup.
 * 
 * Key features:
 * - Uses ChunkTicketType.TEMPORARY with 30-second expiration
 * - Ticket pooling and reuse for concurrent requests to same chunks
 * - Background cleanup task for expired tickets
 * - Load balancing across multiple threads
 * - Comprehensive error handling and timeout management
 * 
 * Requirements: 2.1, 2.2, 2.3
 */
public class ChunkLoadingManager {
    
    // Configuration constants
    private static final int TICKET_EXPIRATION_SECONDS = 30;
    private static final int CHUNK_LOAD_TIMEOUT_MS = 5000; // 5 seconds per chunk
    private static final int CLEANUP_INTERVAL_MS = 10000; // 10 seconds
    private static final int MAX_CONCURRENT_LOADS = 8;
    
    // Custom ticket type for RTP chunk loading with 30-second expiration
    private static final TicketType<ChunkPos> RTP_TEMPORARY_TICKET = 
        TicketType.create("vonixcore_rtp_temp", (a, b) -> 0, TICKET_EXPIRATION_SECONDS * 20);
    
    // Ticket tracking and management
    private final Map<ChunkPos, ChunkTicket> activeTickets = new ConcurrentHashMap<>();
    private final Map<ChunkPos, CompletableFuture<ChunkAccess>> pendingLoads = new ConcurrentHashMap<>();
    
    // Thread management
    private final ExecutorService chunkLoadExecutor;
    private final ScheduledExecutorService cleanupExecutor;
    private final Semaphore loadLimiter;
    
    // Performance tracking
    private final AtomicLong totalChunkLoads = new AtomicLong(0);
    private final AtomicLong successfulLoads = new AtomicLong(0);
    private final AtomicLong failedLoads = new AtomicLong(0);
    private final AtomicLong totalLoadTime = new AtomicLong(0);
    private final AtomicInteger currentConcurrentLoads = new AtomicInteger(0);
    private final AtomicInteger maxConcurrentLoads = new AtomicInteger(0);
    
    // Singleton instance
    private static volatile ChunkLoadingManager instance;
    private volatile boolean shutdown = false;
    
    private ChunkLoadingManager() {
        // Initialize thread pool for chunk loading operations
        this.chunkLoadExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_LOADS, r -> {
            Thread t = new Thread(r, "VonixCore-ChunkLoader");
            t.setDaemon(true);
            return t;
        });
        
        // Initialize cleanup executor
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VonixCore-ChunkCleanup");
            t.setDaemon(true);
            return t;
        });
        
        // Initialize load limiter
        this.loadLimiter = new Semaphore(MAX_CONCURRENT_LOADS, true);
        
        // Start periodic cleanup task
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredTickets, 
                                          CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        VonixCore.LOGGER.info("[ChunkLoading] ChunkLoadingManager initialized with {} max concurrent loads", 
                            MAX_CONCURRENT_LOADS);
    }
    
    /**
     * Get the singleton instance of the chunk loading manager
     */
    public static ChunkLoadingManager getInstance() {
        if (instance == null) {
            synchronized (ChunkLoadingManager.class) {
                if (instance == null) {
                    instance = new ChunkLoadingManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Load a chunk asynchronously with temporary ticket management.
     * Reuses existing tickets for chunks requested by multiple operations.
     * 
     * @param world The world containing the chunk
     * @param chunkPos The position of the chunk to load
     * @return CompletableFuture that completes with the loaded chunk or null on failure
     */
    public CompletableFuture<ChunkAccess> loadChunkAsync(ServerLevel world, ChunkPos chunkPos) {
        if (shutdown) {
            return CompletableFuture.completedFuture(null);
        }
        
        totalChunkLoads.incrementAndGet();
        
        // Check if there's already a pending load for this chunk
        CompletableFuture<ChunkAccess> existingLoad = pendingLoads.get(chunkPos);
        if (existingLoad != null && !existingLoad.isDone()) {
            VonixCore.LOGGER.debug("[ChunkLoading] Reusing existing load for chunk {}", chunkPos);
            return existingLoad;
        }
        
        // Create new load operation
        CompletableFuture<ChunkAccess> loadFuture = new CompletableFuture<>();
        pendingLoads.put(chunkPos, loadFuture);
        
        // Submit load operation to executor
        CompletableFuture.runAsync(() -> {
            try {
                // Acquire load permit
                loadLimiter.acquire();
                
                // Update concurrency tracking
                int current = currentConcurrentLoads.incrementAndGet();
                maxConcurrentLoads.updateAndGet(max -> Math.max(max, current));
                
                long startTime = System.currentTimeMillis();
                ChunkAccess result = loadChunkInternal(world, chunkPos);
                long loadTime = System.currentTimeMillis() - startTime;
                
                totalLoadTime.addAndGet(loadTime);
                
                if (result != null) {
                    successfulLoads.incrementAndGet();
                    loadFuture.complete(result);
                } else {
                    failedLoads.incrementAndGet();
                    loadFuture.complete(null);
                }
                
            } catch (Exception e) {
                failedLoads.incrementAndGet();
                VonixCore.LOGGER.error("[ChunkLoading] Error loading chunk {}: {}", chunkPos, e.getMessage());
                loadFuture.complete(null);
            } finally {
                // Release load permit and update tracking
                loadLimiter.release();
                currentConcurrentLoads.decrementAndGet();
                pendingLoads.remove(chunkPos);
            }
        }, chunkLoadExecutor);
        
        // Add timeout handling
        return loadFuture.orTimeout(CHUNK_LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .exceptionally(throwable -> {
                            if (throwable instanceof TimeoutException) {
                                VonixCore.LOGGER.warn("[ChunkLoading] Chunk load timeout for {}", chunkPos);
                            }
                            failedLoads.incrementAndGet();
                            return null;
                        });
    }
    
    /**
     * Internal chunk loading implementation that handles ticket management.
     * Must be called from the chunk loading executor.
     */
    private ChunkAccess loadChunkInternal(ServerLevel world, ChunkPos chunkPos) {
        CompletableFuture<ChunkAccess> chunkFuture = new CompletableFuture<>();
        
        // Schedule ticket operations on main thread (required by Forge)
        scheduleMainThreadTask(world, () -> {
            try {
                ServerChunkCache chunkSource = world.getChunkSource();
                
                // Get or create ticket for this chunk
                ChunkTicket ticket = getOrCreateTicket(chunkPos);
                
                // Add region ticket
                chunkSource.addRegionTicket(RTP_TEMPORARY_TICKET, chunkPos, 0, chunkPos);
                
                // Request chunk with SURFACE status (sufficient for safety checks)
                chunkSource.getChunkFuture(chunkPos.x, chunkPos.z, ChunkStatus.SURFACE, true)
                          .orTimeout(CHUNK_LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                          .thenAccept(either -> {
                              ChunkAccess chunk = either.left().orElse(null);
                              chunkFuture.complete(chunk);
                          })
                          .exceptionally(ex -> {
                              VonixCore.LOGGER.warn("[ChunkLoading] Chunk load failed for {}: {}", 
                                                   chunkPos, ex.getMessage());
                              chunkFuture.complete(null);
                              return null;
                          });
                          
            } catch (Exception e) {
                VonixCore.LOGGER.error("[ChunkLoading] Error in ticket management for {}: {}", 
                                     chunkPos, e.getMessage());
                chunkFuture.complete(null);
            }
        });
        
        try {
            return chunkFuture.get(CHUNK_LOAD_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            VonixCore.LOGGER.warn("[ChunkLoading] Failed to load chunk {}: {}", chunkPos, e.getMessage());
            return null;
        }
    }
    
    /**
     * Get or create a ticket for the specified chunk position.
     * Implements ticket pooling and reuse for concurrent requests.
     */
    private ChunkTicket getOrCreateTicket(ChunkPos chunkPos) {
        return activeTickets.computeIfAbsent(chunkPos, pos -> {
            ChunkTicket ticket = new ChunkTicket(pos, System.currentTimeMillis());
            VonixCore.LOGGER.debug("[ChunkLoading] Created new ticket for chunk {}", pos);
            return ticket;
        });
    }
    
    /**
     * Release a chunk ticket manually (optional, tickets auto-expire).
     * This can be called to release tickets early if the chunk is no longer needed.
     */
    public void releaseChunkTicket(ServerLevel world, ChunkPos chunkPos) {
        ChunkTicket ticket = activeTickets.remove(chunkPos);
        if (ticket != null) {
            scheduleMainThreadTask(world, () -> {
                try {
                    ServerChunkCache chunkSource = world.getChunkSource();
                    chunkSource.removeRegionTicket(RTP_TEMPORARY_TICKET, chunkPos, 0, chunkPos);
                    VonixCore.LOGGER.debug("[ChunkLoading] Released ticket for chunk {}", chunkPos);
                } catch (Exception e) {
                    VonixCore.LOGGER.warn("[ChunkLoading] Error releasing ticket for {}: {}", 
                                        chunkPos, e.getMessage());
                }
            });
        }
    }
    
    /**
     * Background cleanup task for expired tickets.
     * Runs every 10 seconds to clean up tickets older than 30 seconds.
     */
    private void cleanupExpiredTickets() {
        if (shutdown) {
            return;
        }
        
        try {
            long currentTime = System.currentTimeMillis();
            long expirationThreshold = TICKET_EXPIRATION_SECONDS * 1000L;
            
            activeTickets.entrySet().removeIf(entry -> {
                ChunkTicket ticket = entry.getValue();
                boolean expired = (currentTime - ticket.getCreationTime()) > expirationThreshold;
                
                if (expired) {
                    VonixCore.LOGGER.debug("[ChunkLoading] Cleaned up expired ticket for chunk {}", 
                                         entry.getKey());
                }
                
                return expired;
            });
            
        } catch (Exception e) {
            VonixCore.LOGGER.error("[ChunkLoading] Error during ticket cleanup", e);
        }
    }
    
    /**
     * Schedule a task to run on the main server thread.
     * Required for chunk ticket operations.
     */
    private void scheduleMainThreadTask(ServerLevel world, Runnable task) {
        try {
            if (world.getServer() != null) {
                world.getServer().execute(task);
            }
        } catch (Exception e) {
            VonixCore.LOGGER.warn("[ChunkLoading] Failed to schedule main thread task", e);
        }
    }
    
    /**
     * Get performance statistics for monitoring
     */
    public ChunkLoadingStats getStats() {
        return new ChunkLoadingStatsImpl();
    }
    
    /**
     * Shutdown the chunk loading manager gracefully
     */
    public CompletableFuture<Void> shutdown(long timeoutMs) {
        if (shutdown) {
            return CompletableFuture.completedFuture(null);
        }
        
        shutdown = true;
        VonixCore.LOGGER.info("[ChunkLoading] Shutting down ChunkLoadingManager...");
        
        return CompletableFuture.runAsync(() -> {
            try {
                // Clear active tickets
                activeTickets.clear();
                pendingLoads.clear();
                
                // Shutdown executors
                cleanupExecutor.shutdown();
                chunkLoadExecutor.shutdown();
                
                // Wait for termination
                if (!chunkLoadExecutor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                    VonixCore.LOGGER.warn("[ChunkLoading] Executor did not terminate within {}ms, forcing shutdown", timeoutMs);
                    chunkLoadExecutor.shutdownNow();
                }
                
                if (!cleanupExecutor.awaitTermination(5000, TimeUnit.MILLISECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
                
                VonixCore.LOGGER.info("[ChunkLoading] ChunkLoadingManager shutdown complete");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                VonixCore.LOGGER.error("[ChunkLoading] Shutdown interrupted", e);
            }
        });
    }
    
    /**
     * Represents a chunk ticket with creation time for expiration tracking
     */
    private static class ChunkTicket {
        private final ChunkPos chunkPos;
        private final long creationTime;
        private final AtomicInteger referenceCount;
        
        public ChunkTicket(ChunkPos chunkPos, long creationTime) {
            this.chunkPos = chunkPos;
            this.creationTime = creationTime;
            this.referenceCount = new AtomicInteger(1);
        }
        
        public ChunkPos getChunkPos() { return chunkPos; }
        public long getCreationTime() { return creationTime; }
        public int incrementReference() { return referenceCount.incrementAndGet(); }
        public int decrementReference() { return referenceCount.decrementAndGet(); }
        public int getReferenceCount() { return referenceCount.get(); }
    }
    
    /**
     * Performance statistics interface
     */
    public interface ChunkLoadingStats {
        long getTotalChunkLoads();
        long getSuccessfulLoads();
        long getFailedLoads();
        double getSuccessRate();
        double getAverageLoadTimeMs();
        int getCurrentConcurrentLoads();
        int getMaxConcurrentLoads();
        int getActiveTickets();
        boolean meetsPerformanceThresholds();
    }
    
    /**
     * Implementation of chunk loading statistics
     */
    private class ChunkLoadingStatsImpl implements ChunkLoadingStats {
        @Override
        public long getTotalChunkLoads() {
            return totalChunkLoads.get();
        }
        
        @Override
        public long getSuccessfulLoads() {
            return successfulLoads.get();
        }
        
        @Override
        public long getFailedLoads() {
            return failedLoads.get();
        }
        
        @Override
        public double getSuccessRate() {
            long total = getTotalChunkLoads();
            return total > 0 ? (double) getSuccessfulLoads() / total : 0.0;
        }
        
        @Override
        public double getAverageLoadTimeMs() {
            long total = getTotalChunkLoads();
            return total > 0 ? (double) totalLoadTime.get() / total : 0.0;
        }
        
        @Override
        public int getCurrentConcurrentLoads() {
            return currentConcurrentLoads.get();
        }
        
        @Override
        public int getMaxConcurrentLoads() {
            return maxConcurrentLoads.get();
        }
        
        @Override
        public int getActiveTickets() {
            return activeTickets.size();
        }
        
        @Override
        public boolean meetsPerformanceThresholds() {
            return getAverageLoadTimeMs() <= 2000 && // Max 2 seconds average load time
                   getSuccessRate() >= 0.95; // Min 95% success rate
        }
        
        @Override
        public String toString() {
            return String.format("ChunkLoadingStats{total=%d, success=%.2f%%, avgTime=%.1fms, concurrent=%d/%d, tickets=%d}",
                               getTotalChunkLoads(), getSuccessRate() * 100, getAverageLoadTimeMs(),
                               getCurrentConcurrentLoads(), getMaxConcurrentLoads(), getActiveTickets());
        }
    }
}