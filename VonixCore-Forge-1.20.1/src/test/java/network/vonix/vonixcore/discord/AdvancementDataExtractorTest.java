package network.vonix.vonixcore.discord;

import org.javacord.api.entity.message.embed.Embed;
import org.javacord.api.entity.message.embed.EmbedField;
import org.javacord.api.entity.message.embed.EmbedFooter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AdvancementDataExtractor class.
 * Tests data extraction from Discord embeds with various field configurations.
 */
class AdvancementDataExtractorTest {
    
    private AdvancementDataExtractor extractor;
    
    @Mock
    private Embed mockEmbed;
    
    @Mock
    private EmbedFooter mockFooter;
    
    @Mock
    private EmbedField mockPlayerField;
    
    @Mock
    private EmbedField mockTitleField;
    
    @Mock
    private EmbedField mockDescField;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        extractor = new AdvancementDataExtractor();
    }
    
    @Test
    void testExtractFromEmbed_WithValidFields_ReturnsAdvancementData() throws ExtractionException {
        // Arrange
        setupValidAdvancementEmbed();
        
        when(mockPlayerField.getName()).thenReturn("Player");
        when(mockPlayerField.getValue()).thenReturn("TestPlayer");
        
        when(mockTitleField.getName()).thenReturn("Advancement");
        when(mockTitleField.getValue()).thenReturn("Stone Age");
        
        when(mockDescField.getName()).thenReturn("Description");
        when(mockDescField.getValue()).thenReturn("Mine stone with your new pickaxe");
        
        List<EmbedField> fields = Arrays.asList(mockPlayerField, mockTitleField, mockDescField);
        when(mockEmbed.getFields()).thenReturn(fields);
        
        // Act
        AdvancementData result = extractor.extractFromEmbed(mockEmbed);
        
        // Assert
        assertNotNull(result);
        assertEquals("TestPlayer", result.getPlayerName());
        assertEquals("Stone Age", result.getAdvancementTitle());
        assertEquals("Mine stone with your new pickaxe", result.getAdvancementDescription());
        assertEquals(AdvancementType.NORMAL, result.getType());
    }
    
    @Test
    void testExtractFromEmbed_WithChallengeType_ReturnsChallengeType() throws ExtractionException {
        // Arrange
        setupValidAdvancementEmbed();
        when(mockEmbed.getTitle()).thenReturn(Optional.of("Challenge Complete"));
        
        when(mockPlayerField.getName()).thenReturn("Player");
        when(mockPlayerField.getValue()).thenReturn("TestPlayer");
        
        when(mockTitleField.getName()).thenReturn("Title");
        when(mockTitleField.getValue()).thenReturn("Adventuring Time");
        
        when(mockDescField.getName()).thenReturn("Description");
        when(mockDescField.getValue()).thenReturn("Discover every biome");
        
        List<EmbedField> fields = Arrays.asList(mockPlayerField, mockTitleField, mockDescField);
        when(mockEmbed.getFields()).thenReturn(fields);
        
        // Act
        AdvancementData result = extractor.extractFromEmbed(mockEmbed);
        
        // Assert
        assertEquals(AdvancementType.CHALLENGE, result.getType());
    }
    
    @Test
    void testExtractFromEmbed_WithVariousFieldNames_ExtractsCorrectly() throws ExtractionException {
        // Arrange
        setupValidAdvancementEmbed();
        
        when(mockPlayerField.getName()).thenReturn("User Name");  // Different field name
        when(mockPlayerField.getValue()).thenReturn("TestPlayer");
        
        when(mockTitleField.getName()).thenReturn("Achievement");  // Different field name
        when(mockTitleField.getValue()).thenReturn("Stone Age");
        
        when(mockDescField.getName()).thenReturn("Details");  // Different field name
        when(mockDescField.getValue()).thenReturn("Mine stone with your new pickaxe");
        
        List<EmbedField> fields = Arrays.asList(mockPlayerField, mockTitleField, mockDescField);
        when(mockEmbed.getFields()).thenReturn(fields);
        
        // Act
        AdvancementData result = extractor.extractFromEmbed(mockEmbed);
        
        // Assert
        assertEquals("TestPlayer", result.getPlayerName());
        assertEquals("Stone Age", result.getAdvancementTitle());
        assertEquals("Mine stone with your new pickaxe", result.getAdvancementDescription());
    }
    
    @Test
    void testExtractFromEmbed_WithCaseInsensitiveFieldNames_ExtractsCorrectly() throws ExtractionException {
        // Arrange
        setupValidAdvancementEmbed();
        
        when(mockPlayerField.getName()).thenReturn("PLAYER");  // Uppercase
        when(mockPlayerField.getValue()).thenReturn("TestPlayer");
        
        when(mockTitleField.getName()).thenReturn("advancement");  // Lowercase
        when(mockTitleField.getValue()).thenReturn("Stone Age");
        
        when(mockDescField.getName()).thenReturn("Description");
        when(mockDescField.getValue()).thenReturn("Mine stone with your new pickaxe");
        
        List<EmbedField> fields = Arrays.asList(mockPlayerField, mockTitleField, mockDescField);
        when(mockEmbed.getFields()).thenReturn(fields);
        
        // Act
        AdvancementData result = extractor.extractFromEmbed(mockEmbed);
        
        // Assert
        assertEquals("TestPlayer", result.getPlayerName());
        assertEquals("Stone Age", result.getAdvancementTitle());
        assertEquals("Mine stone with your new pickaxe", result.getAdvancementDescription());
    }
    
    @Test
    void testExtractFromEmbed_WithEmbedTitleFallback_UsesEmbedTitle() throws ExtractionException {
        // Arrange
        setupValidAdvancementEmbed();
        when(mockEmbed.getTitle()).thenReturn(Optional.of("Stone Age"));  // Fallback title
        
        when(mockPlayerField.getName()).thenReturn("Player");
        when(mockPlayerField.getValue()).thenReturn("TestPlayer");
        
        when(mockDescField.getName()).thenReturn("Description");
        when(mockDescField.getValue()).thenReturn("Mine stone with your new pickaxe");
        
        List<EmbedField> fields = Arrays.asList(mockPlayerField, mockDescField);  // No title field
        when(mockEmbed.getFields()).thenReturn(fields);
        
        // Act
        AdvancementData result = extractor.extractFromEmbed(mockEmbed);
        
        // Assert
        assertEquals("Stone Age", result.getAdvancementTitle());
    }
    
    @Test
    void testExtractFromEmbed_WithEmbedDescriptionFallback_UsesEmbedDescription() throws ExtractionException {
        // Arrange
        setupValidAdvancementEmbed();
        when(mockEmbed.getDescription()).thenReturn(Optional.of("Mine stone with your new pickaxe"));  // Fallback description
        
        when(mockPlayerField.getName()).thenReturn("Player");
        when(mockPlayerField.getValue()).thenReturn("TestPlayer");
        
        when(mockTitleField.getName()).thenReturn("Advancement");
        when(mockTitleField.getValue()).thenReturn("Stone Age");
        
        List<EmbedField> fields = Arrays.asList(mockPlayerField, mockTitleField);  // No description field
        when(mockEmbed.getFields()).thenReturn(fields);
        
        // Act
        AdvancementData result = extractor.extractFromEmbed(mockEmbed);
        
        // Assert
        assertEquals("Mine stone with your new pickaxe", result.getAdvancementDescription());
    }
    
    @Test
    void testExtractFromEmbed_WithGenericTitle_IgnoresGenericTitle() throws ExtractionException {
        // Arrange
        setupValidAdvancementEmbed();
        when(mockEmbed.getTitle()).thenReturn(Optional.of("Advancement Made"));  // Generic title should be ignored
        
        when(mockPlayerField.getName()).thenReturn("Player");
        when(mockPlayerField.getValue()).thenReturn("TestPlayer");
        
        when(mockTitleField.getName()).thenReturn("Advancement");
        when(mockTitleField.getValue()).thenReturn("Stone Age");
        
        when(mockDescField.getName()).thenReturn("Description");
        when(mockDescField.getValue()).thenReturn("Mine stone with your new pickaxe");
        
        List<EmbedField> fields = Arrays.asList(mockPlayerField, mockTitleField, mockDescField);
        when(mockEmbed.getFields()).thenReturn(fields);
        
        // Act
        AdvancementData result = extractor.extractFromEmbed(mockEmbed);
        
        // Assert
        assertEquals("Stone Age", result.getAdvancementTitle());  // Should use field value, not generic title
    }
    
    @Test
    void testExtractFromEmbed_WithNullEmbed_ThrowsExtractionException() {
        // Act & Assert
        ExtractionException exception = assertThrows(ExtractionException.class, 
            () -> extractor.extractFromEmbed(null));
        assertEquals("Embed cannot be null", exception.getMessage());
    }
    
    @Test
    void testExtractFromEmbed_WithNonAdvancementEmbed_ThrowsExtractionException() {
        // Arrange
        when(mockEmbed.getFooter()).thenReturn(Optional.of(mockFooter));
        when(mockFooter.getText()).thenReturn(Optional.of("VonixCore · Join"));  // Not an advancement embed
        
        // Act & Assert
        ExtractionException exception = assertThrows(ExtractionException.class, 
            () -> extractor.extractFromEmbed(mockEmbed));
        assertEquals("Embed is not an advancement embed", exception.getMessage());
    }
    
    @Test
    void testExtractFromEmbed_WithMissingPlayerName_ThrowsExtractionException() {
        // Arrange
        setupValidAdvancementEmbed();
        
        when(mockTitleField.getName()).thenReturn("Advancement");
        when(mockTitleField.getValue()).thenReturn("Stone Age");
        
        when(mockDescField.getName()).thenReturn("Description");
        when(mockDescField.getValue()).thenReturn("Mine stone with your new pickaxe");
        
        List<EmbedField> fields = Arrays.asList(mockTitleField, mockDescField);  // No player field
        when(mockEmbed.getFields()).thenReturn(fields);
        
        // Act & Assert
        ExtractionException exception = assertThrows(ExtractionException.class, 
            () -> extractor.extractFromEmbed(mockEmbed));
        assertEquals("Player name not found in embed fields", exception.getMessage());
    }
    
    @Test
    void testExtractFromEmbed_WithMissingAdvancementTitle_ThrowsExtractionException() {
        // Arrange
        setupValidAdvancementEmbed();
        when(mockEmbed.getTitle()).thenReturn(Optional.of("Advancement Made"));  // Generic title should be ignored
        
        when(mockPlayerField.getName()).thenReturn("Player");
        when(mockPlayerField.getValue()).thenReturn("TestPlayer");
        
        when(mockDescField.getName()).thenReturn("Description");
        when(mockDescField.getValue()).thenReturn("Mine stone with your new pickaxe");
        
        List<EmbedField> fields = Arrays.asList(mockPlayerField, mockDescField);  // No title field
        when(mockEmbed.getFields()).thenReturn(fields);
        
        // Act & Assert
        ExtractionException exception = assertThrows(ExtractionException.class, 
            () -> extractor.extractFromEmbed(mockEmbed));
        assertEquals("Advancement title not found in embed fields", exception.getMessage());
    }
    
    @Test
    void testExtractFromEmbed_WithMissingDescription_ThrowsExtractionException() {
        // Arrange
        setupValidAdvancementEmbed();
        
        when(mockPlayerField.getName()).thenReturn("Player");
        when(mockPlayerField.getValue()).thenReturn("TestPlayer");
        
        when(mockTitleField.getName()).thenReturn("Advancement");
        when(mockTitleField.getValue()).thenReturn("Stone Age");
        
        List<EmbedField> fields = Arrays.asList(mockPlayerField, mockTitleField);  // No description field
        when(mockEmbed.getFields()).thenReturn(fields);
        
        // Act & Assert
        ExtractionException exception = assertThrows(ExtractionException.class, 
            () -> extractor.extractFromEmbed(mockEmbed));
        assertEquals("Advancement description not found in embed fields", exception.getMessage());
    }
    
    @Test
    void testExtractFromEmbed_WithEmptyFieldValues_ThrowsExtractionException() {
        // Arrange
        setupValidAdvancementEmbed();
        
        when(mockPlayerField.getName()).thenReturn("Player");
        when(mockPlayerField.getValue()).thenReturn("");  // Empty value
        
        when(mockTitleField.getName()).thenReturn("Advancement");
        when(mockTitleField.getValue()).thenReturn("Stone Age");
        
        when(mockDescField.getName()).thenReturn("Description");
        when(mockDescField.getValue()).thenReturn("Mine stone with your new pickaxe");
        
        List<EmbedField> fields = Arrays.asList(mockPlayerField, mockTitleField, mockDescField);
        when(mockEmbed.getFields()).thenReturn(fields);
        
        // Act & Assert
        ExtractionException exception = assertThrows(ExtractionException.class, 
            () -> extractor.extractFromEmbed(mockEmbed));
        assertEquals("Player name not found in embed fields", exception.getMessage());
    }
    
    @Test
    void testExtractFromEmbed_WithWhitespaceOnlyValues_ThrowsExtractionException() {
        // Arrange
        setupValidAdvancementEmbed();
        
        when(mockPlayerField.getName()).thenReturn("Player");
        when(mockPlayerField.getValue()).thenReturn("   ");  // Whitespace only
        
        when(mockTitleField.getName()).thenReturn("Advancement");
        when(mockTitleField.getValue()).thenReturn("Stone Age");
        
        when(mockDescField.getName()).thenReturn("Description");
        when(mockDescField.getValue()).thenReturn("Mine stone with your new pickaxe");
        
        List<EmbedField> fields = Arrays.asList(mockPlayerField, mockTitleField, mockDescField);
        when(mockEmbed.getFields()).thenReturn(fields);
        
        // Act & Assert
        ExtractionException exception = assertThrows(ExtractionException.class, 
            () -> extractor.extractFromEmbed(mockEmbed));
        assertEquals("Player name not found in embed fields", exception.getMessage());
    }
    
    @Test
    void testExtractFromEmbed_WithNullFieldValues_SkipsNullFields() throws ExtractionException {
        // Arrange
        setupValidAdvancementEmbed();
        
        EmbedField nullField = mock(EmbedField.class);
        when(nullField.getName()).thenReturn("Player");
        when(nullField.getValue()).thenReturn(null);  // Null value should be skipped
        
        when(mockPlayerField.getName()).thenReturn("User");  // Different field name
        when(mockPlayerField.getValue()).thenReturn("TestPlayer");
        
        when(mockTitleField.getName()).thenReturn("Advancement");
        when(mockTitleField.getValue()).thenReturn("Stone Age");
        
        when(mockDescField.getName()).thenReturn("Description");
        when(mockDescField.getValue()).thenReturn("Mine stone with your new pickaxe");
        
        List<EmbedField> fields = Arrays.asList(nullField, mockPlayerField, mockTitleField, mockDescField);
        when(mockEmbed.getFields()).thenReturn(fields);
        
        // Act
        AdvancementData result = extractor.extractFromEmbed(mockEmbed);
        
        // Assert
        assertEquals("TestPlayer", result.getPlayerName());  // Should use the valid field
    }
    
    @Test
    void testExtractFromEmbed_WithMultipleMatchingFields_UsesFirstMatch() throws ExtractionException {
        // Arrange
        setupValidAdvancementEmbed();
        
        EmbedField firstPlayerField = mock(EmbedField.class);
        when(firstPlayerField.getName()).thenReturn("Player");
        when(firstPlayerField.getValue()).thenReturn("FirstPlayer");
        
        EmbedField secondPlayerField = mock(EmbedField.class);
        when(secondPlayerField.getName()).thenReturn("User");
        when(secondPlayerField.getValue()).thenReturn("SecondPlayer");
        
        when(mockTitleField.getName()).thenReturn("Advancement");
        when(mockTitleField.getValue()).thenReturn("Stone Age");
        
        when(mockDescField.getName()).thenReturn("Description");
        when(mockDescField.getValue()).thenReturn("Mine stone with your new pickaxe");
        
        List<EmbedField> fields = Arrays.asList(firstPlayerField, secondPlayerField, mockTitleField, mockDescField);
        when(mockEmbed.getFields()).thenReturn(fields);
        
        // Act
        AdvancementData result = extractor.extractFromEmbed(mockEmbed);
        
        // Assert
        assertEquals("FirstPlayer", result.getPlayerName());  // Should use the first match
    }
    
    /**
     * Helper method to set up a valid advancement embed mock.
     */
    private void setupValidAdvancementEmbed() {
        when(mockEmbed.getFooter()).thenReturn(Optional.of(mockFooter));
        when(mockFooter.getText()).thenReturn(Optional.of("VonixCore · Advancement"));
        when(mockEmbed.getTitle()).thenReturn(Optional.empty());
        when(mockEmbed.getDescription()).thenReturn(Optional.empty());
    }
}