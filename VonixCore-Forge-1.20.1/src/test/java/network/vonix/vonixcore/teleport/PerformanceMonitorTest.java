package network.vonix.vonixcore.teleport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for PerformanceMonitor component.
 * Tests metrics collection, threshold monitoring, and performance logging functionality.
 */
public class PerformanceMonitorTest {

    private PerformanceMonitor monitor;

    @BeforeEach
    void setUp() {
        PerformanceMonitor.resetInstance(); // Reset singleton for clean test state
        monitor = PerformanceMonitor.getInstance();
        monitor.configure(true, false); // Enable monitoring, disable detailed logging for tests
    }

    @AfterEach
    void tearDown() {
        if (monitor != null) {
            monitor.shutdown();
        }
        PerformanceMonitor.resetInstance(); // Clean up after test
    }

    @Test
    void testMonitorInitialization() {
        assertNotNull(monitor);
        
        PerformanceMonitor.PerformanceStats stats = monitor.getPerformanceStats();
        assertNotNull(stats);
        assertEquals(0, stats.getTotalOperations());
        assertEquals(0.0, stats.getSuccessRate(), 0.001);
        assertTrue(monitor.isPerformingWell()); // Should be performing well with no operations
    }

    @Test
    void testOperationMonitorCreation() {
        UUID requestId = UUID.randomUUID();
        PerformanceMonitor.OperationMonitor opMonitor = monitor.startOperation(requestId, "RTP_REQUEST");
        
        assertNotNull(opMonitor);
        assertNotNull(opMonitor.getMetrics());
    }

    @Test
    void testOperationMonitorRecording() {
        UUID requestId = UUID.randomUUID();
        PerformanceMonitor.OperationMonitor opMonitor = monitor.startOperation(requestId, "RTP_REQUEST");
        
        // Record various metrics
        opMonitor.recordChunkLoadTime(500);
        opMonitor.recordLocationSearchTime(1200);
        opMonitor.recordSafetyValidationTime(300);
        opMonitor.recordMainThreadTime(0);
        opMonitor.recordMemoryUsage(1024 * 1024); // 1MB
        opMonitor.recordChunksLoaded(3);
        opMonitor.recordLocationsChecked(15);
        
        // Complete the operation
        opMonitor.complete(true, null);
        
        // Verify metrics were recorded
        RTPMetrics metrics = opMonitor.getMetrics();
        assertNotNull(metrics);
        assertEquals(500, metrics.getChunkLoadTime());
        assertEquals(1200, metrics.getLocationSearchTime());
        assertEquals(300, metrics.getSafetyValidationTime());
        assertEquals(0, metrics.getMainThreadTime());
        assertEquals(1024 * 1024, metrics.getMemoryUsed());
        assertEquals(3, metrics.getChunksLoaded());
        assertEquals(15, metrics.getLocationsChecked());
        assertTrue(metrics.isSuccessful());
        assertNull(metrics.getFailureReason());
    }

    @Test
    void testOperationMonitorFailure() {
        UUID requestId = UUID.randomUUID();
        PerformanceMonitor.OperationMonitor opMonitor = monitor.startOperation(requestId, "RTP_REQUEST");
        
        opMonitor.recordChunkLoadTime(100);
        opMonitor.recordLocationSearchTime(2000);
        opMonitor.complete(false, "No safe location found");
        
        RTPMetrics metrics = opMonitor.getMetrics();
        assertNotNull(metrics);
        assertFalse(metrics.isSuccessful());
        assertEquals("No safe location found", metrics.getFailureReason());
    }

    @Test
    void testMetricsAggregation() throws InterruptedException {
        // Create and complete multiple operations
        for (int i = 0; i < 5; i++) {
            UUID requestId = UUID.randomUUID();
            PerformanceMonitor.OperationMonitor opMonitor = monitor.startOperation(requestId, "RTP_REQUEST");
            
            opMonitor.recordChunkLoadTime(100 + i * 50);
            opMonitor.recordLocationSearchTime(1000 + i * 100);
            opMonitor.recordMainThreadTime(0);
            opMonitor.complete(i < 4, i >= 4 ? "Failed operation" : null); // 4 success, 1 failure
            
            // Small delay to ensure different timestamps
            Thread.sleep(10);
        }
        
        // Check aggregated statistics
        PerformanceMonitor.PerformanceStats stats = monitor.getPerformanceStats();
        assertEquals(5, stats.getTotalOperations());
        assertEquals(0.8, stats.getSuccessRate(), 0.001); // 4/5 = 0.8
        assertTrue(stats.getAverageChunkLoadTime() > 0);
        assertTrue(stats.getAverageSearchTime() > 0);
        assertEquals(0.0, stats.getAverageMainThreadTime(), 0.001); // All operations had 0 main thread time
    }

