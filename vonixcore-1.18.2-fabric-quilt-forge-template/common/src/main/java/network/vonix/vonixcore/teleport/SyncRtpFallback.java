package network.vonix.vonixcore.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.EssentialsConfig;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Synchronous fallback for RTP when async chunk loading causes mod compatibility issues.
 * This runs entirely on the main server thread to avoid threading conflicts.
 */
public class SyncRtpFallback {
    
    private static final int MAX_ATTEMPTS = 100;
    private static final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();
    
    // Dangerous blocks sets for O(1) lookup
    private static final Set<Block> DANGEROUS_GROUND_BLOCKS = Set.of(
            Blocks.LAVA, Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.FIRE,
            Blocks.SOUL_FIRE, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
            Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE, Blocks.WATER,
            Blocks.POINTED_DRIPSTONE, Blocks.SCAFFOLDING, Blocks.COBWEB,
            Blocks.HONEY_BLOCK, Blocks.SLIME_BLOCK, Blocks.TNT);

    private static final Set<Block> NEARBY_DANGER_BLOCKS = Set.of(
            Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE);

    /**
     * Synchronous random teleport - runs entirely on main thread.
     * Used when async chunk loading is disabled to prevent mod compatibility issues.
     */
    public static void randomTeleportSync(ServerPlayer player) {
        UUID playerUuid = player.getUUID();
        
        if (pendingPlayers.contains(playerUuid)) {
            player.sendMessage(new TextComponent("§cYou already have an RTP queued!"), net.minecraft.Util.NIL_UUID);
            return;
        }
        
        pendingPlayers.add(playerUuid);
        player.sendMessage(new TextComponent("§eSearching for a safe location..."), net.minecraft.Util.NIL_UUID);
        
        // Schedule on main thread
        player.getServer().execute(() -> {
            try {
                ServerLevel level = player.getLevel();
                BlockPos center = player.blockPosition();
                BlockPos safePos = findSafeLocationSync(level, center);
                
                if (safePos == null) {
                    player.sendMessage(new TextComponent("§cCould not find a safe location!"), net.minecraft.Util.NIL_UUID);
                    pendingPlayers.remove(playerUuid);
                    return;
                }
                
                TeleportManager.getInstance().saveLastLocation(player, false);
                player.teleportTo(level, safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5,
                        player.getYRot(), player.getXRot());
                player.sendMessage(new TextComponent(String.format(
                        "§aTeleported to §eX: %d, Y: %d, Z: %d",
                        safePos.getX(), safePos.getY(), safePos.getZ())), net.minecraft.Util.NIL_UUID);
            } catch (Exception e) {
                VonixCore.LOGGER.error("[RTP] Error during sync teleport: {}", e.getMessage());
                player.sendMessage(new TextComponent("§cAn error occurred during RTP."), net.minecraft.Util.NIL_UUID);
            } finally {
                pendingPlayers.remove(playerUuid);
            }
        });
    }

    /**
     * Synchronous location search - runs on main thread only.
     */
    private static BlockPos findSafeLocationSync(ServerLevel level, BlockPos center) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int minDist = EssentialsConfig.CONFIG.rtpMinRange.get();
        int maxDist = EssentialsConfig.CONFIG.rtpMaxRange.get();
        
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            int dist = minDist + random.nextInt(Math.max(1, maxDist - minDist));
            int x = center.getX() + (int) (Math.cos(angle) * dist);
            int z = center.getZ() + (int) (Math.sin(angle) * dist);
            
            ChunkPos chunkPos = new ChunkPos(x >> 4, z >> 4);
            
            // Load chunk synchronously on main thread
            ChunkAccess chunk = level.getChunkSource().getChunk(chunkPos.x, chunkPos.z, ChunkStatus.SURFACE, true);
            if (chunk == null) continue;
            
