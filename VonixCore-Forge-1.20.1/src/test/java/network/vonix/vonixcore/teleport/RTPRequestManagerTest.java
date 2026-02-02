package network.vonix.vonixcore.teleport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for RTPRequestManager interface and OptimizedRTPRequestManager implementation.
 * Tests core functionality without requiring a full Minecraft server environment.
 */
public class RTPRequestManagerTest {

    private OptimizedRTPRequestManager manager;

    @BeforeEach
    void setUp() {
        manager = OptimizedRTPRequestManager.getInstance();
    }

    @AfterEach
    void tearDown() {
        // Clean up any active requests
        if (manager != null) {
            try {
                manager.shutdown(5000).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Ignore cleanup errors in tests
            }
        }
    }

    @Test
    void testManagerInitialization() {
        assertNotNull(manager);
        assertEquals(0, manager.getQueueDepth());
        
        RTPRequestManager.RTPPerformanceStats stats = manager.getPerformanceStats();
        assertNotNull(stats);
        assertEquals(0, stats.getTotalRequests());
        assertEquals(0, stats.getCurrentConcurrency());
    }

    @Test
    void testRTPOptionsCreation() {
        RTPOptions defaultOptions = RTPOptions.createDefault();
        assertNotNull(defaultOptions);
        assertTrue(defaultOptions.getMinRadius() >= 0);
        assertTrue(defaultOptions.getMaxRadius() > defaultOptions.getMinRadius());
        assertTrue(defaultOptions.getMaxSearchAttempts() > 0);
        assertTrue(defaultOptions.getSearchTimeoutMs() > 0);
    }

    @Test
    void testRTPOptionsBuilder() {
        RTPOptions options = RTPOptions.builder()
                .minRadius(1000)
                .maxRadius(5000)
                .maxSearchAttempts(25)
                .searchTimeoutMs(15000)
                .respectWorldBorder(false)
                .build();

        assertEquals(1000, options.getMinRadius());
        assertEquals(5000, options.getMaxRadius());
        assertEquals(25, options.getMaxSearchAttempts());
        assertEquals(15000, options.getSearchTimeoutMs());
        assertFalse(options.shouldRespectWorldBorder());
    }

    @Test
    void testRTPOptionsValidation() {
        // Test invalid configuration (minRadius >= maxRadius)
        assertThrows(IllegalArgumentException.class, () -> {
            RTPOptions.builder()
                    .minRadius(5000)
                    .maxRadius(1000)
                    .build();
        });
    }

    @Test
    void testRTPStatusEnum() {
        // Test terminal states
        assertTrue(RTPStatus.COMPLETED.isTerminal());
        assertTrue(RTPStatus.FAILED.isTerminal());
        assertTrue(RTPStatus.CANCELLED.isTerminal());
        
        // Test non-terminal states
        assertFalse(RTPStatus.QUEUED.isTerminal());
        assertFalse(RTPStatus.PROCESSING.isTerminal());
        assertFalse(RTPStatus.TELEPORTING.isTerminal());
        
        // Test active states
        assertTrue(RTPStatus.PROCESSING.isActive());
        assertTrue(RTPStatus.TELEPORTING.isActive());
        
        // Test non-active states
        assertFalse(RTPStatus.QUEUED.isActive());
        assertFalse(RTPStatus.COMPLETED.isActive());
        assertFalse(RTPStatus.FAILED.isActive());
        assertFalse(RTPStatus.CANCELLED.isActive());
    }

    @Test
    void testRTPResultCreation() {
        // Test successful result
        RTPResult success = RTPResult.success(new net.minecraft.core.BlockPos(100, 64, 200), 5, 1500);
        assertTrue(success.isSuccess());
        assertTrue(success.getLocation().isPresent());
        assertEquals(5, success.getAttemptsUsed());
        assertEquals(1500, success.getProcessingTimeMs());
        assertFalse(success.getFailureReason().isPresent());

        // Test failed result
        RTPResult failure = RTPResult.failure("No safe location found", 50, 30000);
        assertFalse(failure.isSuccess());
        assertFalse(failure.getLocation().isPresent());
        assertEquals(50, failure.getAttemptsUsed());
        assertEquals(30000, failure.getProcessingTimeMs());
        assertTrue(failure.getFailureReason().isPresent());
        assertEquals("No safe location found", failure.getFailureReason().get());
    }

    @Test
    void testRTPMetricsCreation() {
        long requestId = 12345L;
        RTPMetrics metrics = RTPMetrics.builder(requestId)
                .startTime(1000)
                .endTime(3000)
                .chunkLoadTime(500)
                .locationSearchTime(1200)
                .safetyValidationTime(300)
                .chunksLoaded(3)
                .locationsChecked(15)
                .successful(true)
                .mainThreadTime(0)
                .memoryUsed(1024)
                .build();

        assertEquals(requestId, metrics.getRequestId());
        assertEquals(2000, metrics.getTotalTime()); // endTime - startTime
        assertEquals(500, metrics.getChunkLoadTime());
        assertEquals(1200, metrics.getLocationSearchTime());
        assertEquals(300, metrics.getSafetyValidationTime());
        assertEquals(3, metrics.getChunksLoaded());
        assertEquals(15, metrics.getLocationsChecked());
        assertTrue(metrics.isSuccessful());
        assertEquals(0, metrics.getMainThreadTime());
        assertEquals(1024, metrics.getMemoryUsed());
        
        // Test performance thresholds
        assertTrue(metrics.meetsPerformanceThresholds());
        
        // Test efficiency score
        double efficiency = metrics.getEfficiencyScore();
        assertTrue(efficiency >= 0.0 && efficiency <= 1.0);
    }

    @Test
    void testCancellationWithoutActiveRequest() {
        UUID randomPlayerId = UUID.randomUUID();
        assertFalse(manager.hasActiveRequest(randomPlayerId));
        assertFalse(manager.cancelRTPRequest(randomPlayerId));
        assertFalse(manager.getRTPStatus(randomPlayerId).isPresent());
        assertFalse(manager.getRTPRequest(randomPlayerId).isPresent());
    }

    @Test
    void testPerformanceStatsInitialState() {
        RTPRequestManager.RTPPerformanceStats stats = manager.getPerformanceStats();
        
        assertEquals(0, stats.getTotalRequests());
        assertEquals(0, stats.getSuccessfulRequests());
        assertEquals(0, stats.getFailedRequests());
        assertEquals(0.0, stats.getSuccessRate(), 0.001);
        assertEquals(0.0, stats.getAverageProcessingTimeMs(), 0.001);
        assertEquals(0.0, stats.getAverageChunkLoadTimeMs(), 0.001);
        assertEquals(0, stats.getCurrentConcurrency());
        assertEquals(0, stats.getMaxConcurrency());
        assertEquals(0, stats.getMainThreadImpactMs());
        assertTrue(stats.meetsPerformanceThresholds()); // Should meet thresholds with no activity
    }

    @Test
    void testShutdownBehavior() throws Exception {
        // Test that shutdown completes successfully
        CompletableFuture<Void> shutdownFuture = manager.shutdown(5000);
        assertNotNull(shutdownFuture);
        
        // Should complete within reasonable time
        shutdownFuture.get(10, TimeUnit.SECONDS);
        
        // After shutdown, new requests should be rejected
        // Note: This test would require a mock ServerPlayer to fully test
        // For now, we just verify the shutdown future completes
    }
}