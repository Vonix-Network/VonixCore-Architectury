package network.vonix.vonixcore.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import network.vonix.vonixcore.VonixCore;

import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Queue-based Asynchronous Random Teleport Manager.
 * 
 * FULLY OPTIMIZED for servers:
 * - Single-threaded queue processing (1 RTP at a time)
 * - Dedicated worker thread pool for all search logic
 * - Block state reads use ChunkAccess (thread-safe, off main thread)
 * - Main thread only used for: teleport + ticket operations
 * - Near-zero main thread impact during chunk searching
 */
public class AsyncRtpManager {

    private static final int MAX_ATTEMPTS = 100;
    private static final int CHUNK_LOAD_TIMEOUT_MS = 5000; // 5 seconds per chunk

    // Custom ticket type for RTP chunk loading
    private static final TicketType<ChunkPos> RTP_TICKET = TicketType.create("vonixcore_rtp",
            Comparator.comparingLong(ChunkPos::toLong), 20 * 5);

    // Dangerous blocks sets for O(1) lookup
    private static final Set<Block> DANGEROUS_GROUND_BLOCKS = Set.of(
            Blocks.LAVA, Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.FIRE,
            Blocks.SOUL_FIRE, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
            Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE, Blocks.WATER,
            Blocks.POINTED_DRIPSTONE, Blocks.SCAFFOLDING, Blocks.COBWEB,
            Blocks.HONEY_BLOCK, Blocks.SLIME_BLOCK, Blocks.TNT);

    private static final Set<Block> NEARBY_DANGER_BLOCKS = Set.of(
            Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE);

    // ===== QUEUE SYSTEM =====

    // Request queue - only 1 processed at a time
    private static final ConcurrentLinkedQueue<RtpRequest> requestQueue = new ConcurrentLinkedQueue<>();

    // Track active players to prevent duplicates
    private static final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();

