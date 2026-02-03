package network.vonix.vonixcore.teleport;

/**
 * Performance metrics for RTP operations.
 * Used for monitoring and optimization of RTP performance.
 */
public class RTPMetrics {
    private final long requestId;
    private final long startTime;
    private final long endTime;
    private final long chunkLoadTime;
    private final long locationSearchTime;
    private final long safetyValidationTime;
    private final int chunksLoaded;
    private final int locationsChecked;
    private final boolean successful;
    private final String failureReason;
    private final long mainThreadTime;
    private final long memoryUsed;

    private RTPMetrics(Builder builder) {
        this.requestId = builder.requestId;
        this.startTime = builder.startTime;
        this.endTime = builder.endTime;
        this.chunkLoadTime = builder.chunkLoadTime;
        this.locationSearchTime = builder.locationSearchTime;
        this.safetyValidationTime = builder.safetyValidationTime;
        this.chunksLoaded = builder.chunksLoaded;
        this.locationsChecked = builder.locationsChecked;
        this.successful = builder.successful;
        this.failureReason = builder.failureReason;
        this.mainThreadTime = builder.mainThreadTime;
        this.memoryUsed = builder.memoryUsed;
    }

    // Getters
    public long getRequestId() { return requestId; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }
    public long getTotalTime() { return endTime - startTime; }
    public long getChunkLoadTime() { return chunkLoadTime; }
    public long getLocationSearchTime() { return locationSearchTime; }
    public long getSafetyValidationTime() { return safetyValidationTime; }
    public int getChunksLoaded() { return chunksLoaded; }
    public int getLocationsChecked() { return locationsChecked; }
    public boolean isSuccessful() { return successful; }
    public String getFailureReason() { return failureReason; }
    public long getMainThreadTime() { return mainThreadTime; }
    public long getMemoryUsed() { return memoryUsed; }

    /**
     * Check if performance thresholds were met
     */
    public boolean meetsPerformanceThresholds() {
        return mainThreadTime <= 1 && // Max 1ms main thread impact
               getTotalTime() <= 5000 && // Max 5 seconds total time
               chunkLoadTime <= 2000; // Max 2 seconds chunk loading
    }

    /**
     * Get performance efficiency score (0.0 to 1.0)
     */
    public double getEfficiencyScore() {
        if (!successful) return 0.0;
        
        double timeScore = Math.max(0.0, 1.0 - (getTotalTime() / 5000.0));
        double chunkScore = chunksLoaded > 0 ? Math.max(0.0, 1.0 - (chunkLoadTime / 2000.0)) : 1.0;
        double attemptScore = locationsChecked > 0 ? Math.min(1.0, 10.0 / locationsChecked) : 1.0;
        
        return (timeScore + chunkScore + attemptScore) / 3.0;
    }

    public static Builder builder(long requestId) {
        return new Builder(requestId);
    }

    public static class Builder {
        private final long requestId;
        private long startTime = System.currentTimeMillis();
        private long endTime;
        private long chunkLoadTime = 0;
        private long locationSearchTime = 0;
        private long safetyValidationTime = 0;
        private int chunksLoaded = 0;
        private int locationsChecked = 0;
        private boolean successful = false;
        private String failureReason;
        private long mainThreadTime = 0;
        private long memoryUsed = 0;

        public Builder(long requestId) {
            this.requestId = requestId;
        }

        public Builder startTime(long startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder endTime(long endTime) {
            this.endTime = endTime;
            return this;
        }

        public Builder chunkLoadTime(long chunkLoadTime) {
            this.chunkLoadTime = chunkLoadTime;
            return this;
        }

        public Builder locationSearchTime(long locationSearchTime) {
            this.locationSearchTime = locationSearchTime;
            return this;
        }

        public Builder safetyValidationTime(long safetyValidationTime) {
            this.safetyValidationTime = safetyValidationTime;
            return this;
        }

        public Builder chunksLoaded(int chunksLoaded) {
            this.chunksLoaded = chunksLoaded;
            return this;
        }

        public Builder locationsChecked(int locationsChecked) {
            this.locationsChecked = locationsChecked;
            return this;
        }

        public Builder successful(boolean successful) {
            this.successful = successful;
            return this;
        }

        public Builder failureReason(String failureReason) {
            this.failureReason = failureReason;
            return this;
        }

        public Builder mainThreadTime(long mainThreadTime) {
            this.mainThreadTime = mainThreadTime;
            return this;
        }

        public Builder memoryUsed(long memoryUsed) {
            this.memoryUsed = memoryUsed;
            return this;
        }

        public RTPMetrics build() {
            if (endTime == 0) {
                endTime = System.currentTimeMillis();
            }
            return new RTPMetrics(this);
        }
    }

    @Override
    public String toString() {
        return String.format("RTPMetrics{id=%d, success=%s, totalTime=%dms, chunks=%d, locations=%d, efficiency=%.2f}", 
                           requestId, successful, getTotalTime(), chunksLoaded, locationsChecked, getEfficiencyScore());
    }
}