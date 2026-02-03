package network.vonix.vonixcore.discord;

import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for enhanced fallback behavior improvements.
 * Verifies that the enhanced fallback mechanisms provide better error recovery
 * and system stability under various error conditions.
 */
@DisplayName("Enhanced Fallback Behavior")
class EnhancedFallbackTest {

    private VanillaComponentBuilder builder;
    
    @BeforeEach
    void setUp() {
        builder = new VanillaComponentBuilder();
    }
    
    @Test
    @DisplayName("Should handle null player name with 'Someone' fallback")
    void shouldHandleNullPlayerNameWithSomeoneFallback() {
        // Act
        MutableComponent component = builder.createFallbackComponent(null, "Stone Age", "[TestServer]");
        
        // Assert
        assertNotNull(component, "Should create component");
        String text = component.getString();
        assertTrue(text.contains("Someone"), "Should use 'Someone' as fallback for null player name");
        assertTrue(text.contains("Stone Age"), "Should contain advancement title");
        assertTrue(text.contains("[TestServer]"), "Should contain server prefix");
    }
    
    @Test
    @DisplayName("Should handle empty player name with 'Someone' fallback")
    void shouldHandleEmptyPlayerNameWithSomeoneFallback() {
        // Act
        MutableComponent component = builder.createFallbackComponent("   ", "Stone Age", "[TestServer]");
        
        // Assert
        assertNotNull(component, "Should create component");
        String text = component.getString();
        assertTrue(text.contains("Someone"), "Should use 'Someone' as fallback for empty player name");
        assertTrue(text.contains("Stone Age"), "Should contain advancement title");
    }
    
    @Test
    @DisplayName("Should handle whitespace-only player name gracefully")
    void shouldHandleWhitespaceOnlyPlayerNameGracefully() {
        // Act
        MutableComponent component = builder.createFallbackComponent("\t\n  \r", "Stone Age", "[TestServer]");
        
        // Assert
        assertNotNull(component, "Should create component");
        String text = component.getString();
        assertTrue(text.contains("Someone"), "Should use 'Someone' as fallback for whitespace-only player name");
    }
    
    @Test
    @DisplayName("Should trim player name properly")
    void shouldTrimPlayerNameProperly() {
        // Act
        MutableComponent component = builder.createFallbackComponent("  TestPlayer  ", "Stone Age", "[TestServer]");
        
        // Assert
        assertNotNull(component, "Should create component");
        String text = component.getString();
        assertTrue(text.contains("TestPlayer"), "Should contain trimmed player name");
        assertFalse(text.contains("  TestPlayer  "), "Should not contain untrimmed player name");
    }
    
    @Test
    @DisplayName("Should trim advancement title properly")
    void shouldTrimAdvancementTitleProperly() {
        // Act
        MutableComponent component = builder.createFallbackComponent("TestPlayer", "  Stone Age  ", "[TestServer]");
        
        // Assert
        assertNotNull(component, "Should create component");
        String text = component.getString();
        assertTrue(text.contains("Stone Age"), "Should contain trimmed advancement title");
        assertFalse(text.contains("  Stone Age  "), "Should not contain untrimmed advancement title");
    }
    
    @Test
    @DisplayName("Should handle null advancement title gracefully")
    void shouldHandleNullAdvancementTitleGracefully() {
        // Act
        MutableComponent component = builder.createFallbackComponent("TestPlayer", null, "[TestServer]");
        
        // Assert
        assertNotNull(component, "Should create component");
        String text = component.getString();
        assertTrue(text.contains("TestPlayer"), "Should contain player name");
        assertTrue(text.contains("advancement"), "Should indicate advancement occurred");
        assertFalse(text.contains(":"), "Should not contain colon when no title");
    }
    
    @Test
    @DisplayName("Should handle whitespace-only advancement title gracefully")
    void shouldHandleWhitespaceOnlyAdvancementTitleGracefully() {
        // Act
        MutableComponent component = builder.createFallbackComponent("TestPlayer", "\t\n  \r", "[TestServer]");
        
        // Assert
        assertNotNull(component, "Should create component");
        String text = component.getString();
        assertTrue(text.contains("TestPlayer"), "Should contain player name");
        assertFalse(text.contains(":"), "Should not contain colon when title is whitespace-only");
    }
    
    @Test
    @DisplayName("Should handle null server prefix gracefully")
    void shouldHandleNullServerPrefixGracefully() {
        // Act
        MutableComponent component = builder.createFallbackComponent("TestPlayer", "Stone Age", null);
        
        // Assert
        assertNotNull(component, "Should create component");
        String text = component.getString();
        assertTrue(text.contains("TestPlayer"), "Should contain player name");
        assertTrue(text.contains("Stone Age"), "Should contain advancement title");
        assertFalse(text.startsWith("["), "Should not start with bracket when prefix is null");
    }
    
    @Test
    @DisplayName("Should handle whitespace-only server prefix gracefully")
    void shouldHandleWhitespaceOnlyServerPrefixGracefully() {
        // Act
        MutableComponent component = builder.createFallbackComponent("TestPlayer", "Stone Age", "\t\n  \r");
        
        // Assert
        assertNotNull(component, "Should create component");
        String text = component.getString();
        assertTrue(text.contains("TestPlayer"), "Should contain player name");
        assertTrue(text.contains("Stone Age"), "Should contain advancement title");
        assertFalse(text.startsWith("["), "Should not start with bracket when prefix is whitespace-only");
    }
    
    @Test
    @DisplayName("Should create ultimate fallback when all parameters are problematic")
    void shouldCreateUltimateFallbackWhenAllParametersAreProblematic() {
        // Act
        MutableComponent component = builder.createFallbackComponent(null, null, null);
        
        // Assert
        assertNotNull(component, "Should create component even with all null parameters");
        String text = component.getString();
        assertTrue(text.contains("Someone"), "Should use 'Someone' as ultimate player fallback");
        assertTrue(text.contains("advancement"), "Should indicate advancement occurred");
    }
    
    @Test
    @DisplayName("Should maintain component structure with valid inputs")
    void shouldMaintainComponentStructureWithValidInputs() {
        // Act
        MutableComponent component = builder.createFallbackComponent("TestPlayer", "Stone Age", "[TestServer]");
        
        // Assert
        assertNotNull(component, "Should create component");
        String text = component.getString();
        
        // The actual structure is: [[TestServer]] PlayerName has made an advancement: AdvancementTitle
        // Because createServerPrefixComponent adds brackets around the prefix
        assertTrue(text.contains("[[TestServer]]"), "Should contain server prefix with double brackets");
        assertTrue(text.contains("TestPlayer has made an advancement"), "Should contain standard advancement message");
        assertTrue(text.contains(": Stone Age"), "Should contain advancement title with colon");
    }
    
    @Test
    @DisplayName("Should be resilient to component creation failures")
    void shouldBeResilientToComponentCreationFailures() {
        // This test verifies that the fallback method doesn't throw exceptions
        // even under extreme conditions
        
        // Test with various problematic inputs
        String[] problematicInputs = {
            null, "", "   ", "\t\n\r", 
            "Very".repeat(1000), // Very long string
            "\u0000\u0001\u0002", // Control characters
            "🎉🏆⚡", // Unicode emojis
        };
        
        for (String input : problematicInputs) {
            assertDoesNotThrow(() -> {
                MutableComponent component = builder.createFallbackComponent(input, input, input);
                assertNotNull(component, "Should always create a component");
            }, "Should not throw exception for input: " + (input != null ? input.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r") : "null"));
        }
    }
}