    // Dedicated worker thread pool (2 threads: 1 processor, 1 for async chunk
    // callbacks)
    private static final ExecutorService workerPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "VonixCore-RTP-Worker");
        t.setDaemon(true);
        return t;
    });

    // Flag to track if queue processor is running
    private static final AtomicBoolean processorRunning = new AtomicBoolean(false);

    /**
     * RTP request record for queue entries.
     */
    private record RtpRequest(ServerPlayer player, ServerLevel level, BlockPos center, long queuedAt) {
    }

    // ===== PUBLIC API =====

    private static int getMinDistance() {
        // 1.19.2 version uses config if available, otherwise default
        return 500;
    }

    private static int getMaxDistance() {
        // 1.19.2 version uses config if available, otherwise default
        return 10000;
    }

    /**
     * Queue an RTP request for the given player.
     * Requests are processed one at a time to minimize server impact.
     */
    public static void randomTeleport(ServerPlayer player) {
        UUID playerUuid = player.getUUID();

        // Prevent duplicate requests
        if (pendingPlayers.contains(playerUuid)) {
            player.sendSystemMessage(Component.literal("§cYou already have an RTP queued!"));
            return;
        }

        // Add to pending set and queue
        pendingPlayers.add(playerUuid);
        RtpRequest request = new RtpRequest(player, player.getLevel(), player.blockPosition(),
                System.currentTimeMillis());
        requestQueue.offer(request);

        int queuePosition = requestQueue.size();
        if (queuePosition == 1) {
            player.sendSystemMessage(Component.literal("§eSearching for a safe location..."));
        } else {
            player.sendSystemMessage(Component.literal("§eQueued for RTP. Position: §6#" + queuePosition));
        }

        // Start processor if not already running
        startQueueProcessor();
    }

    /**
     * Starts the queue processor if not already running.
     */
    private static void startQueueProcessor() {
        if (processorRunning.compareAndSet(false, true)) {
            workerPool.submit(AsyncRtpManager::processQueue);
        }
    }

    /**
     * Main queue processing loop - runs on worker thread.
     * Processes requests one at a time asynchronously.
     */
    private static void processQueue() {
        processNextRequest().thenRun(() -> {
            processorRunning.set(false);
            // Check if more requests came in while we were finishing
            if (!requestQueue.isEmpty()) {
                startQueueProcessor();
            }
        });
    }
    
    /**
     * Process requests recursively to ensure sequential processing.
     */
    private static CompletableFuture<Void> processNextRequest() {
        RtpRequest request = requestQueue.poll();
        if (request == null) {
            return CompletableFuture.completedFuture(null);
        }

        // Check if player is still online
        if (!request.player.isAlive() || request.player.hasDisconnected()) {
            pendingPlayers.remove(request.player.getUUID());
            return processNextRequest(); // Process next
        }

        // Process this request and chain to next
        return processRtpRequest(request).thenCompose(v -> processNextRequest());
    }

    /**
     * Process a single RTP request. Runs on worker thread.
     * Fully async to prevent ServerHangWatchdog crashes.
     */
    private static CompletableFuture<Void> processRtpRequest(RtpRequest request) {
        return processRtpRequestRecursive(request, 1);
    }
    
    /**
     * Recursive async search for safe RTP location.
     */
    private static CompletableFuture<Void> processRtpRequestRecursive(RtpRequest request, int attempt) {
        ServerPlayer player = request.player;
        ServerLevel level = request.level;
        BlockPos center = request.center;
        UUID playerUuid = player.getUUID();

        if (attempt > MAX_ATTEMPTS) {
            // Exhausted all attempts
            scheduleMainThread(player.getServer(), () -> {
                if (player.isAlive() && !player.hasDisconnected()) {
                    player.sendSystemMessage(Component.literal(
                            "§cCould not find a safe location after " + MAX_ATTEMPTS + " attempts!"));
                }
            });
            pendingPlayers.remove(playerUuid);
            return CompletableFuture.completedFuture(null);
        }

        // Progress feedback every 10 attempts
        if (attempt % 10 == 0) {
            final int currentAttempt = attempt;
            scheduleMainThread(player.getServer(), () -> {
                if (player.isAlive() && !player.hasDisconnected()) {
                    player.sendSystemMessage(Component.literal(
                            "§7Searching... (attempt " + currentAttempt + "/" + MAX_ATTEMPTS + ")"));
                }
            });
        }

        // Generate random coordinates
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble() * 2 * Math.PI;
        int minDist = getMinDistance();
        int maxDist = getMaxDistance();
        int dist = minDist + random.nextInt(Math.max(1, maxDist - minDist));
        int x = center.getX() + (int) (Math.cos(angle) * dist);
        int z = center.getZ() + (int) (Math.sin(angle) * dist);

        ChunkPos chunkPos = new ChunkPos(x >> 4, z >> 4);

        // Load chunk asynchronously and chain operations
        return loadChunkAsync(level, chunkPos).thenCompose(chunk -> {
            if (chunk == null) {
                // Chunk load failed, try next attempt
                return processRtpRequestRecursive(request, attempt + 1);
            }

            // Safety check using ChunkAccess (thread-safe reads!)
            BlockPos candidate = findSafeYFromChunk(level, chunk, x, z);
            if (candidate == null || !isSafeSpotFromChunk(level, chunk, candidate)) {
                // Not safe, try next attempt
                return processRtpRequestRecursive(request, attempt + 1);
            }

            // Found safe spot, attempt teleport
            final BlockPos finalPos = candidate;
            final int attempts = attempt;

            CompletableFuture<Boolean> teleportFuture = new CompletableFuture<>();
            scheduleMainThread(player.getServer(), () -> {
                try {
                    if (!player.isAlive() || player.hasDisconnected()) {
                        teleportFuture.complete(false);
                        return;
                    }
                    boolean success = performTeleport(player, level, finalPos, attempts);
                    teleportFuture.complete(success);
                } catch (Exception e) {
                    teleportFuture.complete(false);
                }
            });

            return teleportFuture.thenCompose(success -> {
                if (success) {
                    // Teleport successful
                    pendingPlayers.remove(playerUuid);
                    return CompletableFuture.completedFuture(null);
                }

                // Teleport failed, continue searching
                VonixCore.LOGGER.info("[RTP] Initial attempt failed, continuing search for {}", 
                        player.getName().getString());
                return continueSearchingAsync(player, level, center, attempts)
                        .thenAccept(continuedSuccess -> {
                            if (!continuedSuccess) {
                                pendingPlayers.remove(playerUuid);
                            }
                        });
            });
        }).exceptionally(ex -> {
            VonixCore.LOGGER.error("[RTP] Error processing RTP for {}: {}", 
                    player.getName().getString(), ex.getMessage());
            scheduleMainThread(player.getServer(), () -> {
                if (player.isAlive() && !player.hasDisconnected()) {
                    player.sendSystemMessage(Component.literal("§cAn error occurred during RTP."));
                }
            });
            pendingPlayers.remove(playerUuid);
            return null;
        });
    }

    /**
     * Continues searching for a safe location after initial failure.
     * Returns CompletableFuture<Boolean> for async processing.
     * This non-blocking approach prevents ServerHangWatchdog crashes.
     */
    private static CompletableFuture<Boolean> continueSearchingAsync(ServerPlayer player, ServerLevel level, BlockPos center, int attemptsSoFar) {
        return continueSearchingRecursive(player, level, center, attemptsSoFar, 25);
    }
    
    /**
     * Recursive async search to avoid blocking.
     */
    private static CompletableFuture<Boolean> continueSearchingRecursive(ServerPlayer player, ServerLevel level, 
            BlockPos center, int attemptsSoFar, int remainingAttempts) {
        
        if (remainingAttempts <= 0) {
            // Exhausted all attempts
            scheduleMainThread(player.getServer(), () -> {
                if (player.isAlive() && !player.hasDisconnected()) {
                    player.sendSystemMessage(Component.literal(
                            "§cCould not find a safe location after " + attemptsSoFar + " attempts!"));
                }
            });
            return CompletableFuture.completedFuture(false);
        }
        
        int totalAttempts = attemptsSoFar + 1;
        UUID playerUuid = player.getUUID();

        // Progress feedback every 10 attempts
        if (totalAttempts % 10 == 0) {
            final int currentAttempt = totalAttempts;
            scheduleMainThread(player.getServer(), () -> {
                if (player.isAlive() && !player.hasDisconnected()) {
                    player.sendSystemMessage(Component.literal(
                            "§7Still searching... (attempt " + currentAttempt + ")"));
                }
            });
        }

        // Generate random coordinates
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble() * 2 * Math.PI;
        int minDist = getMinDistance();
        int maxDist = getMaxDistance();
        int dist = minDist + random.nextInt(Math.max(1, maxDist - minDist));
        int x = center.getX() + (int) (Math.cos(angle) * dist);
        int z = center.getZ() + (int) (Math.sin(angle) * dist);

        ChunkPos chunkPos = new ChunkPos(x >> 4, z >> 4);

        // Load chunk asynchronously and chain operations
        return loadChunkAsync(level, chunkPos).thenCompose(chunk -> {
            if (chunk == null) {
                // Chunk load failed, try next attempt
                return continueSearchingRecursive(player, level, center, totalAttempts, remainingAttempts - 1);
            }

            // Safety check using ChunkAccess
            BlockPos candidate = findSafeYFromChunk(level, chunk, x, z);
            if (candidate == null || !isSafeSpotFromChunk(level, chunk, candidate)) {
                // Not safe, try next attempt
                return continueSearchingRecursive(player, level, center, totalAttempts, remainingAttempts - 1);
            }

            // Found safe spot, attempt teleport
            final BlockPos finalPos = candidate;
            final int attemptNumber = totalAttempts;
            
            CompletableFuture<Boolean> teleportFuture = new CompletableFuture<>();
            scheduleMainThread(player.getServer(), () -> {
                try {
                    if (!player.isAlive() || player.hasDisconnected()) {
                        teleportFuture.complete(false);
                        return;
                    }
                    boolean success = performTeleport(player, level, finalPos, attemptNumber);
                    teleportFuture.complete(success);
                } catch (Exception e) {
                    teleportFuture.complete(false);
                }
            });

            return teleportFuture.thenCompose(success -> {
                if (success) {
                    pendingPlayers.remove(playerUuid);
                    return CompletableFuture.completedFuture(true);
                }
                // Teleport failed, try next attempt
                return continueSearchingRecursive(player, level, center, totalAttempts, remainingAttempts - 1);
            });
        });
    }

    /**
     * Load a chunk asynchronously - TRULY NON-BLOCKING.
     * Returns CompletableFuture<ChunkAccess> to avoid blocking any thread.
     * This prevents ServerHangWatchdog crashes.
     */
    private static CompletableFuture<ChunkAccess> loadChunkAsync(ServerLevel level, ChunkPos pos) {
        CompletableFuture<ChunkAccess> future = new CompletableFuture<>();

        // Schedule ticket + chunk request on main thread
        scheduleMainThread(level.getServer(), () -> {
            try {
                ServerChunkCache chunkSource = level.getChunkSource();
                
                // First check if chunk is already loaded to avoid blocking
                ChunkAccess existingChunk = chunkSource.getChunk(pos.x, pos.z, ChunkStatus.SURFACE, false);
                if (existingChunk != null) {
                    // Chunk already exists, just add ticket and return
                    chunkSource.addRegionTicket(RTP_TICKET, pos, 0, pos);
                    future.complete(existingChunk);
                    return;
                }
                
                // Chunk not loaded - add ticket and request async load
                chunkSource.addRegionTicket(RTP_TICKET, pos, 0, pos);

                // Use SURFACE status for fast loading (enough for safety checks)
                // Use orTimeout to prevent indefinite blocking
                chunkSource.getChunkFuture(pos.x, pos.z, ChunkStatus.SURFACE, true)
                        .orTimeout(CHUNK_LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .thenAccept(either -> {
                            // Remove ticket on main thread
                            scheduleMainThread(level.getServer(), () -> {
                                chunkSource.removeRegionTicket(RTP_TICKET, pos, 0, pos);
                            });
                            future.complete(either.left().orElse(null));
                        })
                        .exceptionally(ex -> {
                            scheduleMainThread(level.getServer(), () -> {
                                chunkSource.removeRegionTicket(RTP_TICKET, pos, 0, pos);
                            });
                            future.complete(null);
                            return null;
                        });
            } catch (Exception e) {
                VonixCore.LOGGER.error("[RTP] Error loading chunk {}: {}", pos, e.getMessage());
                future.complete(null);
            }
        });

        return future;
    }

    /**
     * Helper to schedule work on main thread.
     */
    private static void scheduleMainThread(net.minecraft.server.MinecraftServer server, Runnable task) {
        if (server != null) {
            server.execute(task);
        }
    }

    /**
     * Performs teleport with proper chunk generation.
     * Must be called on main thread.
     */
    private static boolean performTeleport(ServerPlayer player, ServerLevel level, BlockPos safePos, int attempts) {
        ChunkPos chunkPos = new ChunkPos(safePos);
        ServerChunkCache chunkSource = level.getChunkSource();

        try {
            // Add ticket with higher radius to ensure proper chunk loading
            chunkSource.addRegionTicket(RTP_TICKET, chunkPos, 3, chunkPos);

            // Force chunk to FULL status with validation
            ChunkAccess targetChunk = level.getChunk(safePos);
            if (targetChunk == null || !targetChunk.getStatus().isOrAfter(ChunkStatus.FULL)) {
                VonixCore.LOGGER.warn("[RTP] Target chunk not fully loaded, attempting force load");
                // Force generation if needed - use getNow to avoid blocking
                try {
                    chunkSource.getChunkFuture(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true)
                            .orTimeout(5, TimeUnit.SECONDS)
                            .getNow(null);
                } catch (Exception e) {
                    VonixCore.LOGGER.warn("[RTP] Chunk force load timed out or failed: {}", e.getMessage());
                    // Try one more time with immediate check
                    targetChunk = level.getChunk(safePos);
                    if (targetChunk == null || !targetChunk.getStatus().isOrAfter(ChunkStatus.FULL)) {
                        return false;
                    }
                }
            }

            // Validate the location is still safe before teleporting
            if (!isLocationSafeMainThread(level, safePos)) {
                VonixCore.LOGGER.warn("[RTP] Location became unsafe during final check, will continue searching");
                return false;
            }

            // Save current location for /back command BEFORE teleporting
            TeleportManager.getInstance().saveLastLocation(player, false);

            // Perform teleport with safety checks
            player.teleportTo(level, safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5,
                    player.getYRot(), player.getXRot());

            player.sendSystemMessage(Component.literal(String.format(
                    "§aTeleported to §eX: %d, Y: %d, Z: %d §7(Attempts: %d)",
                    safePos.getX(), safePos.getY(), safePos.getZ(), attempts)));

            // Pre-load surrounding chunks async (fire and forget)
            preloadSurroundingChunksAsync(level, safePos);
            return true;
        } catch (Exception e) {
            VonixCore.LOGGER.error("[RTP] Error during teleport: {}", e.getMessage());
            return false;
        } finally {
            chunkSource.removeRegionTicket(RTP_TICKET, chunkPos, 3, chunkPos);
        }
    }

    /**
     * Final safety check on main thread before teleport.
     */
    private static boolean isLocationSafeMainThread(ServerLevel level, BlockPos pos) {
        try {
            BlockState groundState = level.getBlockState(pos.below());
            BlockState spawnState = level.getBlockState(pos);
            BlockState aboveState = level.getBlockState(pos.above());

            return groundState.getMaterial().isSolid() &&
                    spawnState.isAir() &&
                    aboveState.isAir() &&
                    !DANGEROUS_GROUND_BLOCKS.contains(groundState.getBlock());
        } catch (Exception e) {
            VonixCore.LOGGER.warn("[RTP] Safety check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Pre-loads surrounding chunks asynchronously.
     */
    private static void preloadSurroundingChunksAsync(ServerLevel level, BlockPos center) {
        ChunkPos centerChunk = new ChunkPos(center);
        ServerChunkCache chunkSource = level.getChunkSource();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0)
                    continue;

                ChunkPos neighborPos = new ChunkPos(centerChunk.x + dx, centerChunk.z + dz);
                chunkSource.addRegionTicket(RTP_TICKET, neighborPos, 0, neighborPos);

                chunkSource.getChunkFuture(neighborPos.x, neighborPos.z, ChunkStatus.FULL, true)
                        .orTimeout(5, TimeUnit.SECONDS)
                        .whenComplete((either, ex) -> {
                            level.getServer().execute(() -> {
                                chunkSource.removeRegionTicket(RTP_TICKET, neighborPos, 0, neighborPos);
                            });
                        });
            }
        }
    }

    // ===== THREAD-SAFE SAFETY CHECKS (using ChunkAccess) =====

    /**
     * Find safe Y coordinate using ChunkAccess (thread-safe reads).
     * This runs on worker thread, NOT main thread.
     */
    private static BlockPos findSafeYFromChunk(ServerLevel level, ChunkAccess chunk, int x, int z) {
        boolean isNether = level.dimension() == Level.NETHER;
        boolean isEnd = level.dimension() == Level.END;

        // Local coordinates within chunk
        int localX = x & 15;
        int localZ = z & 15;

        if (isNether) {
            return findNetherSafeYFromChunk(chunk, x, z, localX, localZ);
        } else if (isEnd) {
            return findEndSafeYFromChunk(chunk, x, z, localX, localZ);
        } else {
            return findOverworldSafeYFromChunk(level, chunk, x, z, localX, localZ);
        }
    }

    private static BlockPos findNetherSafeYFromChunk(ChunkAccess chunk, int x, int z, int localX, int localZ) {
        for (int y = 32; y <= 120; y++) {
            BlockPos check = new BlockPos(localX, y, localZ);
            if (chunk.getBlockState(check).isSolidRender(chunk, check)) {
                BlockPos spawn = new BlockPos(x, y + 1, z);
                BlockPos localSpawn = new BlockPos(localX, y + 1, localZ);
                BlockPos localAbove = new BlockPos(localX, y + 2, localZ);
                if (chunk.getBlockState(localSpawn).isAir() && chunk.getBlockState(localAbove).isAir()) {
                    return spawn;
                }
            }
        }
        return null;
    }

    private static BlockPos findEndSafeYFromChunk(ChunkAccess chunk, int x, int z, int localX, int localZ) {
        for (int y = 50; y <= 120; y++) {
            BlockPos check = new BlockPos(localX, y, localZ);
            if (chunk.getBlockState(check).isSolidRender(chunk, check)) {
                BlockPos spawn = new BlockPos(x, y + 1, z);
                BlockPos localSpawn = new BlockPos(localX, y + 1, localZ);
                BlockPos localAbove = new BlockPos(localX, y + 2, localZ);
                if (chunk.getBlockState(localSpawn).isAir() && chunk.getBlockState(localAbove).isAir()) {
                    return spawn;
                }
            }
        }
        return null;
    }

    private static BlockPos findOverworldSafeYFromChunk(ServerLevel level, ChunkAccess chunk, int x, int z, int localX,
            int localZ) {
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        // Start at Y=200 as requested and search downward for first solid ground
        int startY = Math.min(200, maxY - 2);
        int endY = Math.max(minY + 1, 80); // Don't go below Y=80 for safety

        // Enhanced safety check: look for solid ground with proper space
        for (int y = startY; y >= endY; y--) {
            BlockPos localCheck = new BlockPos(localX, y, localZ);
            if (isSafeGroundBlock(chunk, localCheck)) {
                // Check if there's enough space above (2 air blocks)
                BlockPos localSpawn = new BlockPos(localX, y + 1, localZ);
                BlockPos localAbove = new BlockPos(localX, y + 2, localZ);
                if (isSafeAirSpace(chunk, localSpawn, localAbove)) {
                    // Additional safety check: not in dangerous blocks and not underground
                    if (!isDangerousLocation(chunk, localSpawn) && !isUnderground(chunk, localX, y + 1, localZ)) {
                        return new BlockPos(x, y + 1, z);
                    }
                }
            }
        }

        // Fallback: if nothing found from Y=100 downward, try limited upward search
        int surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, localX, localZ);
        if (surface > startY && surface <= maxY - 2) {
            int fallbackStart = Math.max(startY + 1, surface - 3);
            int fallbackEnd = Math.min(maxY - 2, surface + 3);

            for (int y = fallbackStart; y <= fallbackEnd; y++) {
                BlockPos localCheck = new BlockPos(localX, y, localZ);
                if (isSafeGroundBlock(chunk, localCheck)) {
                    BlockPos localSpawn = new BlockPos(localX, y + 1, localZ);
                    BlockPos localAbove = new BlockPos(localX, y + 2, localZ);
                    if (isSafeAirSpace(chunk, localSpawn, localAbove) &&
                            !isDangerousLocation(chunk, localSpawn)) {
                        return new BlockPos(x, y + 1, z);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Check if a block is safe ground for spawning.
     */
    private static boolean isSafeGroundBlock(ChunkAccess chunk, BlockPos pos) {
        BlockState state = chunk.getBlockState(pos);
        return state.isSolidRender(chunk, pos) &&
                !state.isAir() &&
                !DANGEROUS_GROUND_BLOCKS.contains(state.getBlock());
    }

    /**
     * Check if there's safe air space for player.
     */
    private static boolean isSafeAirSpace(ChunkAccess chunk, BlockPos spawnPos, BlockPos abovePos) {
        return chunk.getBlockState(spawnPos).isAir() &&
                chunk.getBlockState(abovePos).isAir();
    }

    /**
     * Check if location is underground (cave detection).
     */
    private static boolean isUnderground(ChunkAccess chunk, int localX, int y, int localZ) {
        // Check if there are solid blocks above (indicating underground)
        for (int checkY = y + 2; checkY <= y + 5; checkY++) {
            BlockPos checkPos = new BlockPos(localX, checkY, localZ);
            if (chunk.getBlockState(checkPos).isSolidRender(chunk, checkPos)) {
                return true; // Underground
            }
        }
        return false; // Open sky above
    }

    /**
     * Check if a location has dangerous ground blocks.
     */
    private static boolean isDangerousLocation(ChunkAccess chunk, BlockPos localPos) {
        BlockPos localBelow = new BlockPos(localPos.getX(), localPos.getY() - 1, localPos.getZ());
        Block belowBlock = chunk.getBlockState(localBelow).getBlock();
        return DANGEROUS_GROUND_BLOCKS.contains(belowBlock);
    }

    /**
     * Fast safety check using ChunkAccess (thread-safe reads).
     * Runs entirely on worker thread.
     */
    private static boolean isSafeSpotFromChunk(ServerLevel level, ChunkAccess chunk, BlockPos pos) {
        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        int y = pos.getY();

        BlockPos localPos = new BlockPos(localX, y, localZ);
        BlockPos localBelow = new BlockPos(localX, y - 1, localZ);
        BlockPos localAbove = new BlockPos(localX, y + 1, localZ);

        // Must have solid ground
        if (!chunk.getBlockState(localBelow).isSolidRender(chunk, localBelow)) {
            return false;
        }

        // Must have air for player
        if (!chunk.getBlockState(localPos).isAir() || !chunk.getBlockState(localAbove).isAir()) {
            return false;
        }

        // Check dangerous ground block
        Block belowBlock = chunk.getBlockState(localBelow).getBlock();
        if (DANGEROUS_GROUND_BLOCKS.contains(belowBlock)) {
            return false;
        }

        // Cave detection (3 blocks down)
        int airBlocksBelow = 0;
        for (int i = 2; i <= 4; i++) {
            BlockPos localBelowI = new BlockPos(localX, y - i, localZ);
            if (chunk.getBlockState(localBelowI).isAir()) {
                airBlocksBelow++;
            } else {
                break;
            }
        }
        if (airBlocksBelow >= 3) {
            return false; // Cave
        }

        // Nearby danger check (only within same chunk to stay thread-safe)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0)
                    continue;

                int checkX = localX + dx;
                int checkZ = localZ + dz;

                // Skip if outside chunk bounds
                if (checkX < 0 || checkX > 15 || checkZ < 0 || checkZ > 15)
                    continue;

                BlockPos checkPos = new BlockPos(checkX, y, checkZ);
                Block block = chunk.getBlockState(checkPos).getBlock();
                if (NEARBY_DANGER_BLOCKS.contains(block)) {
                    return false;
                }

                BlockPos checkBelow = new BlockPos(checkX, y - 1, checkZ);
                if (chunk.getBlockState(checkBelow).getBlock() == Blocks.LAVA) {
                    return false;
                }
            }
        }

        // End specific checks
        if (level.dimension() == Level.END) {
            if (pos.getY() < 50)
                return false;
            double distFromCenter = Math.sqrt(pos.getX() * pos.getX() + pos.getZ() * pos.getZ());
            if (distFromCenter < 100)
                return false;
        }

        // World border check (this is thread-safe)
        if (!level.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }

        return true;
    }

    /**
     * Shutdown the RTP system gracefully.
     */
    public static void shutdown() {
        requestQueue.clear();
        pendingPlayers.clear();
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
        }
    }
}
