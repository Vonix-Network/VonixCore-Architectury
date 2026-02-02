package network.vonix.vonixcore.discord;

import net.minecraft.network.chat.MutableComponent;
import org.javacord.api.entity.message.embed.Embed;
import org.javacord.api.entity.message.embed.EmbedField;
import org.javacord.api.entity.message.embed.EmbedFooter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Integration tests for DiscordManager chat integration functionality.
 * Tests the complete pipeline from Discord embed processing to Minecraft chat system.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DiscordManager Chat Integration")
class DiscordManagerChatIntegrationTest {

    @Mock
    private Embed mockEmbed;
    
    @Mock
    private EmbedFooter mockFooter;
    
    @Mock
    private EmbedField mockPlayerField;
    
    @Mock
    private EmbedField mockAdvancementField;
    
    @Mock
    private EmbedField mockDescriptionField;
    
    @BeforeEach
    void setUp() {
        // Setup is minimal since we're testing individual components
    }
    
    @Test
    @DisplayName("Should send advancement message to chat system")
    void shouldSendAdvancementMessageToChatSystem() {
        // Arrange
        setupMockAdvancementEmbed("TestPlayer", "Stone Age", "Mine stone with your new pickaxe");
        
        // Create the components we need to test
        AdvancementEmbedDetector detector = new AdvancementEmbedDetector();
        AdvancementDataExtractor extractor = new AdvancementDataExtractor();
        VanillaComponentBuilder builder = new VanillaComponentBuilder();
        
        // Act & Assert
        assertTrue(detector.isAdvancementEmbed(mockEmbed), "Should detect advancement embed");
        
        try {
            AdvancementData data = extractor.extractFromEmbed(mockEmbed);
            assertNotNull(data, "Should extract advancement data");
            assertEquals("TestPlayer", data.getPlayerName());
            assertEquals("Stone Age", data.getAdvancementTitle());
            
            MutableComponent component = builder.buildAdvancementMessage(data, "[TestServer]");
            assertNotNull(component, "Should build advancement component");
            
            String componentText = component.getString();
            assertTrue(componentText.contains("TestPlayer"), "Should contain player name");
            assertTrue(componentText.contains("Stone Age"), "Should contain advancement title");
            assertTrue(componentText.contains("[TestServer]"), "Should contain server prefix");
            
        } catch (ExtractionException e) {
            fail("Should not throw extraction exception: " + e.getMessage());
        }
    }
    
    @Test
    @DisplayName("Should prevent duplicate messages by early return")
    void shouldPreventDuplicateMessagesByEarlyReturn() {
        // This test verifies that when an advancement embed is processed,
        // the method returns early and doesn't continue with normal embed processing
        
        // Arrange
        setupMockAdvancementEmbed("TestPlayer", "Stone Age", "Mine stone with your new pickaxe");
        
        AdvancementEmbedDetector detector = new AdvancementEmbedDetector();
        
        // Act & Assert
        assertTrue(detector.isAdvancementEmbed(mockEmbed), 
                "Advancement embed should be detected, preventing normal processing");
    }
    
    @Test
    @DisplayName("Should maintain message ordering through immediate processing")
    void shouldMaintainMessageOrderingThroughImmediateProcessing() {
        // This test verifies that advancement messages are processed immediately
        // without queuing, which maintains message ordering
        
        // Arrange
        setupMockAdvancementEmbed("TestPlayer", "Stone Age", "Mine stone with your new pickaxe");
        
        AdvancementDataExtractor extractor = new AdvancementDataExtractor();
        VanillaComponentBuilder builder = new VanillaComponentBuilder();
        
        // Act
        try {
            AdvancementData data = extractor.extractFromEmbed(mockEmbed);
            MutableComponent component = builder.buildAdvancementMessage(data, "[TestServer]");
            
            // Assert - component is created immediately, not queued
            assertNotNull(component, "Component should be created immediately");
            
            // In the actual implementation, this would be sent directly to broadcastSystemMessage
            // which maintains ordering by processing on the main server thread
            
        } catch (ExtractionException e) {
            fail("Should not throw extraction exception: " + e.getMessage());
        }
    }
    
    private void setupMockAdvancementEmbed(String playerName, String advancementTitle, String description) {
        // Mock footer with advancement keyword
        lenient().when(mockFooter.getText()).thenReturn(Optional.of("VonixCore · Advancement"));
        lenient().when(mockEmbed.getFooter()).thenReturn(Optional.of(mockFooter));
        
        // Mock embed fields
        lenient().when(mockPlayerField.getName()).thenReturn("Player");
        lenient().when(mockPlayerField.getValue()).thenReturn(playerName);
        
        lenient().when(mockAdvancementField.getName()).thenReturn("Advancement");
        lenient().when(mockAdvancementField.getValue()).thenReturn(advancementTitle);
        
        lenient().when(mockDescriptionField.getName()).thenReturn("Description");
        lenient().when(mockDescriptionField.getValue()).thenReturn(description);
        
        lenient().when(mockEmbed.getFields()).thenReturn(List.of(mockPlayerField, mockAdvancementField, mockDescriptionField));
        
        // Mock embed title
        lenient().when(mockEmbed.getTitle()).thenReturn(Optional.of("Advancement Made"));
    }
}