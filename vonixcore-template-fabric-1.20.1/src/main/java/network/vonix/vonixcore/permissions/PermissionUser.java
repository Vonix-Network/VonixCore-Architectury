package network.vonix.vonixcore.permissions;

import java.util.Set;
import java.util.UUID;

/**
 * Represents a permission user.
 */
public record PermissionUser(
    UUID uuid,
    String username,
    String primaryGroup,
    Set<String> permissions
) {
    public boolean hasPermission(String permission) {
        if (permissions.contains("-" + permission)) return false;
        if (permissions.contains(permission)) return true;
        if (permissions.contains("*")) return true;
        return false;
    }
}