            BlockPos candidate = findSafeYFromChunk(level, chunk, x, z);
            if (candidate != null && isSafeSpotFromChunk(level, chunk, candidate)) {
                return candidate;
            }
        }
        return null;
    }
    
    private static BlockPos findSafeYFromChunk(ServerLevel level, ChunkAccess chunk, int x, int z) {
        boolean isNether = level.dimension() == net.minecraft.world.level.Level.NETHER;
        boolean isEnd = level.dimension() == net.minecraft.world.level.Level.END;
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
    
    private static BlockPos findOverworldSafeYFromChunk(ServerLevel level, ChunkAccess chunk, int x, int z, int localX, int localZ) {
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int startY = Math.min(200, maxY - 2);
        int endY = Math.max(minY + 1, 80);
        
        for (int y = startY; y >= endY; y--) {
            BlockPos localCheck = new BlockPos(localX, y, localZ);
            if (isSafeGroundBlock(chunk, localCheck)) {
                BlockPos localSpawn = new BlockPos(localX, y + 1, localZ);
                BlockPos localAbove = new BlockPos(localX, y + 2, localZ);
                if (isSafeAirSpace(chunk, localSpawn, localAbove) &&
                        !isDangerousLocation(chunk, localSpawn) && !isUnderground(chunk, localX, y + 1, localZ)) {
                    return new BlockPos(x, y + 1, z);
                }
            }
        }
        
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
    
    private static boolean isSafeGroundBlock(ChunkAccess chunk, BlockPos pos) {
        var state = chunk.getBlockState(pos);
        return state.isSolidRender(chunk, pos) && !state.isAir() && 
               !DANGEROUS_GROUND_BLOCKS.contains(state.getBlock());
    }
    
    private static boolean isSafeAirSpace(ChunkAccess chunk, BlockPos spawnPos, BlockPos abovePos) {
        return chunk.getBlockState(spawnPos).isAir() && chunk.getBlockState(abovePos).isAir();
    }
    
    private static boolean isUnderground(ChunkAccess chunk, int localX, int y, int localZ) {
        for (int checkY = y + 2; checkY <= y + 5; checkY++) {
            BlockPos checkPos = new BlockPos(localX, checkY, localZ);
            if (chunk.getBlockState(checkPos).isSolidRender(chunk, checkPos)) {
                return true;
            }
        }
        return false;
    }
    
    private static boolean isDangerousLocation(ChunkAccess chunk, BlockPos localPos) {
        BlockPos localBelow = new BlockPos(localPos.getX(), localPos.getY() - 1, localPos.getZ());
        return DANGEROUS_GROUND_BLOCKS.contains(chunk.getBlockState(localBelow).getBlock());
    }
    
    private static boolean isSafeSpotFromChunk(ServerLevel level, ChunkAccess chunk, BlockPos pos) {
        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        int y = pos.getY();
        
        BlockPos localPos = new BlockPos(localX, y, localZ);
        BlockPos localBelow = new BlockPos(localX, y - 1, localZ);
        BlockPos localAbove = new BlockPos(localX, y + 1, localZ);
        
        if (!chunk.getBlockState(localBelow).isSolidRender(chunk, localBelow)) return false;
        if (!chunk.getBlockState(localPos).isAir() || !chunk.getBlockState(localAbove).isAir()) return false;
        if (DANGEROUS_GROUND_BLOCKS.contains(chunk.getBlockState(localBelow).getBlock())) return false;
        
        int airBlocksBelow = 0;
        for (int i = 2; i <= 4; i++) {
            BlockPos localBelowI = new BlockPos(localX, y - i, localZ);
            if (chunk.getBlockState(localBelowI).isAir()) {
                airBlocksBelow++;
            } else {
                break;
            }
        }
        if (airBlocksBelow >= 3) return false;
        
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int checkX = localX + dx;
                int checkZ = localZ + dz;
                if (checkX < 0 || checkX > 15 || checkZ < 0 || checkZ > 15) continue;
                
                BlockPos checkPos = new BlockPos(checkX, y, checkZ);
                if (NEARBY_DANGER_BLOCKS.contains(chunk.getBlockState(checkPos).getBlock())) return false;
                
                BlockPos checkBelow = new BlockPos(checkX, y - 1, checkZ);
                if (chunk.getBlockState(checkBelow).getBlock() == Blocks.LAVA) return false;
            }
        }
        
        if (level.dimension() == net.minecraft.world.level.Level.END) {
            if (pos.getY() < 50) return false;
            double distFromCenter = Math.sqrt(pos.getX() * pos.getX() + pos.getZ() * pos.getZ());
            if (distFromCenter < 100) return false;
        }
        
        if (!level.getWorldBorder().isWithinBounds(pos)) return false;
        
        return true;
    }
}
