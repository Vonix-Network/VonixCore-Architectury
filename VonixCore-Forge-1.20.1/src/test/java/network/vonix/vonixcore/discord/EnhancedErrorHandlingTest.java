package network.vonix.vonixcore.discord;

import org.javacord.api.entity.message.embed.Embed;
import org.javacord.api.entity.message.embed.EmbedField;
import org.javacord.api.entity.message.embed.EmbedFooter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for enhanced error handling throughout the advancement processing pipeline.
 * Verifies that error handling improvements provide better resilience and debugging information
 * while maintaining system stability.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Enhanced Error Handling")
class EnhancedErrorHandlingTest {

    @Mock
    private Embed mockEmbed;
    
    @Mock
    private EmbedFooter mockFooter;
    
    @Mock
    private EmbedField mockField;

    private AdvancementEmbedDetector detector;
    private AdvancementDataExtractor extractor;
    private VanillaComponentBuilder builder;

    @BeforeEach
    void setUp() {
        detector = new AdvancementEmbedDetector();
        extractor = new AdvancementDataExtractor();
        builder = new VanillaComponentBuilder();
    }

    @Test
    @DisplayName("Should handle embed detection errors gracefully")
    void shouldHandleEmbedDetectionErrorsGracefully() {
        // Arrange - Create an embed that throws an exception when accessing footer
        when(mockEmbed.getFooter()).thenThrow(new RuntimeException("Simulated API error"));

        // Act & Assert - Should not throw exception, should return false
        assertDoesNotThrow(() -> {
            boolean result = detector.isAdvancementEmbed(mockEmbed);
            assertFalse(result, "Should return false when error occurs during detection");
        });
    }

    @Test
    @DisplayName("Should handle advancement type determination errors gracefully")
    void shouldHandleAdvancementTypeDeterminationErrorsGracefully() {
        // Arrange - Create an embed that throws an exception when accessing title
        when(mockEmbed.getTitle()).thenThrow(new RuntimeException("Simulated API error"));
        when(mockEmbed.getFooter()).thenReturn(Optional.empty());

        // Act & Assert - Should not throw exception, should return NORMAL type
        assertDoesNotThrow(() -> {
            AdvancementType result = detector.getAdvancementType(mockEmbed);
            assertEquals(AdvancementType.NORMAL, result, "Should return NORMAL type when error occurs");
        });
    }

    @Test
    @DisplayName("Should handle component builder errors gracefully")
    void shouldHandleComponentBuilderErrorsGracefully() {
        // Test that component builder handles null inputs gracefully
        assertThrows(IllegalArgumentException.class, () -> {
            builder.buildAdvancementMessage(null, "TestServer");
        }, "Should throw IllegalArgumentException for null advancement data");

        assertThrows(IllegalArgumentException.class, () -> {
            AdvancementData validData = new AdvancementData("Player", "Title", "Description", AdvancementType.NORMAL);
            builder.buildAdvancementMessage(validData, null);
        }, "Should throw IllegalArgumentException for null server prefix");
    }

    @Test
    @DisplayName("Should create fallback components under extreme conditions")
    void shouldCreateFallbackComponentsUnderExtremeConditions() {
        // Test various edge cases for fallback component creation
        String[] testInputs = {
            null,
            "",
            "   ",
            "ValidInput",
            "Input\nWith\nNewlines",
            "Input\tWith\tTabs",
            "Very long input that exceeds normal length expectations and contains various characters !@#$%^&*()_+-=[]{}|;':\",./<>?",
            "\u0000\u0001\u0002", // Control characters
            "🎮🏆⚡", // Unicode emojis
        };

        for (String input : testInputs) {
            assertDoesNotThrow(() -> {
                var component = builder.createFallbackComponent(input, input, input);
                assertNotNull(component, "Should always create a component for input: " + 
                    (input != null ? input.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r") : "null"));
            }, "Should not throw exception for input: " + 
               (input != null ? input.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r") : "null"));
        }
    }

    @Test
    @DisplayName("Should maintain system stability under concurrent access")
    void shouldMaintainSystemStabilityUnderConcurrentAccess() {
        // Test that the components are thread-safe under concurrent access
        AdvancementData testData = new AdvancementData("Player", "Title", "Description", AdvancementType.NORMAL);
        
        // Run multiple threads concurrently
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            final int threadId = i; // Make variable effectively final
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    final int iterationId = j; // Make variable effectively final
                    assertDoesNotThrow(() -> {
                        var component = builder.buildAdvancementMessage(testData, "Server" + threadId + "_" + iterationId);
                        assertNotNull(component, "Component should not be null");
                    });
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        assertDoesNotThrow(() -> {
            for (Thread thread : threads) {
                thread.join(5000); // 5 second timeout
                assertFalse(thread.isAlive(), "Thread should have completed");
            }
        });
    }

    @Test
    @DisplayName("Should provide meaningful error context for debugging")
    void shouldProvideMeaningfulErrorContextForDebugging() {
        // Arrange - Create an embed with missing required fields
        when(mockEmbed.getFooter()).thenReturn(Optional.of(mockFooter));
        when(mockFooter.getText()).thenReturn(Optional.of("VonixCore · Advancement"));
        when(mockEmbed.getFields()).thenReturn(Arrays.asList()); // Empty fields
        when(mockEmbed.getTitle()).thenReturn(Optional.of("Test Title"));
        when(mockEmbed.getDescription()).thenReturn(Optional.of("Test Description"));

        // Act & Assert
        ExtractionException exception = assertThrows(ExtractionException.class, 
            () -> extractor.extractFromEmbed(mockEmbed));

        // Verify error message is meaningful
        assertNotNull(exception.getMessage(), "Should provide error message");
        assertTrue(exception.getMessage().length() > 0, "Error message should not be empty");
        assertTrue(exception.getMessage().contains("not found"), "Error message should indicate what was not found");
    }
}