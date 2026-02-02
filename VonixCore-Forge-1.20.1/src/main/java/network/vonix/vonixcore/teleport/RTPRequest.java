package network.vonix.vonixcore.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Represents an RTP request with all necessary context and state tracking.
 * Immutable data class for thread-safe operations.
 */
public class RTPRequest {
    private final UUID requestId;
    private final UUID playerId;
    private final ServerPlayer player;
    private final ServerLevel targetWorld;
    private final RTPOptions options;
    private final long timestamp;
    private volatile RTPStatus status;
    private volatile BlockPos result;
    private volatile String failureReason;

    public RTPRequest(UUID requestId, ServerPlayer player, ServerLevel targetWorld, RTPOptions options) {
        this.requestId = requestId;
        this.playerId = player.getUUID();
        this.player = player;
        this.targetWorld = targetWorld;
        this.options = options;
        this.timestamp = System.currentTimeMillis();
        this.status = RTPStatus.QUEUED;
    }

    // Getters
    public UUID getRequestId() { return requestId; }
    public UUID getPlayerId() { return playerId; }
    public ServerPlayer getPlayer() { return player; }
    public ServerLevel getTargetWorld() { return targetWorld; }
    public RTPOptions getOptions() { return options; }
    public long getTimestamp() { return timestamp; }
    public RTPStatus getStatus() { return status; }
    public BlockPos getResult() { return result; }
    public String getFailureReason() { return failureReason; }

    // Status management (thread-safe)
    public void setStatus(RTPStatus status) { this.status = status; }
    public void setResult(BlockPos result) { this.result = result; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    /**
     * Check if the request is still valid (player online, not expired)
     */
    public boolean isValid() {
        return player != null && 
               player.isAlive() && 
               !player.hasDisconnected() &&
               (System.currentTimeMillis() - timestamp) < 300000; // 5 minute timeout
    }

    /**
     * Get age of request in milliseconds
     */
    public long getAge() {
        return System.currentTimeMillis() - timestamp;
    }

    @Override
    public String toString() {
        return String.format("RTPRequest{id=%s, player=%s, status=%s, age=%dms}", 
                           requestId, player.getName().getString(), status, getAge());
    }
}