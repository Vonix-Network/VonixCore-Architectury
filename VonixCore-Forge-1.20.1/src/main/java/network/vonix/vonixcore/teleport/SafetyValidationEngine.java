package network.vonix.vonixcore.teleport;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.tags.BlockTags;
import network.vonix.vonixcore.VonixCore;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive safety validation engine for RTP locations.
 * 
 * Performs multi-layered safety checks including:
 * - Block safety validation (2-block clearance, solid ground)
 * - Environmental hazard detection (lava, fire, cactus within 3 blocks)
 * - Structure validation with configurable preferences
 * 
 * Requirements: 3.4, 8.1, 8.2, 8.3, 8.4, 8.5
 */
public class SafetyValidationEngine {
    
    // Configuration constants
    private static final int CLEARANCE_HEIGHT = 2; // Blocks above player position
    private static final int HAZARD_CHECK_RADIUS = 3; // Blocks to check around player
    private static final int GROUND_CHECK_DEPTH = 3; // Blocks below to verify solid ground
    private static final int FALL_DAMAGE_CHECK_DEPTH = 10; // Check for dangerous falls
    private static final int VOID_PROXIMITY_THRESHOLD = 5; // Y level threshold for void proximity
    
    // Dangerous blocks that should be avoided
    private static final Set<Block> HAZARDOUS_BLOCKS = Set.of(
        Blocks.LAVA,
        Blocks.FIRE,
        Blocks.SOUL_FIRE,
        Blocks.MAGMA_BLOCK,
        Blocks.CACTUS,
        Blocks.SWEET_BERRY_BUSH,
        Blocks.WITHER_ROSE,
        Blocks.CAMPFIRE,
        Blocks.SOUL_CAMPFIRE,
        Blocks.POWDER_SNOW
    );
    
    // Blocks that cause suffocation
    private static final Set<Block> SUFFOCATION_BLOCKS = Set.of(
        Blocks.STONE,
        Blocks.DIRT,
        Blocks.SAND,
        Blocks.GRAVEL,
        Blocks.COBBLESTONE,
        Blocks.BEDROCK,
        Blocks.OBSIDIAN,
        Blocks.NETHERRACK,
        Blocks.END_STONE
    );
    
    // Solid blocks that can support a player
    private static final Set<Block> SOLID_GROUND_BLOCKS = Set.of(
        Blocks.STONE,
        Blocks.DIRT,
        Blocks.GRASS_BLOCK,
        Blocks.SAND,
        Blocks.GRAVEL,
        Blocks.COBBLESTONE,
        Blocks.NETHERRACK,
        Blocks.END_STONE,
        Blocks.SANDSTONE,
        Blocks.DEEPSLATE,
        Blocks.ANDESITE,
        Blocks.GRANITE,
        Blocks.DIORITE
    );
    
    // Thread management
    private final ExecutorService validationExecutor;
    private final ChunkLoadingManager chunkLoadingManager;
    
    // Performance tracking
    private final AtomicLong totalValidations = new AtomicLong(0);
    private final AtomicLong safeLocations = new AtomicLong(0);
    private final AtomicLong unsafeLocations = new AtomicLong(0);
    private final AtomicLong totalValidationTime = new AtomicLong(0);
    
    // Caching for performance optimization
    private final Map<BlockPos, SafetyResult> validationCache = new ConcurrentHashMap<>();
    private final long cacheExpirationMs = 300000; // 5 minutes
    
    // Singleton instance
    private static volatile SafetyValidationEngine instance;
    private volatile boolean shutdown = false;
    
