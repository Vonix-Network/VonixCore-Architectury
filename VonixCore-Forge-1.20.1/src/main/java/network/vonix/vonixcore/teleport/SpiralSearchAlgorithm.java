package network.vonix.vonixcore.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import network.vonix.vonixcore.VonixCore;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Implements an optimized spiral search algorithm for finding safe RTP locations.
 * 
 * Key features:
 * - Spiral search pattern starting from random center points
 * - Biome blacklist/whitelist filtering to skip expensive chunk loads
 * - Distance constraints and world border awareness
 * - Intelligent chunk loading optimization
 * - Configurable search parameters and retry logic
 * 
 * Requirements: 3.1, 3.3, 3.5
 */
public class SpiralSearchAlgorithm {
    
    // Spiral pattern offsets for efficient area coverage
    private static final int[][] SPIRAL_OFFSETS = {
        {0, 0},   // Center
        {0, 1}, {1, 0}, {0, -1}, {-1, 0},  // First ring
        {1, 1}, {1, -1}, {-1, -1}, {-1, 1}, // Diagonal corners
        {0, 2}, {2, 0}, {0, -2}, {-2, 0},  // Second ring cardinal
        {2, 1}, {1, 2}, {-1, 2}, {-2, 1}, {-2, -1}, {-1, -2}, {1, -2}, {2, -1} // Second ring mixed
    };
    
    // Configuration constants
    private static final int DEFAULT_CHUNK_SAMPLE_SIZE = 16; // Sample every 16 blocks
    private static final int MAX_SPIRAL_RADIUS = 64; // Maximum spiral radius in chunks
    private static final int BIOME_CHECK_CACHE_SIZE = 1000;
    private static final long BIOME_CACHE_EXPIRE_MS = 300000; // 5 minutes
    
    private final ChunkLoadingManager chunkManager;
    private final RandomSource random;
    
