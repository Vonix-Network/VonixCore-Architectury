package network.vonix.vonixcore.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import network.vonix.vonixcore.VonixCore;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages teleportation features including TPA, back locations, and safe
 * teleporting.
 */
public class TeleportManager {

    private static TeleportManager instance;

    // TPA requests: target -> requester
    private final Map<UUID, TpaRequest> tpaRequests = new ConcurrentHashMap<>();
    // Last locations for /back
    private final Map<UUID, TeleportLocation> lastLocations = new ConcurrentHashMap<>();
    // Teleport cooldowns
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public static TeleportManager getInstance() {
        if (instance == null) {
            instance = new TeleportManager();
        }
        return instance;
    }

    /**
     * Save player's current location for /back command.
     */
    public void saveLastLocation(ServerPlayer player, boolean isDeath) {
        lastLocations.put(player.getUUID(), new TeleportLocation(
                player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(),
                System.currentTimeMillis(),
                isDeath));
    }

    public void saveLastLocation(ServerPlayer player) {
        saveLastLocation(player, false);
    }

    /**
     * Get player's last location.
     */
    public TeleportLocation getLastLocation(UUID uuid) {
        return lastLocations.get(uuid);
    }

    /**
     * Send a TPA request.
     */
    public boolean sendTpaRequest(ServerPlayer requester, ServerPlayer target, boolean tpaHere) {
        UUID targetUuid = target.getUUID();

        // Check if there's already a pending request
        TpaRequest existing = tpaRequests.get(targetUuid);
        if (existing != null && !existing.isExpired()) {
            return false; // Already has pending request
        }

        tpaRequests.put(targetUuid, new TpaRequest(
                requester.getUUID(),
                requester.getName().getString(),
                tpaHere,
                System.currentTimeMillis()));
        return true;
    }

    /**
     * Accept a TPA request.
     */
    public boolean acceptTpaRequest(ServerPlayer target, MinecraftServer server) {
        TpaRequest request = tpaRequests.remove(target.getUUID());
        if (request == null || request.isExpired()) {
            return false;
        }

        ServerPlayer requester = server.getPlayerList().getPlayer(request.requesterUuid());
        if (requester == null) {
            return false;
        }

        if (request.tpaHere()) {
            // Teleport target to requester
            teleportPlayer(target, requester.level(), requester.getX(), requester.getY(), requester.getZ(),
                    requester.getYRot(), requester.getXRot());
        } else {
            // Teleport requester to target
            teleportPlayer(requester, target.level(), target.getX(), target.getY(), target.getZ(), target.getYRot(),
                    target.getXRot());
        }
        return true;
    }

    /**
     * Deny a TPA request.
     */
    public boolean denyTpaRequest(ServerPlayer target) {
        return tpaRequests.remove(target.getUUID()) != null;
    }

    /**
     * Get pending TPA request for a player.
     */
    public TpaRequest getTpaRequest(UUID targetUuid) {
        TpaRequest request = tpaRequests.get(targetUuid);
        if (request != null && request.isExpired()) {
            tpaRequests.remove(targetUuid);
            return null;
        }
        return request;
    }

    /**
     * Check if player is on teleport cooldown.
     */
    public boolean isOnCooldown(UUID uuid) {
        Long cooldownEnd = cooldowns.get(uuid);
        if (cooldownEnd == null)
            return false;
        if (System.currentTimeMillis() > cooldownEnd) {
            cooldowns.remove(uuid);
            return false;
        }
        return true;
    }

    /**
     * Get remaining cooldown in seconds.
     */
    public int getRemainingCooldown(UUID uuid) {
        Long cooldownEnd = cooldowns.get(uuid);
        if (cooldownEnd == null)
            return 0;
        long remaining = (cooldownEnd - System.currentTimeMillis()) / 1000;
        return Math.max(0, (int) remaining);
    }

    /**
     * Set teleport cooldown for a player.
     */
    public void setCooldown(UUID uuid, int seconds) {
        cooldowns.put(uuid, System.currentTimeMillis() + (seconds * 1000L));
    }

    /**
     * Teleport a player to a location.
     */
    public void teleportPlayer(ServerPlayer player, Level level, double x, double y, double z, float yaw, float pitch) {
        saveLastLocation(player);

        if (level instanceof ServerLevel serverLevel) {
            player.teleportTo(serverLevel, x, y, z, yaw, pitch);
        }
    }

    /**
     * Find a safe teleport location near the given position.
     */
    public Optional<BlockPos> findSafeLocation(ServerLevel level, BlockPos pos) {
        // Check current position
        if (isSafe(level, pos)) {
            return Optional.of(pos);
        }

        // Search in expanding radius
        for (int r = 1; r <= 5; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -r; dy <= r; dy++) {
                        BlockPos check = pos.offset(dx, dy, dz);
                        if (isSafe(level, check)) {
                            return Optional.of(check);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Check if a position is safe for teleporting.
     */
    private boolean isSafe(ServerLevel level, BlockPos pos) {
        BlockPos feet = pos;
        BlockPos head = pos.above();
        BlockPos ground = pos.below();

        return level.getBlockState(feet).isAir()
                && level.getBlockState(head).isAir()
                && level.getBlockState(ground).isSolid();
    }

    /**
     * Clear all state (call on server shutdown).
     */
    public void clear() {
        tpaRequests.clear();
        lastLocations.clear();
        cooldowns.clear();
    }

    /**
     * TPA request record.
     */
    public record TpaRequest(UUID requesterUuid, String requesterName, boolean tpaHere, long timestamp) {
        private static final long EXPIRE_MS = 120000; // 2 minutes

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > EXPIRE_MS;
        }
    }

    /**
     * Teleport location record.
     */
    public record TeleportLocation(String world, double x, double y, double z, float yaw, float pitch, long timestamp,
            boolean isDeath) {
    }
}
