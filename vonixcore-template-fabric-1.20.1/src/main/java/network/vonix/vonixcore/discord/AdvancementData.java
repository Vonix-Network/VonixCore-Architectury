package network.vonix.vonixcore.discord;

import java.util.Objects;

/**
 * Data class representing extracted advancement information from Discord embeds.
 */
public class AdvancementData {
    private final String playerName;
    private final String advancementTitle;
    private final String advancementDescription;
    private final AdvancementType type;
    
    public AdvancementData(String playerName, String advancementTitle, 
                          String advancementDescription, AdvancementType type) {
        this.playerName = validateString(playerName, "playerName");
        this.advancementTitle = validateString(advancementTitle, "advancementTitle");
        this.advancementDescription = validateString(advancementDescription, "advancementDescription");
        this.type = Objects.requireNonNull(type, "type cannot be null");
    }
    
    private String validateString(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be empty");
        }
        return value.trim();
    }
    
    public String getPlayerName() { return playerName; }
    public String getAdvancementTitle() { return advancementTitle; }
    public String getAdvancementDescription() { return advancementDescription; }
    public AdvancementType getType() { return type; }
    
    @Override
    public String toString() {
        return "AdvancementData{playerName='" + playerName + "', advancementTitle='" + advancementTitle + 
               "', advancementDescription='" + advancementDescription + "', type=" + type + '}';
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        AdvancementData that = (AdvancementData) obj;
        return Objects.equals(playerName, that.playerName) &&
               Objects.equals(advancementTitle, that.advancementTitle) &&
               Objects.equals(advancementDescription, that.advancementDescription) &&
               type == that.type;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(playerName, advancementTitle, advancementDescription, type);
    }
}
