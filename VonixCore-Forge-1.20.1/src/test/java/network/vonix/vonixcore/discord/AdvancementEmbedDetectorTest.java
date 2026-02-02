package network.vonix.vonixcore.discord;

import org.javacord.api.entity.message.embed.Embed;
import org.javacord.api.entity.message.embed.EmbedFooter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AdvancementEmbedDetector class.
 * Tests footer-based detection and advancement type determination.
 */
class AdvancementEmbedDetectorTest {
    
    private AdvancementEmbedDetector detector;
    
    @Mock
    private Embed mockEmbed;
    
    @Mock
    private EmbedFooter mockFooter;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        detector = new AdvancementEmbedDetector();
    }
    
    @Test
    void testIsAdvancementEmbed_WithAdvancementFooter_ReturnsTrue() {
        // Arrange
        when(mockEmbed.getFooter()).thenReturn(Optional.of(mockFooter));
        when(mockFooter.getText()).thenReturn(Optional.of("VonixCore · Advancement"));
        
        // Act
        boolean result = detector.isAdvancementEmbed(mockEmbed);
        
        // Assert
        assertTrue(result, "Should detect advancement embed with 'advancement' keyword in footer");
    }
    
    @Test
    void testIsAdvancementEmbed_WithGoalFooter_ReturnsTrue() {
        // Arrange
        when(mockEmbed.getFooter()).thenReturn(Optional.of(mockFooter));
        when(mockFooter.getText()).thenReturn(Optional.of("VonixCore · Goal"));
        
        // Act
        boolean result = detector.isAdvancementEmbed(mockEmbed);
        
        // Assert
        assertTrue(result, "Should detect advancement embed with 'goal' keyword in footer");
    }
    
    @Test
    void testIsAdvancementEmbed_WithChallengeFooter_ReturnsTrue() {
        // Arrange
        when(mockEmbed.getFooter()).thenReturn(Optional.of(mockFooter));
        when(mockFooter.getText()).thenReturn(Optional.of("VonixCore · Challenge"));
        
        // Act
        boolean result = detector.isAdvancementEmbed(mockEmbed);
        
        // Assert
        assertTrue(result, "Should detect advancement embed with 'challenge' keyword in footer");
    }
    
    @Test
    void testIsAdvancementEmbed_WithTaskFooter_ReturnsTrue() {
        // Arrange
        when(mockEmbed.getFooter()).thenReturn(Optional.of(mockFooter));
        when(mockFooter.getText()).thenReturn(Optional.of("VonixCore · Task"));
        
        // Act
        boolean result = detector.isAdvancementEmbed(mockEmbed);
        
        // Assert
        assertTrue(result, "Should detect advancement embed with 'task' keyword in footer");
    }
    
    @Test
    void testIsAdvancementEmbed_WithCaseInsensitiveKeyword_ReturnsTrue() {
        // Arrange
        when(mockEmbed.getFooter()).thenReturn(Optional.of(mockFooter));
        when(mockFooter.getText()).thenReturn(Optional.of("VonixCore · ADVANCEMENT"));
        
        // Act
        boolean result = detector.isAdvancementEmbed(mockEmbed);
        
        // Assert
        assertTrue(result, "Should detect advancement embed with case-insensitive keyword matching");
    }
    
    @Test
    void testIsAdvancementEmbed_WithNonAdvancementFooter_ReturnsFalse() {
        // Arrange
        when(mockEmbed.getFooter()).thenReturn(Optional.of(mockFooter));
        when(mockFooter.getText()).thenReturn(Optional.of("VonixCore · Join"));
        
        // Act
        boolean result = detector.isAdvancementEmbed(mockEmbed);
        
        // Assert
        assertFalse(result, "Should not detect non-advancement embed");
    }
    
    @Test
    void testIsAdvancementEmbed_WithNoFooter_ReturnsFalse() {
        // Arrange
        when(mockEmbed.getFooter()).thenReturn(Optional.empty());
        
        // Act
        boolean result = detector.isAdvancementEmbed(mockEmbed);
        
        // Assert
        assertFalse(result, "Should not detect advancement embed without footer");
    }
    
    @Test
    void testIsAdvancementEmbed_WithNullEmbed_ReturnsFalse() {
        // Act
        boolean result = detector.isAdvancementEmbed(null);
        
        // Assert
        assertFalse(result, "Should handle null embed gracefully");
    }
    
    @Test
    void testGetAdvancementType_WithChallengeTitle_ReturnsChallenge() {
        // Arrange
        when(mockEmbed.getTitle()).thenReturn(Optional.of("Challenge Complete"));
        when(mockEmbed.getFooter()).thenReturn(Optional.empty());
        
        // Act
        AdvancementType result = detector.getAdvancementType(mockEmbed);
        
        // Assert
        assertEquals(AdvancementType.CHALLENGE, result, "Should detect CHALLENGE type from title");
    }
    
    @Test
    void testGetAdvancementType_WithGoalTitle_ReturnsGoal() {
        // Arrange
        when(mockEmbed.getTitle()).thenReturn(Optional.of("Goal Reached"));
        when(mockEmbed.getFooter()).thenReturn(Optional.empty());
        
        // Act
        AdvancementType result = detector.getAdvancementType(mockEmbed);
        
        // Assert
        assertEquals(AdvancementType.GOAL, result, "Should detect GOAL type from title");
    }
    
    @Test
    void testGetAdvancementType_WithChallengeFooter_ReturnsChallenge() {
        // Arrange
        when(mockEmbed.getTitle()).thenReturn(Optional.empty());
        when(mockEmbed.getFooter()).thenReturn(Optional.of(mockFooter));
        when(mockFooter.getText()).thenReturn(Optional.of("VonixCore · Challenge"));
        
        // Act
        AdvancementType result = detector.getAdvancementType(mockEmbed);
        
        // Assert
        assertEquals(AdvancementType.CHALLENGE, result, "Should detect CHALLENGE type from footer");
    }
    
    @Test
    void testGetAdvancementType_WithGoalFooter_ReturnsGoal() {
        // Arrange
        when(mockEmbed.getTitle()).thenReturn(Optional.empty());
        when(mockEmbed.getFooter()).thenReturn(Optional.of(mockFooter));
        when(mockFooter.getText()).thenReturn(Optional.of("VonixCore · Goal"));
        
        // Act
        AdvancementType result = detector.getAdvancementType(mockEmbed);
        
        // Assert
        assertEquals(AdvancementType.GOAL, result, "Should detect GOAL type from footer");
    }
    
    @Test
    void testGetAdvancementType_WithNormalAdvancement_ReturnsNormal() {
        // Arrange
        when(mockEmbed.getTitle()).thenReturn(Optional.of("Advancement Made"));
        when(mockEmbed.getFooter()).thenReturn(Optional.of(mockFooter));
        when(mockFooter.getText()).thenReturn(Optional.of("VonixCore · Advancement"));
        
        // Act
        AdvancementType result = detector.getAdvancementType(mockEmbed);
        
        // Assert
        assertEquals(AdvancementType.NORMAL, result, "Should default to NORMAL type for regular advancements");
    }
    
    @Test
    void testGetAdvancementType_WithNullEmbed_ReturnsNormal() {
        // Act
        AdvancementType result = detector.getAdvancementType(null);
        
        // Assert
        assertEquals(AdvancementType.NORMAL, result, "Should handle null embed gracefully and return NORMAL");
    }
    
    @Test
    void testGetAdvancementType_TitleTakesPrecedenceOverFooter() {
        // Arrange - title says challenge, footer says goal
        when(mockEmbed.getTitle()).thenReturn(Optional.of("Challenge Complete"));
        when(mockEmbed.getFooter()).thenReturn(Optional.of(mockFooter));
        when(mockFooter.getText()).thenReturn(Optional.of("VonixCore · Goal"));
        
        // Act
        AdvancementType result = detector.getAdvancementType(mockEmbed);
        
        // Assert
        assertEquals(AdvancementType.CHALLENGE, result, "Title should take precedence over footer for type detection");
    }
}