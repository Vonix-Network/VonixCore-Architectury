package network.vonix.vonixcore.discord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ServerPrefixConfig class.
 * Tests the server prefix configuration system including unique prefix generation,
 * validation, and fallback handling.
 */
class ServerPrefixConfigTest {
    
    private ServerPrefixConfig config;
    
    @BeforeEach
    void setUp() {
        config = new ServerPrefixConfig();
    }
    
    @Test
    @DisplayName("Should generate unique prefixes for different servers")
    void shouldGenerateUniquePrefixesForDifferentServers() {
        long serverId1 = 123456789L;
        long serverId2 = 987654321L;
        
        String prefix1 = config.getServerPrefix(serverId1);
        String prefix2 = config.getServerPrefix(serverId2);
        
        assertNotNull(prefix1);
        assertNotNull(prefix2);
        assertNotEquals(prefix1, prefix2);
        assertFalse(prefix1.isEmpty());
        assertFalse(prefix2.isEmpty());
    }
    
    @Test
    @DisplayName("Should return same prefix for same server ID")
    void shouldReturnSamePrefixForSameServerId() {
        long serverId = 123456789L;
        
        String prefix1 = config.getServerPrefix(serverId);
        String prefix2 = config.getServerPrefix(serverId);
        
        assertEquals(prefix1, prefix2);
    }
    
    @Test
    @DisplayName("Should allow setting custom prefix for server")
    void shouldAllowSettingCustomPrefixForServer() {
        long serverId = 123456789L;
        String customPrefix = "CUSTOM";
        
        config.setServerPrefix(serverId, customPrefix);
        String retrievedPrefix = config.getServerPrefix(serverId);
        
        assertEquals(customPrefix, retrievedPrefix);
    }
    
    @Test
    @DisplayName("Should reject invalid prefixes")
    void shouldRejectInvalidPrefixes() {
        long serverId = 123456789L;
        
        // Test null prefix
        assertThrows(IllegalArgumentException.class, () -> 
            config.setServerPrefix(serverId, null));
        
        // Test empty prefix
        assertThrows(IllegalArgumentException.class, () -> 
            config.setServerPrefix(serverId, ""));
        
        // Test whitespace-only prefix
        assertThrows(IllegalArgumentException.class, () -> 
            config.setServerPrefix(serverId, "   "));
        
        // Test too long prefix (max is 16)
        assertThrows(IllegalArgumentException.class, () -> 
            config.setServerPrefix(serverId, "VERYLONGPREFIXNAME"));
        
        // Test invalid characters
        assertThrows(IllegalArgumentException.class, () -> 
            config.setServerPrefix(serverId, "PREFIX@"));
    }
    
    @Test
    @DisplayName("Should prevent duplicate prefixes across servers")
    void shouldPreventDuplicatePrefixesAcrossServers() {
        long serverId1 = 123456789L;
        long serverId2 = 987654321L;
        String prefix = "SHARED";
        
        // Set prefix for first server
        config.setServerPrefix(serverId1, prefix);
        
        // Try to set same prefix for second server - should fail
        assertThrows(IllegalArgumentException.class, () -> 
            config.setServerPrefix(serverId2, prefix));
    }
    
    @Test
    @DisplayName("Should allow updating prefix for same server")
    void shouldAllowUpdatingPrefixForSameServer() {
        long serverId = 123456789L;
        String prefix1 = "PREFIX1";
        String prefix2 = "PREFIX2";
        
        config.setServerPrefix(serverId, prefix1);
        assertEquals(prefix1, config.getServerPrefix(serverId));
        
        // Update to new prefix - should work
        assertDoesNotThrow(() -> config.setServerPrefix(serverId, prefix2));
        assertEquals(prefix2, config.getServerPrefix(serverId));
    }
    
    @Test
    @DisplayName("Should handle fallback prefix correctly")
    void shouldHandleFallbackPrefixCorrectly() {
        String defaultFallback = config.getFallbackPrefix();
        assertNotNull(defaultFallback);
        assertFalse(defaultFallback.isEmpty());
        
        String customFallback = "FALLBACK";
        config.setFallbackPrefix(customFallback);
        assertEquals(customFallback, config.getFallbackPrefix());
    }
    
    @Test
    @DisplayName("Should validate configuration correctly")
    void shouldValidateConfigurationCorrectly() {
        // Initially should be valid
        assertTrue(config.validateConfiguration());
        
        // Add valid configuration
        config.setServerPrefix(123L, "VALID");
        assertTrue(config.validateConfiguration());
        
        // Set invalid fallback prefix directly (bypassing validation)
        config.setFallbackPrefix("VALID");
        assertTrue(config.validateConfiguration());
    }
    
    @Test
    @DisplayName("Should clear all prefixes correctly")
    void shouldClearAllPrefixesCorrectly() {
        long serverId1 = 123456789L;
        long serverId2 = 987654321L;
        
        config.setServerPrefix(serverId1, "PREFIX1");
        config.setServerPrefix(serverId2, "PREFIX2");
        
        assertEquals(2, config.getAllServerPrefixes().size());
        
        config.clearAllPrefixes();
        
        assertEquals(0, config.getAllServerPrefixes().size());
        
        // Should generate new unique prefixes after clearing
        String newPrefix1 = config.getServerPrefix(serverId1);
        String newPrefix2 = config.getServerPrefix(serverId2);
        
        assertNotEquals(newPrefix1, newPrefix2);
    }
    
    @Test
    @DisplayName("Should remove server prefix correctly")
    void shouldRemoveServerPrefixCorrectly() {
        long serverId = 123456789L;
        String customPrefix = "CUSTOM";
        
        config.setServerPrefix(serverId, customPrefix);
        assertEquals(customPrefix, config.getServerPrefix(serverId));
        
        config.removeServerPrefix(serverId);
        
        // Should generate a new prefix after removal
        String newPrefix = config.getServerPrefix(serverId);
        assertNotEquals(customPrefix, newPrefix);
    }
    
    @Test
    @DisplayName("Should handle null server gracefully")
    void shouldHandleNullServerGracefully() {
        String prefix = config.getServerPrefix((org.javacord.api.entity.server.Server) null);
        assertEquals(config.getFallbackPrefix(), prefix);
    }
    
    @Test
    @DisplayName("Should accept valid prefix characters")
    void shouldAcceptValidPrefixCharacters() {
        long serverId = 123456789L;
        
        // Test alphanumeric
        assertDoesNotThrow(() -> config.setServerPrefix(serverId, "ABC123"));
        
        // Test with allowed symbols
        config.removeServerPrefix(serverId);
        assertDoesNotThrow(() -> config.setServerPrefix(serverId, "TEST-1"));
        
        config.removeServerPrefix(serverId);
        assertDoesNotThrow(() -> config.setServerPrefix(serverId, "TEST_2"));
        
        config.removeServerPrefix(serverId);
        assertDoesNotThrow(() -> config.setServerPrefix(serverId, "[SRV]"));
        
        config.removeServerPrefix(serverId);
        assertDoesNotThrow(() -> config.setServerPrefix(serverId, "(MC)"));
        
        config.removeServerPrefix(serverId);
        assertDoesNotThrow(() -> config.setServerPrefix(serverId, "{TEST}"));
    }
}