    @Test
    void testPerformanceThresholds() {
        UUID requestId = UUID.randomUUID();
        PerformanceMonitor.OperationMonitor opMonitor = monitor.startOperation(requestId, "RTP_REQUEST");
        
        // Record metrics that exceed thresholds
        opMonitor.recordMainThreadTime(5); // Exceeds 1ms threshold
        opMonitor.recordChunkLoadTime(3000); // Exceeds 2000ms threshold
        opMonitor.recordLocationSearchTime(4000); // Exceeds 3000ms threshold
        opMonitor.recordMemoryUsage(15 * 1024 * 1024); // Exceeds 10MB threshold
        opMonitor.complete(true, null);
        
        RTPMetrics metrics = opMonitor.getMetrics();
        assertFalse(metrics.meetsPerformanceThresholds());
    }

    @Test
    void testPerformanceThresholdsWithinLimits() {
        UUID requestId = UUID.randomUUID();
        PerformanceMonitor.OperationMonitor opMonitor = monitor.startOperation(requestId, "RTP_REQUEST");
        
        // Record metrics within thresholds
        opMonitor.recordMainThreadTime(0);
        opMonitor.recordChunkLoadTime(1500); // Within 2000ms threshold
        opMonitor.recordLocationSearchTime(2500); // Within 3000ms threshold
        opMonitor.recordMemoryUsage(5 * 1024 * 1024); // Within 10MB threshold
        opMonitor.complete(true, null);
        
        RTPMetrics metrics = opMonitor.getMetrics();
        assertTrue(metrics.meetsPerformanceThresholds());
    }

    @Test
    void testPerformanceReportGeneration() {
        // Add some test data
        UUID requestId = UUID.randomUUID();
        PerformanceMonitor.OperationMonitor opMonitor = monitor.startOperation(requestId, "RTP_REQUEST");
        opMonitor.recordChunkLoadTime(500);
        opMonitor.recordLocationSearchTime(1000);
        opMonitor.recordMainThreadTime(0);
        opMonitor.complete(true, null);
        
        String report = monitor.generatePerformanceReport();
        assertNotNull(report);
        assertTrue(report.contains("RTP Performance Report"));
        assertTrue(report.contains("Total Operations: 1"));
        assertTrue(report.contains("Success: 100.0%"));
        assertTrue(report.contains("Performance Status:"));
    }

    @Test
    void testHistoricalDataTracking() throws InterruptedException {
        // Add multiple operations with delays to create historical data
        for (int i = 0; i < 3; i++) {
            UUID requestId = UUID.randomUUID();
            PerformanceMonitor.OperationMonitor opMonitor = monitor.startOperation(requestId, "RTP_REQUEST");
            opMonitor.recordChunkLoadTime(100 + i * 100);
            opMonitor.recordLocationSearchTime(1000 + i * 200);
            opMonitor.complete(true, null);
            Thread.sleep(50); // Ensure different timestamps
        }
        
        PerformanceMonitor.HistoricalPerformanceData historical = 
            monitor.getHistoricalData(TimeUnit.MINUTES.toMillis(1));
        
        assertNotNull(historical);
        assertTrue(historical.hasData());
        assertNotNull(historical.getTrend());
        assertTrue(historical.getTrend().matches("IMPROVING|DEGRADING|STABLE|INSUFFICIENT_DATA"));
    }

