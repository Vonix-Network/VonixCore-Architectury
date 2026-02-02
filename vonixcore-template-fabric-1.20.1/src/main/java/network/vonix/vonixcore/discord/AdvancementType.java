package network.vonix.vonixcore.discord;

import net.minecraft.ChatFormatting;

/**
 * Enumeration of advancement types with their display text and color mappings.
 */
public enum AdvancementType {
    NORMAL("Advancement Made", ChatFormatting.YELLOW),
    GOAL("Goal Reached", ChatFormatting.YELLOW),
    CHALLENGE("Challenge Complete", ChatFormatting.LIGHT_PURPLE);
    
    private final String displayText;
    private final ChatFormatting color;
    
    AdvancementType(String displayText, ChatFormatting color) {
        this.displayText = displayText;
        this.color = color;
    }
    
    public String getDisplayText() { return displayText; }
    public ChatFormatting getColor() { return color; }
    
    public static AdvancementType fromTitle(String title) {
        if (title == null) return NORMAL;
        String lowerTitle = title.toLowerCase();
        if (lowerTitle.contains("challenge")) return CHALLENGE;
        else if (lowerTitle.contains("goal")) return GOAL;
        else return NORMAL;
    }
}