    // Biome filtering cache to avoid repeated chunk loads for biome checks
    private final Map<ChunkPos, BiomeCacheEntry> biomeCache = new LinkedHashMap<ChunkPos, BiomeCacheEntry>(BIOME_CHECK_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ChunkPos, BiomeCacheEntry> eldest) {
            return size() > BIOME_CHECK_CACHE_SIZE || 
                   (System.currentTimeMillis() - eldest.getValue().timestamp) > BIOME_CACHE_EXPIRE_MS;
        }
    };
    
    public SpiralSearchAlgorithm() {
        this.chunkManager = ChunkLoadingManager.getInstance();
        this.random = RandomSource.create();
    }
    
    /**
     * Search for safe locations using spiral pattern with biome filtering.
     * 
     * @param world The world to search in
     * @param center The center point for the search
     * @param options Search parameters including biome filters and distance constraints
     * @return CompletableFuture containing list of potential locations
     */
    public CompletableFuture<List<BlockPos>> searchLocations(ServerLevel world, BlockPos center, RTPOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            List<BlockPos> candidates = new ArrayList<>();
            Set<ChunkPos> checkedChunks = new HashSet<>();
            
            long startTime = System.currentTimeMillis();
            int attempts = 0;
            int maxAttempts = options.getMaxSearchAttempts();
            
            VonixCore.LOGGER.debug("[SpiralSearch] Starting search from {} with options: {}", center, options);
            
            // Generate multiple random center points within the allowed radius
            List<BlockPos> searchCenters = generateSearchCenters(world, center, options, 3);
            
            for (BlockPos searchCenter : searchCenters) {
                if (attempts >= maxAttempts) break;
                if (System.currentTimeMillis() - startTime > options.getSearchTimeoutMs()) break;
                
                List<BlockPos> centerCandidates = searchFromCenter(world, searchCenter, options, checkedChunks, maxAttempts - attempts);
                candidates.addAll(centerCandidates);
                attempts += centerCandidates.size();
                
                // If we have enough candidates, we can stop early
                if (candidates.size() >= 10) break;
            }
            
            long searchTime = System.currentTimeMillis() - startTime;
            VonixCore.LOGGER.debug("[SpiralSearch] Search completed in {}ms, found {} candidates from {} attempts", 
                                 searchTime, candidates.size(), attempts);
            
            return candidates;
        });
    }
    
    /**
     * Generate multiple random search centers within the allowed radius range.
     */
    private List<BlockPos> generateSearchCenters(ServerLevel world, BlockPos originalCenter, RTPOptions options, int count) {
        List<BlockPos> centers = new ArrayList<>();
        WorldBorder worldBorder = world.getWorldBorder();
        
        for (int i = 0; i < count; i++) {
            // Generate random distance within min/max radius
            double distance = options.getMinRadius() + 
                            random.nextDouble() * (options.getMaxRadius() - options.getMinRadius());
            
            // Generate random angle
            double angle = random.nextDouble() * 2 * Math.PI;
            
            // Calculate position
            int x = originalCenter.getX() + (int)(Math.cos(angle) * distance);
            int z = originalCenter.getZ() + (int)(Math.sin(angle) * distance);
            
            BlockPos candidate = new BlockPos(x, originalCenter.getY(), z);
            
            // Check world border if enabled
            if (options.shouldRespectWorldBorder() && !worldBorder.isWithinBounds(candidate)) {
                // Try to adjust position to be within border
                candidate = adjustToWorldBorder(candidate, worldBorder);
                if (candidate == null) continue; // Skip if can't fit in border
            }
            
            centers.add(candidate);
        }
        
        return centers;
    }
    
    /**
     * Adjust a position to be within world border constraints.
     */
    private BlockPos adjustToWorldBorder(BlockPos pos, WorldBorder border) {
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();
        double size = border.getSize() / 2.0;
        
        double minX = centerX - size + 16; // 16 block buffer
        double maxX = centerX + size - 16;
        double minZ = centerZ - size + 16;
        double maxZ = centerZ + size - 16;
        
        if (maxX <= minX || maxZ <= minZ) return null; // Border too small
        
        int adjustedX = (int) Math.max(minX, Math.min(maxX, pos.getX()));
        int adjustedZ = (int) Math.max(minZ, Math.min(maxZ, pos.getZ()));
        
        return new BlockPos(adjustedX, pos.getY(), adjustedZ);
    }
    
    /**
     * Search for locations using spiral pattern from a specific center point.
     */
    private List<BlockPos> searchFromCenter(ServerLevel world, BlockPos center, RTPOptions options, 
                                          Set<ChunkPos> globalCheckedChunks, int maxAttempts) {
        List<BlockPos> candidates = new ArrayList<>();
        Set<ChunkPos> localCheckedChunks = new HashSet<>();
        
        // Calculate search radius in chunks
        int maxRadiusChunks = Math.min(MAX_SPIRAL_RADIUS, options.getMaxRadius() / 16);
        
        // Spiral search pattern
        for (int radius = 0; radius <= maxRadiusChunks && candidates.size() < maxAttempts; radius++) {
            List<ChunkPos> ringChunks = generateSpiralRing(center, radius);
            
            for (ChunkPos chunkPos : ringChunks) {
                if (candidates.size() >= maxAttempts) break;
                if (globalCheckedChunks.contains(chunkPos) || localCheckedChunks.contains(chunkPos)) continue;
                
                localCheckedChunks.add(chunkPos);
                globalCheckedChunks.add(chunkPos);
                
                // Pre-filter by biome if possible (using cache or quick check)
                if (!passesInitialBiomeFilter(world, chunkPos, options)) {
                    VonixCore.LOGGER.debug("[SpiralSearch] Skipping chunk {} due to biome filter", chunkPos);
                    continue;
                }
                
                // Check distance constraints
                BlockPos chunkCenter = new BlockPos(chunkPos.x * 16 + 8, center.getY(), chunkPos.z * 16 + 8);
                double distance = center.distSqr(chunkCenter);
                double minDistSq = options.getMinRadius() * options.getMinRadius();
                double maxDistSq = options.getMaxRadius() * options.getMaxRadius();
                
                if (distance < minDistSq || distance > maxDistSq) continue;
                
                // Generate candidate positions within this chunk
                List<BlockPos> chunkCandidates = generateChunkCandidates(chunkCenter, DEFAULT_CHUNK_SAMPLE_SIZE);
                candidates.addAll(chunkCandidates);
            }
        }
        
        return candidates;
    }
    
    /**
     * Generate spiral ring of chunk positions at the specified radius.
     */
    private List<ChunkPos> generateSpiralRing(BlockPos center, int radius) {
        List<ChunkPos> ring = new ArrayList<>();
        ChunkPos centerChunk = new ChunkPos(center);
        
        if (radius == 0) {
            ring.add(centerChunk);
            return ring;
        }
        
        // Generate ring using spiral pattern
        for (int i = 0; i < SPIRAL_OFFSETS.length && ring.size() < radius * 8; i++) {
            int[] offset = SPIRAL_OFFSETS[i % SPIRAL_OFFSETS.length];
            int scaledX = offset[0] * radius;
            int scaledZ = offset[1] * radius;
            
            ChunkPos ringChunk = new ChunkPos(centerChunk.x + scaledX, centerChunk.z + scaledZ);
            if (!ring.contains(ringChunk)) {
                ring.add(ringChunk);
            }
        }
        
        // Fill in additional positions for larger radii
        if (radius > 1) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) == radius || Math.abs(dz) == radius) {
                        ChunkPos ringChunk = new ChunkPos(centerChunk.x + dx, centerChunk.z + dz);
                        if (!ring.contains(ringChunk)) {
                            ring.add(ringChunk);
                        }
                    }
                }
            }
        }
        
        // Shuffle for randomization
        Collections.shuffle(ring, new Random(ThreadLocalRandom.current().nextLong()));
        
        return ring;
    }
    
    /**
     * Initial biome filtering using cache or quick heuristics.
     * This avoids expensive chunk loading for obviously unsuitable chunks.
     */
    private boolean passesInitialBiomeFilter(ServerLevel world, ChunkPos chunkPos, RTPOptions options) {
        // If no biome restrictions, pass all chunks
        if (options.getAllowedBiomes().isEmpty() && options.getBlockedBiomes().isEmpty()) {
            return true;
        }
        
        // Check cache first
        BiomeCacheEntry cached = biomeCache.get(chunkPos);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < BIOME_CACHE_EXPIRE_MS) {
            return evaluateBiomeFilter(cached.biomeId, options);
        }
        
        // Try to get biome without loading chunk (if chunk is already loaded)
        try {
            ChunkAccess chunk = world.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
            if (chunk != null) {
                // Sample biome from chunk center
                BlockPos samplePos = new BlockPos(chunkPos.x * 16 + 8, 64, chunkPos.z * 16 + 8);
                Holder<Biome> biomeHolder = chunk.getNoiseBiome(samplePos.getX() >> 2, samplePos.getY() >> 2, samplePos.getZ() >> 2);
                
                String biomeId = getBiomeId(biomeHolder);
                biomeCache.put(chunkPos, new BiomeCacheEntry(biomeId, System.currentTimeMillis()));
                
                return evaluateBiomeFilter(biomeId, options);
            }
        } catch (Exception e) {
            VonixCore.LOGGER.debug("[SpiralSearch] Error checking biome for chunk {}: {}", chunkPos, e.getMessage());
        }
        
        // If we can't determine biome without loading, assume it passes (will be checked later during safety validation)
        return true;
    }
    
    /**
     * Evaluate biome against whitelist/blacklist filters.
     */
    private boolean evaluateBiomeFilter(String biomeId, RTPOptions options) {
        // Check blacklist first (more restrictive)
        if (!options.getBlockedBiomes().isEmpty() && options.getBlockedBiomes().contains(biomeId)) {
            return false;
        }
        
        // Check whitelist if specified
        if (!options.getAllowedBiomes().isEmpty() && !options.getAllowedBiomes().contains(biomeId)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Get biome identifier string from biome holder.
     */
    private String getBiomeId(Holder<Biome> biomeHolder) {
        try {
            return biomeHolder.unwrapKey()
                             .map(key -> key.location().toString())
                             .orElse("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }
    
    /**
     * Generate candidate positions within a chunk for testing.
     */
    private List<BlockPos> generateChunkCandidates(BlockPos chunkCenter, int sampleSize) {
        List<BlockPos> candidates = new ArrayList<>();
        
        // Generate random positions within the chunk
        for (int i = 0; i < Math.min(sampleSize, 8); i++) {
            int offsetX = random.nextInt(16) - 8; // -8 to +7
            int offsetZ = random.nextInt(16) - 8;
            
            BlockPos candidate = chunkCenter.offset(offsetX, 0, offsetZ);
            candidates.add(candidate);
        }
        
        return candidates;
    }
    
    /**
     * Verify that a location passes comprehensive biome filtering.
     * This method should be called during safety validation with the chunk already loaded.
     */
    public CompletableFuture<Boolean> verifyBiomeFilter(ServerLevel world, BlockPos pos, RTPOptions options) {
        if (options.getAllowedBiomes().isEmpty() && options.getBlockedBiomes().isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                ChunkPos chunkPos = new ChunkPos(pos);
                
                // Load chunk if necessary
                return chunkManager.loadChunkAsync(world, chunkPos)
                    .thenApply(chunk -> {
                        if (chunk == null) return false;
                        
                        try {
                            // Sample biome at the exact position
                            Holder<Biome> biomeHolder = chunk.getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
                            String biomeId = getBiomeId(biomeHolder);
                            
                            // Update cache
                            biomeCache.put(chunkPos, new BiomeCacheEntry(biomeId, System.currentTimeMillis()));
                            
                            boolean passes = evaluateBiomeFilter(biomeId, options);
                            VonixCore.LOGGER.debug("[SpiralSearch] Biome filter for {} ({}): {}", pos, biomeId, passes);
                            
                            return passes;
                        } catch (Exception e) {
                            VonixCore.LOGGER.warn("[SpiralSearch] Error verifying biome filter for {}: {}", pos, e.getMessage());
                            return false;
                        }
                    })
                    .join(); // Block since we're already in async context
                    
            } catch (Exception e) {
                VonixCore.LOGGER.error("[SpiralSearch] Error in biome verification for {}: {}", pos, e.getMessage());
                return false;
            }
        });
    }
    
    /**
     * Get search statistics for monitoring and optimization.
     */
    public SearchStats getStats() {
        return new SearchStats(biomeCache.size());
    }
    
    /**
     * Clear biome cache (useful for testing or memory management).
     */
    public void clearCache() {
        synchronized (biomeCache) {
            biomeCache.clear();
        }
        VonixCore.LOGGER.debug("[SpiralSearch] Biome cache cleared");
    }
    
    /**
     * Cache entry for biome information.
     */
    private static class BiomeCacheEntry {
        final String biomeId;
        final long timestamp;
        
        BiomeCacheEntry(String biomeId, long timestamp) {
            this.biomeId = biomeId;
            this.timestamp = timestamp;
        }
    }
    
    /**
     * Search statistics for monitoring.
     */
    public static class SearchStats {
        private final int biomeCacheSize;
        
        public SearchStats(int biomeCacheSize) {
            this.biomeCacheSize = biomeCacheSize;
        }
        
        public int getBiomeCacheSize() { return biomeCacheSize; }
        
        @Override
        public String toString() {
            return String.format("SearchStats{biomeCacheSize=%d}", biomeCacheSize);
        }
    }
}