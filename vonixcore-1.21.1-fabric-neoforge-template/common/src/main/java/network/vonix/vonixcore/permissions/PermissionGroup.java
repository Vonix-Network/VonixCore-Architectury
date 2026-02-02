package network.vonix.vonixcore.permissions;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permission group with inheritance support.
 */
public class PermissionGroup {
    private final String name;
    private String displayName;
    private String prefix = "";
    private String suffix = "";
    private int weight = 0;
    private String parent = null;
    private final Map<String, Boolean> permissions = new ConcurrentHashMap<>();

    public PermissionGroup(String name) {
        this.name = name.toLowerCase();
        this.displayName = name;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix != null ? prefix : "";
    }

    public String getSuffix() {
        return suffix;
    }

    public void setSuffix(String suffix) {
        this.suffix = suffix != null ? suffix : "";
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public String getParent() {
        return parent;
    }

    public void setParent(String parent) {
        this.parent = parent;
    }

    public Map<String, Boolean> getPermissions() {
        return permissions;
    }

    public void setPermission(String permission, boolean value) {
        permissions.put(permission.toLowerCase(), value);
    }

    public void unsetPermission(String permission) {
        permissions.remove(permission.toLowerCase());
    }

    public Boolean getPermission(String permission) {
        // Exact match
        Boolean exact = permissions.get(permission.toLowerCase());
        if (exact != null)
            return exact;

        // Wildcard matching (e.g., "vonixcore.home.*" matches "vonixcore.home.set")
        String[] parts = permission.toLowerCase().split("\\.");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0)
                builder.append(".");
            builder.append(parts[i]);
            Boolean wildcardPerm = permissions.get(builder + ".*");
            if (wildcardPerm != null)
                return wildcardPerm;
        }

        return null;
    }

    public boolean hasPermission(String permission) {
        Boolean perm = getPermission(permission);
        return perm != null && perm;
    }
}
