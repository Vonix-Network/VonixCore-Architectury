package network.vonix.vonixcore.discord;

import org.javacord.api.entity.message.embed.Embed;
import org.javacord.api.entity.message.embed.EmbedField;

public class EventDataExtractor {
    private final EventEmbedDetector detector = new EventEmbedDetector();
    
    public EventData extractFromEmbed(Embed embed) throws ExtractionException {
        if (embed == null) throw new ExtractionException("Embed cannot be null");
        
        EventEmbedDetector.EventType eventType = detector.getEventType(embed);
        if (eventType == EventEmbedDetector.EventType.UNKNOWN)
            throw new ExtractionException("Could not determine event type from embed");
        
        String playerName = extractPlayerName(embed);
        if (playerName == null || playerName.trim().isEmpty())
            throw new ExtractionException("Could not extract player name from embed");
        
        String deathMessage = eventType == EventEmbedDetector.EventType.DEATH ? extractDeathMessage(embed) : null;
        return new EventData(playerName, eventType, deathMessage);
    }
    
    private String extractPlayerName(Embed embed) {
        for (EmbedField field : embed.getFields()) {
            String fn = field.getName().toLowerCase();
            if (fn.contains("player") || fn.contains("user") || fn.equals("name")) {
                String v = field.getValue();
                if (v != null && !v.trim().isEmpty()) return v.trim();
            }
        }
        if (embed.getAuthor().isPresent()) {
            String an = embed.getAuthor().get().getName();
            if (an != null && !an.trim().isEmpty()) return an.trim();
        }
        if (embed.getDescription().isPresent()) {
            String[] words = embed.getDescription().get().split("\\s+");
            if (words.length > 0 && !words[0].equalsIgnoreCase("a") && !words[0].equalsIgnoreCase("the") && !words[0].equalsIgnoreCase("someone"))
                return words[0].trim();
        }
        return null;
    }
    
    private String extractDeathMessage(Embed embed) {
        for (EmbedField field : embed.getFields()) {
            String fn = field.getName().toLowerCase();
            if (fn.contains("message") || fn.contains("cause") || fn.contains("death")) {
                String v = field.getValue();
                if (v != null && !v.trim().isEmpty() && !v.equals("—")) return v.trim();
            }
        }
        if (embed.getDescription().isPresent()) {
            String desc = embed.getDescription().get();
            if (desc != null && !desc.trim().isEmpty() && !desc.equalsIgnoreCase("a player died")) return desc.trim();
        }
        return null;
    }
}