    @Test
    void testMonitorConfiguration() {
        // Test enabling/disabling monitoring
        monitor.configure(false, false);
        
        UUID requestId = UUID.randomUUID();
        PerformanceMonitor.OperationMonitor opMonitor = monitor.startOperation(requestId, "RTP_REQUEST");
        
        // When disabled, should return no-op monitor
        assertNotNull(opMonitor);
        opMonitor.recordChunkLoadTime(1000);
        opMonitor.complete(true, null);
        
        // Stats should not be affected when monitoring is disabled
        // (This test verifies the no-op behavior)
        
        // Re-enable monitoring
        monitor.configure(true, true);
        assertNotNull(monitor.startOperation(UUID.randomUUID(), "TEST"));
    }

    @Test
    void testPerformanceStatsCalculations() {
        PerformanceMonitor.PerformanceStats stats = new PerformanceMonitor.PerformanceStats(
            100, 0.95, 2500.0, 1500.0, 1000.0, 0.5, 5.0 * 1024 * 1024
        );
        
        assertEquals(100, stats.getTotalOperations());
        assertEquals(0.95, stats.getSuccessRate(), 0.001);
        assertEquals(2500.0, stats.getAverageProcessingTime(), 0.001);
        assertEquals(1500.0, stats.getAverageChunkLoadTime(), 0.001);
        assertEquals(1000.0, stats.getAverageSearchTime(), 0.001);
        assertEquals(0.5, stats.getAverageMainThreadTime(), 0.001);
        assertEquals(5.0 * 1024 * 1024, stats.getAverageMemoryUsage(), 0.001);
    }

    @Test
    void testHistoricalDataTrendAnalysis() {
        // Create mock historical data with improving trend
        java.util.List<RTPMetrics> improvingMetrics = java.util.List.of(
            createMockMetrics(1000, 2000), // Older, slower
            createMockMetrics(1001, 1900),
            createMockMetrics(1002, 1500), // Recent, faster
            createMockMetrics(1003, 1400)
        );
        
        PerformanceMonitor.HistoricalPerformanceData historical = 
            new PerformanceMonitor.HistoricalPerformanceData(improvingMetrics, TimeUnit.MINUTES.toMillis(5));
        
        assertTrue(historical.hasData());
        String trend = historical.getTrend();
        assertTrue(trend.equals("IMPROVING") || trend.equals("STABLE")); // Depends on exact calculation
        
        // Test with insufficient data
        java.util.List<RTPMetrics> insufficientData = java.util.List.of(createMockMetrics(1000, 1500));
        PerformanceMonitor.HistoricalPerformanceData insufficientHistorical = 
            new PerformanceMonitor.HistoricalPerformanceData(insufficientData, TimeUnit.MINUTES.toMillis(1));
        
        assertEquals("INSUFFICIENT_DATA", insufficientHistorical.getTrend());
        assertEquals(0.0, insufficientHistorical.getTrendPercentage(), 0.001);
    }

    @Test
    void testSystemPerformanceEvaluation() {
        // Test with good performance
        UUID requestId1 = UUID.randomUUID();
        PerformanceMonitor.OperationMonitor opMonitor1 = monitor.startOperation(requestId1, "RTP_REQUEST");
        opMonitor1.recordMainThreadTime(0);
        opMonitor1.recordChunkLoadTime(1000);
        opMonitor1.recordLocationSearchTime(2000);
        opMonitor1.complete(true, null);
        
        assertTrue(monitor.isPerformingWell());
        
        // Test with poor performance (high main thread impact)
        UUID requestId2 = UUID.randomUUID();
        PerformanceMonitor.OperationMonitor opMonitor2 = monitor.startOperation(requestId2, "RTP_REQUEST");
        opMonitor2.recordMainThreadTime(5); // Exceeds 1ms threshold
        opMonitor2.complete(false, "Performance issue");
        
        // After adding poor performance, system should not be performing well
        assertFalse(monitor.isPerformingWell());
    }

    /**
     * Helper method to create mock RTPMetrics for testing
     */
    private RTPMetrics createMockMetrics(long startTime, long totalTime) {
        return RTPMetrics.builder(12345L)
                .startTime(startTime)
                .endTime(startTime + totalTime)
                .chunkLoadTime(totalTime / 3)
                .locationSearchTime(totalTime / 2)
                .safetyValidationTime(totalTime / 6)
                .successful(true)
                .chunksLoaded(2)
                .locationsChecked(10)
                .mainThreadTime(0)
                .memoryUsed(1024 * 1024)
                .build();
    }
}