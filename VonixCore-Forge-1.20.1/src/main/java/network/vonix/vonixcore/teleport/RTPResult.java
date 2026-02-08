package network.vonix.vonixcore.teleport;

import net.minecraft.core.BlockPos;

import java.util.Optional;

/**
 * Result of an RTP operation containing success/failure information and metrics.
 * Immutable result object for thread-safe access to operation outcomes.
 */
public class RTPResult {
    private final boolean success;
    private final BlockPos location;
    private final String failureReason;
    private final int attemptsUsed;
    private final long processingTimeMs;
    private final long chunkLoadTimeMs;
    private final int chunksLoaded;
    private final RTPMetrics metrics;

    private RTPResult(Builder builder) {
        this.success = builder.success;
        this.location = builder.location;
        this.failureReason = builder.failureReason;
        this.attemptsUsed = builder.attemptsUsed;
        this.processingTimeMs = builder.processingTimeMs;
        this.chunkLoadTimeMs = builder.chunkLoadTimeMs;
        this.chunksLoaded = builder.chunksLoaded;
        this.metrics = builder.metrics;
    }

    // Getters
    public boolean isSuccess() { return success; }
    public Optional<BlockPos> getLocation() { return Optional.ofNullable(location); }
    public Optional<String> getFailureReason() { return Optional.ofNullable(failureReason); }
    public int getAttemptsUsed() { return attemptsUsed; }
    public long getProcessingTimeMs() { return processingTimeMs; }
    public long getChunkLoadTimeMs() { return chunkLoadTimeMs; }
    public int getChunksLoaded() { return chunksLoaded; }
    public Optional<RTPMetrics> getMetrics() { return Optional.ofNullable(metrics); }

    /**
     * Create a successful result
     */
    public static RTPResult success(BlockPos location, int attemptsUsed, long processingTimeMs) {
        return new Builder()
                .success(true)
                .location(location)
                .attemptsUsed(attemptsUsed)
                .processingTimeMs(processingTimeMs)
                .build();
    }

    /**
     * Create a failed result
     */
    public static RTPResult failure(String reason, int attemptsUsed, long processingTimeMs) {
        return new Builder()
                .success(false)
                .failureReason(reason)
                .attemptsUsed(attemptsUsed)
                .processingTimeMs(processingTimeMs)
                .build();
    }

    /**
     * Create a result builder for complex scenarios
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean success = false;
        private BlockPos location;
        private String failureReason;
        private int attemptsUsed = 0;
        private long processingTimeMs = 0;
        private long chunkLoadTimeMs = 0;
        private int chunksLoaded = 0;
        private RTPMetrics metrics;

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder location(BlockPos location) {
            this.location = location;
            return this;
        }

        public Builder failureReason(String failureReason) {
            this.failureReason = failureReason;
            return this;
        }

        public Builder attemptsUsed(int attemptsUsed) {
            this.attemptsUsed = attemptsUsed;
            return this;
        }

        public Builder processingTimeMs(long processingTimeMs) {
            this.processingTimeMs = processingTimeMs;
            return this;
        }

        public Builder chunkLoadTimeMs(long chunkLoadTimeMs) {
            this.chunkLoadTimeMs = chunkLoadTimeMs;
            return this;
        }

        public Builder chunksLoaded(int chunksLoaded) {
            this.chunksLoaded = chunksLoaded;
            return this;
        }

        public Builder metrics(RTPMetrics metrics) {
            this.metrics = metrics;
            return this;
        }

        public RTPResult build() {
            return new RTPResult(this);
        }
    }

    @Override
    public String toString() {
        if (success) {
            return String.format("RTPResult{SUCCESS, location=%s, attempts=%d, time=%dms}", 
                               location, attemptsUsed, processingTimeMs);
        } else {
            return String.format("RTPResult{FAILURE, reason='%s', attempts=%d, time=%dms}", 
                               failureReason, attemptsUsed, processingTimeMs);
        }
    }
}