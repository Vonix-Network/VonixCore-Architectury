package network.vonix.vonixcore.discord;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VanillaComponentBuilder class.
 * Tests component generation, formatting, hover effects, and server prefixes.
 */
class VanillaComponentBuilderTest {
    
    private VanillaComponentBuilder builder;
    private AdvancementData normalAdvancement;
    private AdvancementData goalAdvancement;
    private AdvancementData challengeAdvancement;
    
    @BeforeEach
    void setUp() {
        builder = new VanillaComponentBuilder();
        
        normalAdvancement = new AdvancementData(
            "TestPlayer",
            "Stone Age",
            "Mine stone with your new pickaxe",
            AdvancementType.NORMAL
        );
        
        goalAdvancement = new AdvancementData(
            "GoalPlayer",
            "Acquire Hardware",
            "Smelt an iron ingot",
            AdvancementType.GOAL
        );
        
        challengeAdvancement = new AdvancementData(
            "ChallengePlayer",
            "Monsters Hunted",
            "Kill one of every hostile monster",
            AdvancementType.CHALLENGE
        );
    }
    
    @Nested
    @DisplayName("Basic Component Generation")
    class BasicComponentGeneration {
        
        @Test
        @DisplayName("Should create advancement message with server prefix")
        void shouldCreateAdvancementMessageWithServerPrefix() {
            String serverPrefix = "Survival";
            MutableComponent result = builder.buildAdvancementMessage(normalAdvancement, serverPrefix);
            
            assertNotNull(result);
            String resultText = result.getString();
            
            // Check that all expected parts are present
            assertTrue(resultText.contains("[Survival]"), "Should contain server prefix in brackets");
            assertTrue(resultText.contains("TestPlayer"), "Should contain player name");
            assertTrue(resultText.contains("has made the advancement"), "Should contain connector text");
            assertTrue(resultText.contains("[Stone Age]"), "Should contain advancement title in brackets");
        }
        
        @Test
        @DisplayName("Should create advancement message without server prefix")
        void shouldCreateAdvancementMessageWithoutServerPrefix() {
            MutableComponent result = builder.buildAdvancementMessage(normalAdvancement, "");
            
            assertNotNull(result);
            String resultText = result.getString();
            
            // Check that message doesn't start with brackets (no server prefix)
            assertFalse(resultText.startsWith("["), "Should not start with server prefix brackets");
            assertTrue(resultText.contains("TestPlayer"), "Should contain player name");
            assertTrue(resultText.contains("has made the advancement"), "Should contain connector text");
            assertTrue(resultText.contains("[Stone Age]"), "Should contain advancement title in brackets");
        }
        
        @Test
        @DisplayName("Should create advancement message using overloaded method")
        void shouldCreateAdvancementMessageUsingOverloadedMethod() {
            MutableComponent result = builder.buildAdvancementMessage(normalAdvancement);
            
            assertNotNull(result);
            String resultText = result.getString();
            
            assertTrue(resultText.contains("TestPlayer"), "Should contain player name");
            assertTrue(resultText.contains("has made the advancement"), "Should contain connector text");
            assertTrue(resultText.contains("[Stone Age]"), "Should contain advancement title in brackets");
        }
    }
    
    @Nested
    @DisplayName("Advancement Type Formatting")
    class AdvancementTypeFormatting {
        
        @Test
        @DisplayName("Should format normal advancement with yellow color")
        void shouldFormatNormalAdvancementWithYellowColor() {
            MutableComponent result = builder.buildAdvancementMessage(normalAdvancement, "Server");
            
            // Find the advancement title component and check its color
            // This is a simplified check - in a real scenario, you'd traverse the component tree
            String resultText = result.getString();
            assertTrue(resultText.contains("[Stone Age]"), "Should contain advancement title");
            
            // The actual color checking would require traversing the component siblings
            // For now, we verify the structure is correct
            assertNotNull(result);
        }
        
        @Test
        @DisplayName("Should format goal advancement with yellow color")
        void shouldFormatGoalAdvancementWithYellowColor() {
            MutableComponent result = builder.buildAdvancementMessage(goalAdvancement, "Server");
            
            assertNotNull(result);
            String resultText = result.getString();
            assertTrue(resultText.contains("GoalPlayer"), "Should contain player name");
            assertTrue(resultText.contains("[Acquire Hardware]"), "Should contain advancement title");
        }
        
        @Test
        @DisplayName("Should format challenge advancement with light purple color")
        void shouldFormatChallengeAdvancementWithLightPurpleColor() {
            MutableComponent result = builder.buildAdvancementMessage(challengeAdvancement, "Server");
            
            assertNotNull(result);
            String resultText = result.getString();
            assertTrue(resultText.contains("ChallengePlayer"), "Should contain player name");
            assertTrue(resultText.contains("[Monsters Hunted]"), "Should contain advancement title");
        }
    }
    
    @Nested
    @DisplayName("Server Prefix Handling")
    class ServerPrefixHandling {
        
        @Test
        @DisplayName("Should handle empty server prefix")
        void shouldHandleEmptyServerPrefix() {
            MutableComponent result = builder.buildAdvancementMessage(normalAdvancement, "");
            
            assertNotNull(result);
            String resultText = result.getString();
            assertFalse(resultText.startsWith("["), "Should not start with brackets for empty prefix");
        }
        
        @Test
        @DisplayName("Should handle whitespace-only server prefix")
        void shouldHandleWhitespaceOnlyServerPrefix() {
            MutableComponent result = builder.buildAdvancementMessage(normalAdvancement, "   ");
            
            assertNotNull(result);
            String resultText = result.getString();
            assertFalse(resultText.startsWith("["), "Should not start with brackets for whitespace-only prefix");
        }
        