    private SafetyValidationEngine() {
        this.validationExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "VonixCore-SafetyValidator");
            t.setDaemon(true);
            return t;
        });
        this.chunkLoadingManager = ChunkLoadingManager.getInstance();
        
        VonixCore.LOGGER.info("[SafetyValidation] SafetyValidationEngine initialized");
    }
    
    /**
     * Get the singleton instance of the safety validation engine
     */
    public static SafetyValidationEngine getInstance() {
        if (instance == null) {
            synchronized (SafetyValidationEngine.class) {
                if (instance == null) {
                    instance = new SafetyValidationEngine();
                }
            }
        }
        return instance;
    }
    
    /**
     * Validate a location for teleportation safety.
     * Performs comprehensive multi-layer safety checks.
     * 
     * @param world The world containing the location
     * @param location The location to validate
     * @param options RTP options containing safety preferences
     * @return CompletableFuture containing the safety validation result
     */
    public CompletableFuture<SafetyResult> validateLocation(ServerLevel world, BlockPos location, RTPOptions options) {
        if (shutdown) {
            return CompletableFuture.completedFuture(SafetyResult.unsafe(location, "System shutdown"));
        }
        
        totalValidations.incrementAndGet();
        long startTime = System.currentTimeMillis();
        
        // Check cache first
        SafetyResult cached = getCachedResult(location);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        
        return CompletableFuture
            .supplyAsync(() -> performSafetyValidation(world, location, options), validationExecutor)
            .thenApply(result -> {
                // Update performance metrics
                long validationTime = System.currentTimeMillis() - startTime;
                totalValidationTime.addAndGet(validationTime);
                
                if (result.isSafe()) {
                    safeLocations.incrementAndGet();
                } else {
                    unsafeLocations.incrementAndGet();
                }
                
                // Cache result
                cacheResult(location, result);
                
                return result;
            })
            .exceptionally(throwable -> {
                VonixCore.LOGGER.error("[SafetyValidation] Error validating location {}: {}", 
                                     location, throwable.getMessage());
                unsafeLocations.incrementAndGet();
                return SafetyResult.unsafe(location, "Validation error: " + throwable.getMessage());
            });
    }
    
    /**
     * Perform the actual safety validation checks
     */
    private SafetyResult performSafetyValidation(ServerLevel world, BlockPos location, RTPOptions options) {
        List<SafetyIssue> issues = new ArrayList<>();
        double safetyScore = 100.0;
        
        try {
            // Ensure chunk is loaded
            ChunkAccess chunk = chunkLoadingManager.loadChunkAsync(world, 
                new net.minecraft.world.level.ChunkPos(location)).join();
            
            if (chunk == null) {
                return SafetyResult.unsafe(location, "Unable to load chunk");
            }
            
            // Layer 1: Block Safety Validation
            SafetyCheckResult blockSafety = checkBlockSafety(world, location);
            issues.addAll(blockSafety.getIssues());
            safetyScore -= blockSafety.getSafetyPenalty();
            
            // Layer 2: Environmental Hazard Detection
            SafetyCheckResult hazardCheck = checkEnvironmentalHazards(world, location, options.getSafetyCheckRadius());
            issues.addAll(hazardCheck.getIssues());
            safetyScore -= hazardCheck.getSafetyPenalty();
            
            // Layer 3: Structure Validation (if enabled)
            if (options.shouldAvoidStructures()) {
                SafetyCheckResult structureCheck = checkStructureConstraints(world, location);
                issues.addAll(structureCheck.getIssues());
                safetyScore -= structureCheck.getSafetyPenalty();
            }
            
            // Layer 4: World Boundary Validation
            SafetyCheckResult boundaryCheck = checkWorldBoundaries(world, location);
            issues.addAll(boundaryCheck.getIssues());
            safetyScore -= boundaryCheck.getSafetyPenalty();
            
            // Determine overall safety
            boolean isSafe = issues.isEmpty() || safetyScore >= 70.0;
            
            return new SafetyResult(isSafe, location, issues, Math.max(0, safetyScore));
            
        } catch (Exception e) {
            VonixCore.LOGGER.error("[SafetyValidation] Exception during validation of {}: {}", 
                                 location, e.getMessage());
            return SafetyResult.unsafe(location, "Validation exception: " + e.getMessage());
        }
    }
    
    /**
     * Check block safety: 2-block clearance above, solid ground below
     */
    private SafetyCheckResult checkBlockSafety(ServerLevel world, BlockPos location) {
        List<SafetyIssue> issues = new ArrayList<>();
        double penalty = 0.0;
        
        // Check clearance above player (head and feet level)
        for (int y = 0; y < CLEARANCE_HEIGHT; y++) {
            BlockPos checkPos = location.above(y);
            BlockState blockState = world.getBlockState(checkPos);
            Block block = blockState.getBlock();
            
            // Check for suffocation risk
            if (SUFFOCATION_BLOCKS.contains(block) || blockState.isSolidRender(world, checkPos)) {
                issues.add(SafetyIssue.SUFFOCATION_RISK);
                penalty += 50.0; // Major penalty for suffocation
                VonixCore.LOGGER.debug("[SafetyValidation] Suffocation risk at {} (block: {})", 
                                     checkPos, block.getDescriptionId());
            }
        }
        
        // Check solid ground beneath player
        BlockPos groundPos = location.below();
        BlockState groundState = world.getBlockState(groundPos);
        Block groundBlock = groundState.getBlock();
        
        if (!SOLID_GROUND_BLOCKS.contains(groundBlock) && 
            !groundState.isSolidRender(world, groundPos)) {
            
            // Check deeper for solid ground
            boolean foundSolidGround = false;
            for (int depth = 2; depth <= GROUND_CHECK_DEPTH; depth++) {
                BlockPos deeperPos = location.below(depth);
                BlockState deeperState = world.getBlockState(deeperPos);
                
                if (SOLID_GROUND_BLOCKS.contains(deeperState.getBlock()) || 
                    deeperState.isSolidRender(world, deeperPos)) {
                    foundSolidGround = true;
                    break;
                }
            }
            
            if (!foundSolidGround) {
                issues.add(SafetyIssue.UNSTABLE_GROUND);
                penalty += 30.0;
                VonixCore.LOGGER.debug("[SafetyValidation] Unstable ground at {} (block: {})", 
                                     groundPos, groundBlock.getDescriptionId());
            }
        }
        
        // Check for dangerous falls
        if (checkForDangerousFall(world, location)) {
            issues.add(SafetyIssue.FALL_DAMAGE_RISK);
            penalty += 25.0;
        }
        
        // Check void proximity
        if (location.getY() <= VOID_PROXIMITY_THRESHOLD) {
            issues.add(SafetyIssue.VOID_PROXIMITY);
            penalty += 40.0;
            VonixCore.LOGGER.debug("[SafetyValidation] Void proximity at Y={}", location.getY());
        }
        
        return new SafetyCheckResult(issues, penalty);
    }
    
    /**
     * Check for environmental hazards within the specified radius
     */
    private SafetyCheckResult checkEnvironmentalHazards(ServerLevel world, BlockPos location, int radius) {
        List<SafetyIssue> issues = new ArrayList<>();
        double penalty = 0.0;
        
        // Check all blocks within the hazard check radius
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos checkPos = location.offset(x, y, z);
                    BlockState blockState = world.getBlockState(checkPos);
                    Block block = blockState.getBlock();
                    
                    if (HAZARDOUS_BLOCKS.contains(block)) {
                        // Calculate distance-based penalty
                        double distance = Math.sqrt(x*x + y*y + z*z);
                        double hazardPenalty = Math.max(5.0, 20.0 - (distance * 3.0));
                        
                        if (block == Blocks.LAVA) {
                            issues.add(SafetyIssue.LAVA_NEARBY);
                            penalty += hazardPenalty * 2.0; // Lava is extra dangerous
                        } else if (block == Blocks.FIRE || block == Blocks.SOUL_FIRE) {
                            issues.add(SafetyIssue.FIRE_NEARBY);
                            penalty += hazardPenalty;
                        } else {
                            issues.add(SafetyIssue.ENVIRONMENTAL_HAZARD);
                            penalty += hazardPenalty * 0.5;
                        }
                        
                        VonixCore.LOGGER.debug("[SafetyValidation] Hazard {} found at {} (distance: {:.1f})", 
                                             block.getDescriptionId(), checkPos, distance);
                    }
                }
            }
        }
        
        return new SafetyCheckResult(issues, penalty);
    }
    
    /**
     * Check structure constraints based on configuration
     */
    private SafetyCheckResult checkStructureConstraints(ServerLevel world, BlockPos location) {
        List<SafetyIssue> issues = new ArrayList<>();
        double penalty = 0.0;
        
        try {
            // Check if location is within any structures
            Map<Structure, LongSet> structures = world.structureManager()
                .getAllStructuresAt(location);
            
            if (!structures.isEmpty()) {
                for (Structure structure : structures.keySet()) {
                    // For now, we avoid all structures when avoidStructures is enabled
                    // This can be made more configurable in the future
                    issues.add(SafetyIssue.STRUCTURE_CONFLICT);
                    penalty += 15.0;
                    
                    VonixCore.LOGGER.debug("[SafetyValidation] Structure conflict at {} (structure: {})", 
                                         location, structure.toString());
                }
            }
            
        } catch (Exception e) {
            VonixCore.LOGGER.warn("[SafetyValidation] Error checking structures at {}: {}", 
                                location, e.getMessage());
            // Don't fail validation due to structure check errors
        }
        
        return new SafetyCheckResult(issues, penalty);
    }
    
    /**
     * Check world boundary constraints
     */
    private SafetyCheckResult checkWorldBoundaries(ServerLevel world, BlockPos location) {
        List<SafetyIssue> issues = new ArrayList<>();
        double penalty = 0.0;
        
        try {
            // Check world border
            net.minecraft.world.level.border.WorldBorder worldBorder = world.getWorldBorder();
            if (!worldBorder.isWithinBounds(location)) {
                issues.add(SafetyIssue.WORLD_BOUNDARY_VIOLATION);
                penalty += 100.0; // Complete failure for boundary violations
                
                VonixCore.LOGGER.debug("[SafetyValidation] World boundary violation at {}", location);
            }
            
            // Check Y-level boundaries
            if (location.getY() < world.getMinBuildHeight() || location.getY() > world.getMaxBuildHeight()) {
                issues.add(SafetyIssue.WORLD_BOUNDARY_VIOLATION);
                penalty += 100.0;
                
                VonixCore.LOGGER.debug("[SafetyValidation] Y-level boundary violation at {} (Y={})", 
                                     location, location.getY());
            }
            
        } catch (Exception e) {
            VonixCore.LOGGER.warn("[SafetyValidation] Error checking world boundaries at {}: {}", 
                                location, e.getMessage());
        }
        
        return new SafetyCheckResult(issues, penalty);
    }
    
    /**
     * Check for dangerous falls that could cause damage
     */
    private boolean checkForDangerousFall(ServerLevel world, BlockPos location) {
        // Check downward for solid ground within fall damage range
        for (int depth = 1; depth <= FALL_DAMAGE_CHECK_DEPTH; depth++) {
            BlockPos checkPos = location.below(depth);
            BlockState blockState = world.getBlockState(checkPos);
            
            if (SOLID_GROUND_BLOCKS.contains(blockState.getBlock()) || 
                blockState.isSolidRender(world, checkPos)) {
                
                // Found solid ground - check if fall would cause damage
                return depth > 3; // Falls > 3 blocks cause damage
            }
        }
        
        // No solid ground found within check range - dangerous fall
        return true;
    }
    
    /**
     * Get cached validation result if available and not expired
     */
    private SafetyResult getCachedResult(BlockPos location) {
        SafetyResult cached = validationCache.get(location);
        if (cached != null && !cached.isExpired(cacheExpirationMs)) {
            return cached;
        }
        return null;
    }
    
    /**
     * Cache validation result with timestamp
     */
    private void cacheResult(BlockPos location, SafetyResult result) {
        // Limit cache size to prevent memory issues
        if (validationCache.size() > 10000) {
            // Remove oldest entries (simple LRU-like behavior)
            Iterator<Map.Entry<BlockPos, SafetyResult>> iterator = validationCache.entrySet().iterator();
            for (int i = 0; i < 1000 && iterator.hasNext(); i++) {
                iterator.next();
                iterator.remove();
            }
        }
        
        validationCache.put(location, result);
    }
    
    /**
     * Get performance statistics
     */
    public SafetyValidationStats getStats() {
        return new SafetyValidationStatsImpl();
    }
    
    /**
     * Clear validation cache
     */
    public void clearCache() {
        validationCache.clear();
        VonixCore.LOGGER.info("[SafetyValidation] Validation cache cleared");
    }
    
    /**
     * Shutdown the safety validation engine
     */
    public CompletableFuture<Void> shutdown(long timeoutMs) {
        if (shutdown) {
            return CompletableFuture.completedFuture(null);
        }
        
        shutdown = true;
        VonixCore.LOGGER.info("[SafetyValidation] Shutting down SafetyValidationEngine...");
        
        return CompletableFuture.runAsync(() -> {
            try {
                validationCache.clear();
                validationExecutor.shutdown();
                
                if (!validationExecutor.awaitTermination(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    VonixCore.LOGGER.warn("[SafetyValidation] Executor did not terminate within {}ms, forcing shutdown", timeoutMs);
                    validationExecutor.shutdownNow();
                }
                
                VonixCore.LOGGER.info("[SafetyValidation] SafetyValidationEngine shutdown complete");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                VonixCore.LOGGER.error("[SafetyValidation] Shutdown interrupted", e);
            }
        });
    }
    
    /**
     * Safety issues that can be detected during validation
     */
    public enum SafetyIssue {
        SUFFOCATION_RISK("Risk of suffocation in solid blocks"),
        FALL_DAMAGE_RISK("Risk of fall damage"),
        LAVA_NEARBY("Lava detected nearby"),
        FIRE_NEARBY("Fire detected nearby"),
        VOID_PROXIMITY("Too close to void"),
        UNSTABLE_GROUND("Unstable or missing ground"),
        STRUCTURE_CONFLICT("Location conflicts with structure preferences"),
        ENVIRONMENTAL_HAZARD("Environmental hazard detected"),
        WORLD_BOUNDARY_VIOLATION("Location outside world boundaries");
        
        private final String description;
        
        SafetyIssue(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * Result of a safety validation check
     */
    public static class SafetyResult {
        private final boolean safe;
        private final BlockPos location;
        private final List<SafetyIssue> issues;
        private final double safetyScore;
        private final long timestamp;
        
        public SafetyResult(boolean safe, BlockPos location, List<SafetyIssue> issues, double safetyScore) {
            this.safe = safe;
            this.location = location;
            this.issues = List.copyOf(issues);
            this.safetyScore = safetyScore;
            this.timestamp = System.currentTimeMillis();
        }
        
        public boolean isSafe() { return safe; }
        public BlockPos getLocation() { return location; }
        public List<SafetyIssue> getIssues() { return issues; }
        public double getSafetyScore() { return safetyScore; }
        public long getTimestamp() { return timestamp; }
        
        public boolean isExpired(long expirationMs) {
            return (System.currentTimeMillis() - timestamp) > expirationMs;
        }
        
        public static SafetyResult safe(BlockPos location, double score) {
            return new SafetyResult(true, location, List.of(), score);
        }
        
        public static SafetyResult unsafe(BlockPos location, String reason) {
            return new SafetyResult(false, location, 
                List.of(SafetyIssue.ENVIRONMENTAL_HAZARD), 0.0);
        }
        
        @Override
        public String toString() {
            return String.format("SafetyResult{safe=%s, location=%s, score=%.1f, issues=%d}", 
                               safe, location, safetyScore, issues.size());
        }
    }
    
    /**
     * Internal result for individual safety checks
     */
    private static class SafetyCheckResult {
        private final List<SafetyIssue> issues;
        private final double safetyPenalty;
        
        public SafetyCheckResult(List<SafetyIssue> issues, double safetyPenalty) {
            this.issues = issues;
            this.safetyPenalty = safetyPenalty;
        }
        
        public List<SafetyIssue> getIssues() { return issues; }
        public double getSafetyPenalty() { return safetyPenalty; }
    }
    
    /**
     * Performance statistics interface
     */
    public interface SafetyValidationStats {
        long getTotalValidations();
        long getSafeLocations();
        long getUnsafeLocations();
        double getSafetyRate();
        double getAverageValidationTimeMs();
        int getCacheSize();
        boolean meetsPerformanceThresholds();
    }
    
    /**
     * Implementation of safety validation statistics
     */
    private class SafetyValidationStatsImpl implements SafetyValidationStats {
        @Override
        public long getTotalValidations() {
            return totalValidations.get();
        }
        
        @Override
        public long getSafeLocations() {
            return safeLocations.get();
        }
        
        @Override
        public long getUnsafeLocations() {
            return unsafeLocations.get();
        }
        
        @Override
        public double getSafetyRate() {
            long total = getTotalValidations();
            return total > 0 ? (double) getSafeLocations() / total : 0.0;
        }
        
        @Override
        public double getAverageValidationTimeMs() {
            long total = getTotalValidations();
            return total > 0 ? (double) totalValidationTime.get() / total : 0.0;
        }
        
        @Override
        public int getCacheSize() {
            return validationCache.size();
        }
        
        @Override
        public boolean meetsPerformanceThresholds() {
            return getAverageValidationTimeMs() <= 100 && // Max 100ms average validation time
                   getSafetyRate() >= 0.3; // At least 30% of locations should be safe
        }
        
        @Override
        public String toString() {
            return String.format("SafetyValidationStats{total=%d, safe=%.2f%%, avgTime=%.1fms, cache=%d}",
                               getTotalValidations(), getSafetyRate() * 100, 
                               getAverageValidationTimeMs(), getCacheSize());
        }
    }
    
    /**
     * Validate location with default options (convenience method for LocationSearchEngine).
     */
    public CompletableFuture<SafetyResult> validateLocationAsync(ServerLevel world, BlockPos location) {
        // Use default options via builder
        RTPOptions defaultOptions = new RTPOptions.Builder().build();
        return validateLocation(world, location, defaultOptions);
    }
}
