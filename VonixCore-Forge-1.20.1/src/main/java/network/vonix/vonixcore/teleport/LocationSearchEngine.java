package network.vonix.vonixcore.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import network.vonix.vonixcore.VonixCore;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Location search engine with retry logic, radius expansion, and caching.
 * 
 * Features:
 * - Automatic search radius expansion on failure
 * - Configurable search timeouts and retry limits
 * - Location caching with LRU eviction
 * - Integration with SpiralSearchAlgorithm for efficient searching
 * - Comprehensive error handling and logging
 * 
 * Requirements: 3.1, 3.3, 5.2
 */
public class LocationSearchEngine {
    
    private static final int DEFAULT_CACHE_SIZE = 500;
    private static final long DEFAULT_CACHE_TTL_MS = 600000; // 10 minutes
    private static final double RADIUS_EXPANSION_FACTOR = 1.5;
    private static final int MAX_RADIUS_EXPANSIONS = 3;
    
    private final SpiralSearchAlgorithm spiralSearch;
    private final SafetyValidationEngine safetyValidator;
    private final Map<String, LocationCacheEntry> locationCache;
    private final int maxCacheSize;
    private final long cacheTTLMs;
    
    public LocationSearchEngine() {
        this.spiralSearch = new SpiralSearchAlgorithm();
        this.safetyValidator = SafetyValidationEngine.getInstance();
        this.maxCacheSize = DEFAULT_CACHE_SIZE;
        this.cacheTTLMs = DEFAULT_CACHE_TTL_MS;
        
        // Initialize LRU cache
        this.locationCache = new LinkedHashMap<String, LocationCacheEntry>(maxCacheSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, LocationCacheEntry> eldest) {
                return size() > maxCacheSize || 
                       (System.currentTimeMillis() - eldest.getValue().timestamp) > cacheTTLMs;
            }
        };
    }
    
    /**
     * Search for a safe location with automatic retry and radius expansion.
     * 
     * @param world The world to search in
     * @param center The center point for the search
     * @param options Search parameters
     * @return CompletableFuture containing the safe location, or empty if not found
     */
    public CompletableFuture<Optional<BlockPos>> findSafeLocation(ServerLevel world, BlockPos center, RTPOptions options) {
        String cacheKey = generateCacheKey(world, center, options);
        
        // Check cache first
        LocationCacheEntry cached = locationCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < cacheTTLMs) {
            VonixCore.LOGGER.debug("[LocationSearch] Cache hit for {} -> {}", cacheKey, cached.location);
            return CompletableFuture.completedFuture(Optional.of(cached.location));
        }
        
        // Start search with initial radius
        return searchWithRetry(world, center, options, 0);
    }
    
    /**
     * Search with automatic retry and radius expansion.
     */
    private CompletableFuture<Optional<BlockPos>> searchWithRetry(ServerLevel world, BlockPos center, 
                                                                   RTPOptions options, int expansionCount) {
        if (expansionCount > MAX_RADIUS_EXPANSIONS) {
            VonixCore.LOGGER.warn("[LocationSearch] Max radius expansions reached, search failed");
            return CompletableFuture.completedFuture(Optional.empty());
        }
        
        // Calculate expanded radius if this is a retry
        RTPOptions expandedOptions = options;
        if (expansionCount > 0) {
            int expandedMaxRadius = (int)(options.getMaxRadius() * Math.pow(RADIUS_EXPANSION_FACTOR, expansionCount));
            expandedOptions = new RTPOptions.Builder()
                .minRadius(options.getMinRadius())
                .maxRadius(expandedMaxRadius)
                .maxSearchAttempts(options.getMaxSearchAttempts())
                .searchTimeoutMs(options.getSearchTimeoutMs())
                .allowedBiomes(options.getAllowedBiomes())
                .blockedBiomes(options.getBlockedBiomes())
                .respectWorldBorder(options.shouldRespectWorldBorder())
                .build();
            
            VonixCore.LOGGER.debug("[LocationSearch] Expanding search radius to {} (expansion #{})", 
                                 expandedMaxRadius, expansionCount);
        }
        
        // Perform spiral search
        return spiralSearch.searchLocations(world, center, expandedOptions)
            .thenCompose(candidates -> validateCandidates(world, candidates, options))
            .thenCompose(result -> {
                if (result.isPresent()) {
                    // Cache the successful location
                    String cacheKey = generateCacheKey(world, center, options);
                    locationCache.put(cacheKey, new LocationCacheEntry(result.get(), System.currentTimeMillis()));
                    
                    VonixCore.LOGGER.debug("[LocationSearch] Found safe location at {}", result.get());
                    return CompletableFuture.completedFuture(result);
                } else if (expansionCount < MAX_RADIUS_EXPANSIONS) {
                    // Retry with expanded radius
                    VonixCore.LOGGER.debug("[LocationSearch] No safe location found, retrying with expanded radius");
                    return searchWithRetry(world, center, options, expansionCount + 1);
                } else {
                    VonixCore.LOGGER.warn("[LocationSearch] Failed to find safe location after {} expansions", expansionCount);
                    return CompletableFuture.completedFuture(Optional.empty());
                }
            })
            .exceptionally(throwable -> {
                VonixCore.LOGGER.error("[LocationSearch] Error during location search", throwable);
                if (expansionCount < MAX_RADIUS_EXPANSIONS) {
                    // Retry on error
                    return searchWithRetry(world, center, options, expansionCount + 1).join();
                }
                return Optional.empty();
            });
    }
    
    /**
     * Validate candidate locations for safety.
     */
    private CompletableFuture<Optional<BlockPos>> validateCandidates(ServerLevel world, List<BlockPos> candidates, RTPOptions options) {
        if (candidates.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        
        // Validate candidates in order until we find a safe one
        return validateCandidatesRecursive(world, candidates, 0, options);
    }
    
    /**
     * Recursively validate candidates.
     */
    private CompletableFuture<Optional<BlockPos>> validateCandidatesRecursive(ServerLevel world, List<BlockPos> candidates, 
                                                                               int index, RTPOptions options) {
        if (index >= candidates.size()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        
        BlockPos candidate = candidates.get(index);
        
        // Validate this candidate
        return safetyValidator.validateLocation(world, candidate, options)
            .thenCompose(result -> {
                if (result.isSafe()) {
                    VonixCore.LOGGER.debug("[LocationSearch] Candidate {} passed safety validation", candidate);
                    return CompletableFuture.completedFuture(Optional.of(candidate));
                } else {
                    VonixCore.LOGGER.debug("[LocationSearch] Candidate {} failed safety validation: {}", candidate, result.getIssues());
                    // Try next candidate
                    return validateCandidatesRecursive(world, candidates, index + 1, options);
                }
            })
            .exceptionally(throwable -> {
                VonixCore.LOGGER.debug("[LocationSearch] Error validating candidate {}: {}", candidate, throwable.getMessage());
                // Try next candidate on error
                try {
                    return validateCandidatesRecursive(world, candidates, index + 1, options).join();
                } catch (Exception e) {
                    return Optional.empty();
                }
            });
    }
    
    /**
     * Generate cache key for location search.
     */
    private String generateCacheKey(ServerLevel world, BlockPos center, RTPOptions options) {
        return String.format("%s_%d_%d_%d_%d_%d",
                           world.dimension().location(),
                           center.getX(),
                           center.getZ(),
                           options.getMinRadius(),
                           options.getMaxRadius(),
                           options.getAllowedBiomes().hashCode());
    }
    
    /**
     * Clear the location cache.
     */
    public void clearCache() {
        synchronized (locationCache) {
            locationCache.clear();
        }
        VonixCore.LOGGER.debug("[LocationSearch] Location cache cleared");
    }
    
    /**
     * Get cache statistics.
     */
    public CacheStats getCacheStats() {
        return new CacheStats(locationCache.size(), maxCacheSize);
    }
    
    /**
     * Cache entry for locations.
     */
    private static class LocationCacheEntry {
        final BlockPos location;
        final long timestamp;
        
        LocationCacheEntry(BlockPos location, long timestamp) {
            this.location = location;
            this.timestamp = timestamp;
        }
    }
    
    /**
     * Cache statistics.
     */
    public static class CacheStats {
        private final int currentSize;
        private final int maxSize;
        
        public CacheStats(int currentSize, int maxSize) {
            this.currentSize = currentSize;
            this.maxSize = maxSize;
        }
        
        public int getCurrentSize() { return currentSize; }
        public int getMaxSize() { return maxSize; }
        public double getUtilization() { return (double) currentSize / maxSize; }
        
        @Override
        public String toString() {
            return String.format("CacheStats{size=%d/%d, utilization=%.1f%%}", 
                               currentSize, maxSize, getUtilization() * 100);
        }
    }
}