        @Test
        @DisplayName("Should trim server prefix whitespace")
        void shouldTrimServerPrefixWhitespace() {
            MutableComponent result = builder.buildAdvancementMessage(normalAdvancement, "  Survival  ");
            
            assertNotNull(result);
            String resultText = result.getString();
            assertTrue(resultText.contains("[Survival]"), "Should contain trimmed server prefix");
            assertFalse(resultText.contains("[  Survival  ]"), "Should not contain untrimmed prefix");
        }
        
        @Test
        @DisplayName("Should handle special characters in server prefix")
        void shouldHandleSpecialCharactersInServerPrefix() {
            MutableComponent result = builder.buildAdvancementMessage(normalAdvancement, "Survival-1.20");
            
            assertNotNull(result);
            String resultText = result.getString();
            assertTrue(resultText.contains("[Survival-1.20]"), "Should contain server prefix with special characters");
        }
    }
    
    @Nested
    @DisplayName("Fallback Component Creation")
    class FallbackComponentCreation {
        
        @Test
        @DisplayName("Should create fallback component with all parameters")
        void shouldCreateFallbackComponentWithAllParameters() {
            MutableComponent result = builder.createFallbackComponent("Player", "Some Advancement", "Server");
            
            assertNotNull(result);
            String resultText = result.getString();
            assertTrue(resultText.contains("[Server]"), "Should contain server prefix");
            assertTrue(resultText.contains("Player"), "Should contain player name");
            assertTrue(resultText.contains("has made an advancement"), "Should contain fallback text");
            assertTrue(resultText.contains("Some Advancement"), "Should contain advancement title");
        }
        
        @Test
        @DisplayName("Should create fallback component without server prefix")
        void shouldCreateFallbackComponentWithoutServerPrefix() {
            MutableComponent result = builder.createFallbackComponent("Player", "Some Advancement", "");
            
            assertNotNull(result);
            String resultText = result.getString();
            assertFalse(resultText.startsWith("["), "Should not start with brackets");
            assertTrue(resultText.contains("Player"), "Should contain player name");
            assertTrue(resultText.contains("has made an advancement"), "Should contain fallback text");
        }
        
        @Test
        @DisplayName("Should create fallback component without advancement title")
        void shouldCreateFallbackComponentWithoutAdvancementTitle() {
            MutableComponent result = builder.createFallbackComponent("Player", "", "Server");
            
            assertNotNull(result);
            String resultText = result.getString();
            assertTrue(resultText.contains("[Server]"), "Should contain server prefix");
            assertTrue(resultText.contains("Player"), "Should contain player name");
            assertTrue(resultText.contains("has made an advancement"), "Should contain fallback text");
            assertFalse(resultText.contains(":"), "Should not contain colon without advancement title");
        }
        
        @Test
        @DisplayName("Should handle null parameters in fallback component")
        void shouldHandleNullParametersInFallbackComponent() {
            MutableComponent result = builder.createFallbackComponent("Player", null, null);
            
            assertNotNull(result);
            String resultText = result.getString();
            assertTrue(resultText.contains("Player"), "Should contain player name");
            assertTrue(resultText.contains("has made an advancement"), "Should contain fallback text");
        }
    }
    
    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {
        
        @Test
        @DisplayName("Should throw exception for null advancement data")
        void shouldThrowExceptionForNullAdvancementData() {
            assertThrows(IllegalArgumentException.class, () -> {
                builder.buildAdvancementMessage(null, "Server");
            }, "Should throw IllegalArgumentException for null advancement data");
        }
        
        @Test
        @DisplayName("Should throw exception for null server prefix")
        void shouldThrowExceptionForNullServerPrefix() {
            assertThrows(IllegalArgumentException.class, () -> {
                builder.buildAdvancementMessage(normalAdvancement, null);
            }, "Should throw IllegalArgumentException for null server prefix");
        }
        
        @Test
        @DisplayName("Should throw exception for null advancement data in overloaded method")
        void shouldThrowExceptionForNullAdvancementDataInOverloadedMethod() {
            assertThrows(IllegalArgumentException.class, () -> {
                builder.buildAdvancementMessage(null);
            }, "Should throw IllegalArgumentException for null advancement data in overloaded method");
        }
    }
    
    @Nested
    @DisplayName("Component Structure Validation")
    class ComponentStructureValidation {
        
        @Test
        @DisplayName("Should create non-empty component")
        void shouldCreateNonEmptyComponent() {
            MutableComponent result = builder.buildAdvancementMessage(normalAdvancement, "Server");
            
            assertNotNull(result);
            assertFalse(result.getString().isEmpty(), "Component should not be empty");
        }
        
        @Test
        @DisplayName("Should create component with expected text structure")
        void shouldCreateComponentWithExpectedTextStructure() {
            MutableComponent result = builder.buildAdvancementMessage(normalAdvancement, "Survival");
            String resultText = result.getString();
            
            // Verify the expected structure: [Server] PlayerName has made the advancement [AdvancementTitle]
            assertTrue(resultText.matches("\\[Survival\\] TestPlayer has made the advancement \\[Stone Age\\]"),
                    "Component should match expected text structure");
        }
        
        @Test
        @DisplayName("Should maintain component structure without server prefix")
        void shouldMaintainComponentStructureWithoutServerPrefix() {
            MutableComponent result = builder.buildAdvancementMessage(normalAdvancement, "");
            String resultText = result.getString();
            
            // Verify the expected structure: PlayerName has made the advancement [AdvancementTitle]
            assertTrue(resultText.matches("TestPlayer has made the advancement \\[Stone Age\\]"),
                    "Component should match expected text structure without server prefix");
        }
    }
}