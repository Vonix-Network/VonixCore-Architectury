package network.vonix.vonixcore.teleport;

import network.vonix.vonixcore.VonixCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Comprehensive performance monitoring system for RTP operations.
 * 
 * This component provides:
 * - Real-time metrics collection for execution times and resource usage
 * - Configurable threshold monitoring with automatic warning generation
 * - Structured performance logging with detailed operation breakdowns
 * - Historical performance tracking and trend analysis
 * - Integration with the OptimizedRTPRequestManager for seamless monitoring
 * 
 * Requirements: 1.1, 1.4, 7.1, 7.2, 7.3, 7.4
 */
public class PerformanceMonitor {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceMonitor.class);
    
    // Performance thresholds (configurable)
    public static class Thresholds {
        public static final long MAX_MAIN_THREAD_TIME_MS = 1;
        public static final long MAX_TOTAL_PROCESSING_TIME_MS = 5000;
        public static final long MAX_CHUNK_LOAD_TIME_MS = 2000;
        public static final long MAX_LOCATION_SEARCH_TIME_MS = 3000;
        public static final long MAX_SAFETY_VALIDATION_TIME_MS = 1000;
        public static final int MAX_QUEUE_DEPTH = 100;
        public static final long MAX_MEMORY_PER_OPERATION_MB = 10;
        public static final double MIN_SUCCESS_RATE = 0.95; // 95%
        public static final int WARNING_COOLDOWN_MS = 30000; // 30 seconds between warnings
    }
    
    // Singleton instance
    private static volatile PerformanceMonitor instance;
    
    // Core monitoring components
    private final MetricsCollector metricsCollector;
    private final ThresholdMonitor thresholdMonitor;
    private final PerformanceLogger performanceLogger;
    private final HistoricalTracker historicalTracker;
    
    // Configuration
    private volatile boolean enabled = true;
    private volatile boolean detailedLogging = false;
    private volatile Thresholds customThresholds = new Thresholds();
    
    // Cleanup executor
    private final ScheduledExecutorService cleanupExecutor;
    
    private PerformanceMonitor() {
        this.metricsCollector = new MetricsCollector();
        this.thresholdMonitor = new ThresholdMonitor();
        this.performanceLogger = new PerformanceLogger();
        this.historicalTracker = new HistoricalTracker();
        
        // Initialize cleanup executor
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VonixCore-RTP-PerformanceMonitor-Cleanup");
            t.setDaemon(true);
            return t;
        });
        
        // Start periodic cleanup and reporting
        cleanupExecutor.scheduleAtFixedRate(this::performPeriodicMaintenance, 
                                          60, 60, TimeUnit.SECONDS); // Every minute
        
        LOGGER.info("[RTP-Performance] PerformanceMonitor initialized with thresholds: " +
                   "mainThread={}ms, processing={}ms, chunkLoad={}ms, search={}ms", 
                   Thresholds.MAX_MAIN_THREAD_TIME_MS, Thresholds.MAX_TOTAL_PROCESSING_TIME_MS,
                   Thresholds.MAX_CHUNK_LOAD_TIME_MS, Thresholds.MAX_LOCATION_SEARCH_TIME_MS);
    }
    
    /**
     * Get the singleton instance of the performance monitor
     */
    public static PerformanceMonitor getInstance() {
        if (instance == null) {
            synchronized (PerformanceMonitor.class) {
                if (instance == null) {
                    instance = new PerformanceMonitor();
                }
            }
        }
        return instance;
    }
    
    /**
     * Start monitoring an RTP operation
     */
    public OperationMonitor startOperation(UUID requestId, String operationType) {
        if (!enabled) {
            return new NoOpOperationMonitor();
        }
        
        return new OperationMonitorImpl(requestId, operationType);
    }
    
    /**
     * Record completed RTP metrics
     */
    public void recordMetrics(RTPMetrics metrics) {
        if (!enabled) {
            return;
        }
        
        metricsCollector.recordMetrics(metrics);
        thresholdMonitor.checkThresholds(metrics);
        performanceLogger.logMetrics(metrics);
        historicalTracker.addMetrics(metrics);
    }
    
    /**
     * Get current performance statistics
     */
    public PerformanceStats getPerformanceStats() {
        return metricsCollector.getStats();
    }
    
    /**
     * Get historical performance data
     */
    public HistoricalPerformanceData getHistoricalData(long periodMs) {
        return historicalTracker.getHistoricalData(periodMs);
    }
    
    /**
     * Check if system is meeting performance thresholds
     */
    public boolean isPerformingWell() {
        PerformanceStats stats = getPerformanceStats();
        
        // If no operations have been performed, consider the system as performing well
        if (stats.getTotalOperations() == 0) {
            return true;
        }
        
        return stats.getAverageMainThreadTime() <= Thresholds.MAX_MAIN_THREAD_TIME_MS &&
               stats.getAverageProcessingTime() <= Thresholds.MAX_TOTAL_PROCESSING_TIME_MS &&
               stats.getSuccessRate() >= Thresholds.MIN_SUCCESS_RATE;
    }
    
    /**
     * Generate performance report
     */
    public String generatePerformanceReport() {
        PerformanceStats stats = getPerformanceStats();
        HistoricalPerformanceData historical = getHistoricalData(TimeUnit.HOURS.toMillis(1));
        
        StringBuilder report = new StringBuilder();
        report.append("=== RTP Performance Report ===\n");
        report.append(String.format("Total Operations: %d (Success: %.1f%%)\n", 
                                  stats.getTotalOperations(), stats.getSuccessRate() * 100));
        report.append(String.format("Average Times: Processing=%.1fms, ChunkLoad=%.1fms, Search=%.1fms\n",
                                  stats.getAverageProcessingTime(), stats.getAverageChunkLoadTime(), 
                                  stats.getAverageSearchTime()));
        report.append(String.format("Main Thread Impact: %.1fms (Threshold: %dms)\n",
                                  stats.getAverageMainThreadTime(), Thresholds.MAX_MAIN_THREAD_TIME_MS));
        report.append(String.format("Performance Status: %s\n", isPerformingWell() ? "GOOD" : "DEGRADED"));
        
        if (historical.hasData()) {
            report.append(String.format("Hourly Trend: %s (%.1f%% change)\n",
                                      historical.getTrend(), historical.getTrendPercentage()));
        }
        
        return report.toString();
    }
    
    /**
     * Configure performance monitoring
     */
    public void configure(boolean enabled, boolean detailedLogging) {
        this.enabled = enabled;
        this.detailedLogging = detailedLogging;
        LOGGER.info("[RTP-Performance] Configuration updated: enabled={}, detailedLogging={}", 
                   enabled, detailedLogging);
    }
    
    /**
     * Shutdown the performance monitor
     */
    public void shutdown() {
        if (cleanupExecutor != null && !cleanupExecutor.isShutdown()) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cleanupExecutor.shutdownNow();
            }
        }
        LOGGER.info("[RTP-Performance] PerformanceMonitor shutdown complete");
    }
    
    /**
     * Reset the singleton instance (for testing purposes only)
     */
    public static void resetInstance() {
        synchronized (PerformanceMonitor.class) {
            if (instance != null) {
                instance.shutdown();
                instance = null;
            }
        }
    }
    
    /**
     * Periodic maintenance tasks
     */
    private void performPeriodicMaintenance() {
        try {
            historicalTracker.cleanup();
            thresholdMonitor.resetCooldowns();
            
            if (detailedLogging) {
                LOGGER.debug("[RTP-Performance] Periodic report: {}", generatePerformanceReport());
            }
        } catch (Exception e) {
            LOGGER.warn("[RTP-Performance] Error during periodic maintenance", e);
        }
    }
    
    /**
     * Interface for monitoring individual operations
     */
    public interface OperationMonitor {
        void recordChunkLoadTime(long timeMs);
        void recordLocationSearchTime(long timeMs);
        void recordSafetyValidationTime(long timeMs);
        void recordMainThreadTime(long timeMs);
        void recordMemoryUsage(long bytesUsed);
        void recordChunksLoaded(int count);
        void recordLocationsChecked(int count);
        void complete(boolean success, String failureReason);
        RTPMetrics getMetrics();
    }
    
    /**
     * Implementation of operation monitoring
     */
    private class OperationMonitorImpl implements OperationMonitor {
        private final RTPMetrics.Builder metricsBuilder;
        private final long startTime;
        private volatile boolean completed = false;
        
        public OperationMonitorImpl(UUID requestId, String operationType) {
            this.startTime = System.currentTimeMillis();
            this.metricsBuilder = RTPMetrics.builder(requestId.getMostSignificantBits())
                                           .startTime(startTime);
        }
        
        @Override
        public void recordChunkLoadTime(long timeMs) {
            metricsBuilder.chunkLoadTime(timeMs);
        }
        
        @Override
        public void recordLocationSearchTime(long timeMs) {
            metricsBuilder.locationSearchTime(timeMs);
        }
        
        @Override
        public void recordSafetyValidationTime(long timeMs) {
            metricsBuilder.safetyValidationTime(timeMs);
        }
        
        @Override
        public void recordMainThreadTime(long timeMs) {
            metricsBuilder.mainThreadTime(timeMs);
        }
        
        @Override
        public void recordMemoryUsage(long bytesUsed) {
            metricsBuilder.memoryUsed(bytesUsed);
        }
        
        @Override
        public void recordChunksLoaded(int count) {
            metricsBuilder.chunksLoaded(count);
        }
        
        @Override
        public void recordLocationsChecked(int count) {
            metricsBuilder.locationsChecked(count);
        }
        
        @Override
        public void complete(boolean success, String failureReason) {
            if (completed) {
                return;
            }
            
            completed = true;
            metricsBuilder.endTime(System.currentTimeMillis())
                         .successful(success)
                         .failureReason(failureReason);
            
            RTPMetrics metrics = metricsBuilder.build();
            recordMetrics(metrics);
        }
        
        @Override
        public RTPMetrics getMetrics() {
            return metricsBuilder.build();
        }
    }
    
    /**
     * No-op implementation for when monitoring is disabled
     */
    private static class NoOpOperationMonitor implements OperationMonitor {
        @Override public void recordChunkLoadTime(long timeMs) {}
        @Override public void recordLocationSearchTime(long timeMs) {}
        @Override public void recordSafetyValidationTime(long timeMs) {}
        @Override public void recordMainThreadTime(long timeMs) {}
        @Override public void recordMemoryUsage(long bytesUsed) {}
        @Override public void recordChunksLoaded(int count) {}
        @Override public void recordLocationsChecked(int count) {}
        @Override public void complete(boolean success, String failureReason) {}
        @Override public RTPMetrics getMetrics() { return null; }
    }
    
    /**
     * Metrics collection and aggregation
     */
    private static class MetricsCollector {
        private final AtomicLong totalOperations = new AtomicLong(0);
        private final AtomicLong successfulOperations = new AtomicLong(0);
        private final AtomicLong totalProcessingTime = new AtomicLong(0);
        private final AtomicLong totalChunkLoadTime = new AtomicLong(0);
        private final AtomicLong totalSearchTime = new AtomicLong(0);
        private final AtomicLong totalMainThreadTime = new AtomicLong(0);
        private final AtomicLong totalMemoryUsed = new AtomicLong(0);
        
        public void recordMetrics(RTPMetrics metrics) {
            totalOperations.incrementAndGet();
            if (metrics.isSuccessful()) {
                successfulOperations.incrementAndGet();
            }
            totalProcessingTime.addAndGet(metrics.getTotalTime());
            totalChunkLoadTime.addAndGet(metrics.getChunkLoadTime());
            totalSearchTime.addAndGet(metrics.getLocationSearchTime());
            totalMainThreadTime.addAndGet(metrics.getMainThreadTime());
            totalMemoryUsed.addAndGet(metrics.getMemoryUsed());
        }
        
        public PerformanceStats getStats() {
            long total = totalOperations.get();
            if (total == 0) {
                return new PerformanceStats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
            }
            
            return new PerformanceStats(
                total,
                (double) successfulOperations.get() / total,
                (double) totalProcessingTime.get() / total,
                (double) totalChunkLoadTime.get() / total,
                (double) totalSearchTime.get() / total,
                (double) totalMainThreadTime.get() / total,
                (double) totalMemoryUsed.get() / total
            );
        }
    }
    
    /**
     * Performance statistics data class
     */
    public static class PerformanceStats {
        private final long totalOperations;
        private final double successRate;
        private final double averageProcessingTime;
        private final double averageChunkLoadTime;
        private final double averageSearchTime;
        private final double averageMainThreadTime;
        private final double averageMemoryUsage;
        
        public PerformanceStats(long totalOperations, double successRate, 
                              double averageProcessingTime, double averageChunkLoadTime,
                              double averageSearchTime, double averageMainThreadTime,
                              double averageMemoryUsage) {
            this.totalOperations = totalOperations;
            this.successRate = successRate;
            this.averageProcessingTime = averageProcessingTime;
            this.averageChunkLoadTime = averageChunkLoadTime;
            this.averageSearchTime = averageSearchTime;
            this.averageMainThreadTime = averageMainThreadTime;
            this.averageMemoryUsage = averageMemoryUsage;
        }
        
        // Getters
        public long getTotalOperations() { return totalOperations; }
        public double getSuccessRate() { return successRate; }
        public double getAverageProcessingTime() { return averageProcessingTime; }
        public double getAverageChunkLoadTime() { return averageChunkLoadTime; }
        public double getAverageSearchTime() { return averageSearchTime; }
        public double getAverageMainThreadTime() { return averageMainThreadTime; }
        public double getAverageMemoryUsage() { return averageMemoryUsage; }
    }
    
    /**
     * Threshold monitoring with configurable warnings
     */
    private static class ThresholdMonitor {
        private final Map<String, Long> lastWarningTimes = new ConcurrentHashMap<>();
        
        public void checkThresholds(RTPMetrics metrics) {
            checkThreshold("mainThread", metrics.getMainThreadTime(), 
                          Thresholds.MAX_MAIN_THREAD_TIME_MS, "Main thread impact");
            checkThreshold("processing", metrics.getTotalTime(), 
                          Thresholds.MAX_TOTAL_PROCESSING_TIME_MS, "Total processing time");
            checkThreshold("chunkLoad", metrics.getChunkLoadTime(), 
                          Thresholds.MAX_CHUNK_LOAD_TIME_MS, "Chunk loading time");
            checkThreshold("search", metrics.getLocationSearchTime(), 
                          Thresholds.MAX_LOCATION_SEARCH_TIME_MS, "Location search time");
            checkThreshold("memory", metrics.getMemoryUsed() / (1024 * 1024), 
                          Thresholds.MAX_MEMORY_PER_OPERATION_MB, "Memory usage");
        }
        
        private void checkThreshold(String type, long value, long threshold, String description) {
            if (value > threshold) {
                String key = type + "_threshold";
                long now = System.currentTimeMillis();
                Long lastWarning = lastWarningTimes.get(key);
                
                if (lastWarning == null || (now - lastWarning) > Thresholds.WARNING_COOLDOWN_MS) {
                    LOGGER.warn("[RTP-Performance] {} exceeded threshold: {}ms > {}ms", 
                               description, value, threshold);
                    lastWarningTimes.put(key, now);
                }
            }
        }
        
        public void resetCooldowns() {
            long cutoff = System.currentTimeMillis() - (Thresholds.WARNING_COOLDOWN_MS * 2);
            lastWarningTimes.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
    }
    
    /**
     * Structured performance logging
     */
    private static class PerformanceLogger {
        private static final DateTimeFormatter TIMESTAMP_FORMAT = 
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(java.time.ZoneId.systemDefault());
        
        public void logMetrics(RTPMetrics metrics) {
            if (LOGGER.isDebugEnabled()) {
                String timestamp = TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(metrics.getStartTime()));
                LOGGER.debug("[RTP-Performance] {} | {} | Total: {}ms | Chunks: {}ms | Search: {}ms | " +
                           "Safety: {}ms | MainThread: {}ms | Memory: {}KB | Success: {} | Efficiency: {:.2f}",
                           timestamp, metrics.getRequestId(), metrics.getTotalTime(),
                           metrics.getChunkLoadTime(), metrics.getLocationSearchTime(),
                           metrics.getSafetyValidationTime(), metrics.getMainThreadTime(),
                           metrics.getMemoryUsed() / 1024, metrics.isSuccessful(),
                           metrics.getEfficiencyScore());
            }
        }
    }
    
    /**
     * Historical performance tracking
     */
    private static class HistoricalTracker {
        private final Queue<RTPMetrics> recentMetrics = new ConcurrentLinkedQueue<>();
        private final long MAX_HISTORY_AGE_MS = TimeUnit.HOURS.toMillis(24); // 24 hours
        
        public void addMetrics(RTPMetrics metrics) {
            recentMetrics.offer(metrics);
        }
        
        public HistoricalPerformanceData getHistoricalData(long periodMs) {
            long cutoff = System.currentTimeMillis() - periodMs;
            List<RTPMetrics> relevantMetrics = recentMetrics.stream()
                .filter(m -> m.getStartTime() >= cutoff)
                .toList();
            
            return new HistoricalPerformanceData(relevantMetrics, periodMs);
        }
        
        public void cleanup() {
            long cutoff = System.currentTimeMillis() - MAX_HISTORY_AGE_MS;
            recentMetrics.removeIf(metrics -> metrics.getStartTime() < cutoff);
        }
    }
    
    /**
     * Historical performance data analysis
     */
    public static class HistoricalPerformanceData {
        private final List<RTPMetrics> metrics;
        private final long periodMs;
        
        public HistoricalPerformanceData(List<RTPMetrics> metrics, long periodMs) {
            this.metrics = new ArrayList<>(metrics);
            this.periodMs = periodMs;
        }
        
        public boolean hasData() {
            return !metrics.isEmpty();
        }
        
        public String getTrend() {
            if (metrics.size() < 2) {
                return "INSUFFICIENT_DATA";
            }
            
            // Simple trend analysis based on recent vs older performance
            int halfPoint = metrics.size() / 2;
            double recentAvg = metrics.subList(halfPoint, metrics.size()).stream()
                .mapToDouble(RTPMetrics::getTotalTime)
                .average().orElse(0.0);
            double olderAvg = metrics.subList(0, halfPoint).stream()
                .mapToDouble(RTPMetrics::getTotalTime)
                .average().orElse(0.0);
            
            if (recentAvg < olderAvg * 0.9) {
                return "IMPROVING";
            } else if (recentAvg > olderAvg * 1.1) {
                return "DEGRADING";
            } else {
                return "STABLE";
            }
        }
        
        public double getTrendPercentage() {
            if (metrics.size() < 2) {
                return 0.0;
            }
            
            int halfPoint = metrics.size() / 2;
            double recentAvg = metrics.subList(halfPoint, metrics.size()).stream()
                .mapToDouble(RTPMetrics::getTotalTime)
                .average().orElse(0.0);
            double olderAvg = metrics.subList(0, halfPoint).stream()
                .mapToDouble(RTPMetrics::getTotalTime)
                .average().orElse(0.0);
            
            if (olderAvg == 0.0) {
                return 0.0;
            }
            
            return ((recentAvg - olderAvg) / olderAvg) * 100.0;
        }
    }
}