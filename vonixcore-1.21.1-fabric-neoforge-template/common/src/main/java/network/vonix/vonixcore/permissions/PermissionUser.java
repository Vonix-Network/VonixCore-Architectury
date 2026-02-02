package network.vonix.vonixcore.permissions;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permission user data.
 */
public class PermissionUser {
    private final UUID uuid;
    private String username;
    private String primaryGroup = "default";
    private String prefix = "";
    private String suffix = "";
    private final Set<String> groups = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> permissions = new ConcurrentHashMap<>();

    public PermissionUser(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPrimaryGroup() {
        return primaryGroup;
    }

    public void setPrimaryGroup(String group) {
        this.primaryGroup = group != null ? group : "default";
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

    public Set<String> getGroups() {
        return groups;
    }

    public void addGroup(String group) {
        groups.add(group.toLowerCase());
    }

    public void removeGroup(String group) {
        groups.remove(group.toLowerCase());
    }

    public boolean hasGroup(String group) {
        return groups.contains(group.toLowerCase()) || primaryGroup.equalsIgnoreCase(group);
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

        // Wildcard matching
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
