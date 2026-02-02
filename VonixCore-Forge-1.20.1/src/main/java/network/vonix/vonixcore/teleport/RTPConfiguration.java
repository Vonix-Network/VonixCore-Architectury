package network.vonix.vonixcore.teleport;

import java.util.*;

/**
 * Configuration for optimized RTP system.
 * 
 * Provides configuration for:
 * - Distance and timing parameters
 * - Thread pool and performance tuning
 * - Biome filtering and structure preferences
 * - Safety validation settings
 * 
 * Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7
 */
public class RTPConfiguration {
    
    // Distance parameters
    private final int minDistance;
    private final int maxDistance;
    private final int initialSearchRadius;
    private final int maxSearchRadius;
    
    // Timing parameters
    private final long searchTimeoutMs;
    private final long chunkLoadTimeoutMs;
    private final int maxRetryAttempts;
    
    // Thread pool configuration
    private final int maxConcurrentRequests;
    private final int chunkLoadThreads;
    
    // Safety validation
    private final boolean checkSuffocation;
    private final boolean checkFallDamage;
    private final boolean checkLava;
    private final boolean checkVoid;
    private final boolean avoidStructures;
    private final int safetyCheckRadius;
    
    // Biome filtering
    private final Set<String> allowedBiomes;
    private final Set<String> blockedBiomes;
    private final boolean useBiomeFilter;
    
    // Caching
    private final int locationCacheSize;
    private final long cacheEntryTTLMs;
    
    private RTPConfiguration(Builder builder) {
        this.minDistance = builder.minDistance;
        this.maxDistance = builder.maxDistance;
        this.initialSearchRadius = builder.initialSearchRadius;
        this.maxSearchRadius = builder.maxSearchRadius;
        this.searchTimeoutMs = builder.searchTimeoutMs;
        this.chunkLoadTimeoutMs = builder.chunkLoadTimeoutMs;
        this.maxRetryAttempts = builder.maxRetryAttempts;
        this.maxConcurrentRequests = builder.maxConcurrentRequests;
        this.chunkLoadThreads = builder.chunkLoadThreads;
        this.checkSuffocation = builder.checkSuffocation;
        this.checkFallDamage = builder.checkFallDamage;
        this.checkLava = builder.checkLava;
        this.checkVoid = builder.checkVoid;
        this.avoidStructures = builder.avoidStructures;
        this.safetyCheckRadius = builder.safetyCheckRadius;
        this.allowedBiomes = new HashSet<>(builder.allowedBiomes);
        this.blockedBiomes = new HashSet<>(builder.blockedBiomes);
        this.useBiomeFilter = builder.useBiomeFilter;
        this.locationCacheSize = builder.locationCacheSize;
        this.cacheEntryTTLMs = builder.cacheEntryTTLMs;
    }

    // Getters
    public int getMinDistance() { return minDistance; }
    public int getMaxDistance() { return maxDistance; }
    public int getInitialSearchRadius() { return initialSearchRadius; }
    public int getMaxSearchRadius() { return maxSearchRadius; }
    public long getSearchTimeoutMs() { return searchTimeoutMs; }
    public long getChunkLoadTimeoutMs() { return chunkLoadTimeoutMs; }
    public int getMaxRetryAttempts() { return maxRetryAttempts; }
    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public int getChunkLoadThreads() { return chunkLoadThreads; }
    public boolean isCheckSuffocation() { return checkSuffocation; }
    public boolean isCheckFallDamage() { return checkFallDamage; }
    public boolean isCheckLava() { return checkLava; }
    public boolean isCheckVoid() { return checkVoid; }
    public boolean isAvoidStructures() { return avoidStructures; }
    public int getSafetyCheckRadius() { return safetyCheckRadius; }
    public Set<String> getAllowedBiomes() { return new HashSet<>(allowedBiomes); }
    public Set<String> getBlockedBiomes() { return new HashSet<>(blockedBiomes); }
    public boolean isUseBiomeFilter() { return useBiomeFilter; }
    public int getLocationCacheSize() { return locationCacheSize; }
    public long getCacheEntryTTLMs() { return cacheEntryTTLMs; }
    
    /**
     * Create default configuration.
     */
    public static RTPConfiguration createDefault() {
        return new Builder().build();
    }
    
    /**
     * Builder for RTPConfiguration.
     */
    public static class Builder {
        private int minDistance = 1000;
        private int maxDistance = 10000;
        private int initialSearchRadius = 1000;
        private int maxSearchRadius = 10000;
        private long searchTimeoutMs = 5000;
        private long chunkLoadTimeoutMs = 5000;
        private int maxRetryAttempts = 3;
        private int maxConcurrentRequests = 10;
        private int chunkLoadThreads = 8;
        private boolean checkSuffocation = true;
        private boolean checkFallDamage = true;
        private boolean checkLava = true;
        private boolean checkVoid = true;
        private boolean avoidStructures = false;
        private int safetyCheckRadius = 3;
        private Set<String> allowedBiomes = new HashSet<>();
        private Set<String> blockedBiomes = new HashSet<>();
        private boolean useBiomeFilter = false;
        private int locationCacheSize = 100;
        private long cacheEntryTTLMs = 300000;
        
        public Builder minDistance(int minDistance) { this.minDistance = minDistance; return this; }
        public Builder maxDistance(int maxDistance) { this.maxDistance = maxDistance; return this; }
        public Builder searchTimeoutMs(long timeout) { this.searchTimeoutMs = timeout; return this; }
        public Builder maxRetryAttempts(int attempts) { this.maxRetryAttempts = attempts; return this; }
        public Builder maxConcurrentRequests(int max) { this.maxConcurrentRequests = max; return this; }
        public Builder checkSuffocation(boolean check) { this.checkSuffocation = check; return this; }
        public Builder checkLava(boolean check) { this.checkLava = check; return this; }
        public Builder avoidStructures(boolean avoid) { this.avoidStructures = avoid; return this; }
        public Builder allowedBiomes(Set<String> biomes) { this.allowedBiomes = biomes; return this; }
        public Builder blockedBiomes(Set<String> biomes) { this.blockedBiomes = biomes; return this; }
        public Builder useBiomeFilter(boolean use) { this.useBiomeFilter = use; return this; }
        
        public RTPConfiguration build() {
            return new RTPConfiguration(this);
        }
    }
}
