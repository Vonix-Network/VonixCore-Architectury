package network.vonix.vonixcore.teleport;

import network.vonix.vonixcore.config.EssentialsConfig;

import java.util.Set;

/**
 * Configuration options for RTP requests.
 * Immutable configuration object that defines search parameters and constraints.
 */
public class RTPOptions {
    private final int minRadius;
    private final int maxRadius;
    private final Set<String> allowedBiomes;
    private final Set<String> blockedBiomes;
    private final boolean respectWorldBorder;
    private final int safetyCheckRadius;
    private final int maxSearchAttempts;
    private final long searchTimeoutMs;
    private final boolean avoidStructures;
    private final boolean preferSurface;

    private RTPOptions(Builder builder) {
        this.minRadius = builder.minRadius;
        this.maxRadius = builder.maxRadius;
        this.allowedBiomes = Set.copyOf(builder.allowedBiomes);
        this.blockedBiomes = Set.copyOf(builder.blockedBiomes);
        this.respectWorldBorder = builder.respectWorldBorder;
        this.safetyCheckRadius = builder.safetyCheckRadius;
        this.maxSearchAttempts = builder.maxSearchAttempts;
        this.searchTimeoutMs = builder.searchTimeoutMs;
        this.avoidStructures = builder.avoidStructures;
        this.preferSurface = builder.preferSurface;
    }

    // Getters
    public int getMinRadius() { return minRadius; }
    public int getMaxRadius() { return maxRadius; }
    public Set<String> getAllowedBiomes() { return allowedBiomes; }
    public Set<String> getBlockedBiomes() { return blockedBiomes; }
    public boolean shouldRespectWorldBorder() { return respectWorldBorder; }
    public int getSafetyCheckRadius() { return safetyCheckRadius; }
    public int getMaxSearchAttempts() { return maxSearchAttempts; }
    public long getSearchTimeoutMs() { return searchTimeoutMs; }
    public boolean shouldAvoidStructures() { return avoidStructures; }
    public boolean shouldPreferSurface() { return preferSurface; }

    /**
     * Create default RTP options based on current configuration
     */
    public static RTPOptions createDefault() {
        try {
            return new Builder()
                    .minRadius(EssentialsConfig.CONFIG.rtpMinRange.get())
                    .maxRadius(EssentialsConfig.CONFIG.rtpMaxRange.get())
                    .build();
        } catch (Exception e) {
            // Fallback to hardcoded defaults if config is not available (e.g., in tests)
            return new Builder()
                    .minRadius(500)
                    .maxRadius(10000)
                    .build();
        }
    }

    /**
     * Create RTP options with custom parameters
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int minRadius = 500;
        private int maxRadius = 10000;
        private Set<String> allowedBiomes = Set.of();
        private Set<String> blockedBiomes = Set.of();
        private boolean respectWorldBorder = true;
        private int safetyCheckRadius = 3;
        private int maxSearchAttempts = 50;
        private long searchTimeoutMs = 30000; // 30 seconds
        private boolean avoidStructures = false;
        private boolean preferSurface = true;

        public Builder minRadius(int minRadius) {
            this.minRadius = Math.max(0, minRadius);
            return this;
        }

        public Builder maxRadius(int maxRadius) {
            this.maxRadius = Math.max(100, maxRadius);
            return this;
        }

        public Builder allowedBiomes(Set<String> allowedBiomes) {
            this.allowedBiomes = allowedBiomes != null ? allowedBiomes : Set.of();
            return this;
        }

        public Builder blockedBiomes(Set<String> blockedBiomes) {
            this.blockedBiomes = blockedBiomes != null ? blockedBiomes : Set.of();
            return this;
        }

        public Builder respectWorldBorder(boolean respectWorldBorder) {
            this.respectWorldBorder = respectWorldBorder;
            return this;
        }

        public Builder safetyCheckRadius(int safetyCheckRadius) {
            this.safetyCheckRadius = Math.max(1, Math.min(10, safetyCheckRadius));
            return this;
        }

        public Builder maxSearchAttempts(int maxSearchAttempts) {
            this.maxSearchAttempts = Math.max(10, Math.min(200, maxSearchAttempts));
            return this;
        }

        public Builder searchTimeoutMs(long searchTimeoutMs) {
            this.searchTimeoutMs = Math.max(5000, Math.min(120000, searchTimeoutMs));
            return this;
        }

        public Builder avoidStructures(boolean avoidStructures) {
            this.avoidStructures = avoidStructures;
            return this;
        }

        public Builder preferSurface(boolean preferSurface) {
            this.preferSurface = preferSurface;
            return this;
        }

        public RTPOptions build() {
            // Validate configuration
            if (minRadius >= maxRadius) {
                throw new IllegalArgumentException("minRadius must be less than maxRadius");
            }
            return new RTPOptions(this);
        }
    }

    @Override
    public String toString() {
        return String.format("RTPOptions{minRadius=%d, maxRadius=%d, attempts=%d, timeout=%dms}", 
                           minRadius, maxRadius, maxSearchAttempts, searchTimeoutMs);
    }
}