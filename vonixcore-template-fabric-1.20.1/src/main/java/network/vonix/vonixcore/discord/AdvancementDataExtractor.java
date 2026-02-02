package network.vonix.vonixcore.discord;

import network.vonix.vonixcore.VonixCore;
import org.javacord.api.entity.message.embed.Embed;
import org.javacord.api.entity.message.embed.EmbedField;

/**
 * Extractor class for parsing advancement data from Discord embeds.
 */
public class AdvancementDataExtractor {
    
    private final AdvancementEmbedDetector detector;
    
    public AdvancementDataExtractor() {
        this.detector = new AdvancementEmbedDetector();
    }
    
    public AdvancementData extractFromEmbed(Embed embed) throws ExtractionException {
        if (embed == null) throw new ExtractionException("Embed cannot be null");
        
        if (!detector.isAdvancementEmbed(embed)) {
            throw new ExtractionException("Embed is not an advancement embed");
        }
        
        AdvancementType type = detector.getAdvancementType(embed);
        
        String playerName = null;
        String advancementTitle = null;
        String advancementDescription = null;
        
        for (EmbedField field : embed.getFields()) {
            String fieldName = field.getName();
            String fieldValue = field.getValue();
            
            if (fieldName == null || fieldValue == null) continue;
            
            String normalizedName = fieldName.toLowerCase().trim();
            String normalizedValue = fieldValue.trim();
            
            if (normalizedValue.isEmpty()) continue;
            
            if (normalizedName.contains("player") || normalizedName.contains("user") || normalizedName.contains("name")) {
                if (playerName == null) playerName = normalizedValue;
            } else if (normalizedName.contains("advancement") || normalizedName.contains("title") || normalizedName.contains("achievement")) {
                if (advancementTitle == null) advancementTitle = normalizedValue;
            } else if (normalizedName.contains("description") || normalizedName.contains("desc") || normalizedName.contains("details")) {
                if (advancementDescription == null) advancementDescription = normalizedValue;
            }
        }
        
        if (advancementTitle == null && embed.getTitle().isPresent()) {
            String embedTitle = embed.getTitle().get().trim();
            if (!embedTitle.isEmpty() && !isGenericTitle(embedTitle)) {
                advancementTitle = embedTitle;
            }
        }
        
        if (advancementDescription == null && embed.getDescription().isPresent()) {
            String embedDesc = embed.getDescription().get().trim();
            if (!embedDesc.isEmpty()) {
                advancementDescription = embedDesc;
            }
        }
        
        if (playerName == null || playerName.trim().isEmpty()) {
            throw new ExtractionException("Player name not found in embed fields");
        }
        if (advancementTitle == null || advancementTitle.trim().isEmpty()) {
            throw new ExtractionException("Advancement title not found in embed fields");
        }
        if (advancementDescription == null || advancementDescription.trim().isEmpty()) {
            throw new ExtractionException("Advancement description not found in embed fields");
        }
        
        return new AdvancementData(playerName, advancementTitle, advancementDescription, type);
    }
    
    private boolean isGenericTitle(String title) {
        String lowerTitle = title.toLowerCase();
        return lowerTitle.equals("advancement made") || 
               lowerTitle.equals("goal reached") || 
               lowerTitle.equals("challenge complete") ||
               lowerTitle.equals("advancement") ||
               lowerTitle.equals("goal") ||
               lowerTitle.equals("challenge");
    }
}
