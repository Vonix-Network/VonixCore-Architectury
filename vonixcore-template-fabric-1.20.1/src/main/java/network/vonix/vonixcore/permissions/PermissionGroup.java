package network.vonix.vonixcore.permissions;

import java.util.Set;

/**
 * Represents a permission group.
 */
public record PermissionGroup(
    String name,
    int priority,
    String prefix,
    String suffix,
    boolean isDefault,
    Set<String> permissions
) {
    public boolean hasPermission(String permission) {
        if (permissions.contains("-" + permission)) return false;
        if (permissions.contains(permission)) return true;
        if (permissions.contains("*")) return true;
        
        // Check wildcard patterns
        String[] parts = permission.split("\\.");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (i > 0) builder.append(".");
            builder.append(parts[i]);
            if (permissions.contains(builder.toString() + ".*")) {
                return true;
            }
        }
        return false;
    }
}
