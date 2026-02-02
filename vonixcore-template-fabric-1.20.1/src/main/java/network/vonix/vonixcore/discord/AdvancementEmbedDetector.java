package network.vonix.vonixcore.discord;

import network.vonix.vonixcore.VonixCore;
import org.javacord.api.entity.message.embed.Embed;
import java.util.Set;

/**
 * Detector class for identifying advancement embeds from Discord messages.
 */
public class AdvancementEmbedDetector {
    
    private static final Set<String> ADVANCEMENT_FOOTER_KEYWORDS = Set.of(
        "advancement", "goal", "challenge", "task"
    );
    
    public boolean isAdvancementEmbed(Embed embed) {
        if (embed == null) return false;
        try {
            return hasAdvancementFooter(embed);
        } catch (Exception e) {
            VonixCore.LOGGER.warn("[Discord] Error during advancement embed detection: {}", e.getMessage());
            return false;
        }
    }
    
    public AdvancementType getAdvancementType(Embed embed) {
        if (embed == null) return AdvancementType.NORMAL;
        
        try {
            if (embed.getTitle().isPresent()) {
                String title = embed.getTitle().get().toLowerCase();
                if (title.contains("challenge")) return AdvancementType.CHALLENGE;
                else if (title.contains("goal")) return AdvancementType.GOAL;
            }
            
            if (embed.getFooter().isPresent()) {
                String footerText = embed.getFooter().get().getText().map(String::toLowerCase).orElse("");
                if (footerText.contains("challenge")) return AdvancementType.CHALLENGE;
                else if (footerText.contains("goal")) return AdvancementType.GOAL;
            }
        } catch (Exception e) {
            VonixCore.LOGGER.warn("[Discord] Error determining advancement type: {}", e.getMessage());
        }
        
        return AdvancementType.NORMAL;
    }
    
    private boolean hasAdvancementFooter(Embed embed) {
        if (!embed.getFooter().isPresent()) return false;
        String footerText = embed.getFooter().get().getText().map(String::toLowerCase).orElse("");
        return ADVANCEMENT_FOOTER_KEYWORDS.stream().anyMatch(footerText::contains);
    }
}
