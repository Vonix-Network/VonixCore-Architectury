package network.vonix.vonixcore.permissions;

import network.vonix.vonixcore.VonixCore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages permissions for players and groups.
 */
public class PermissionManager {

    private static PermissionManager instance;
    private final Map<UUID, Set<String>> playerPermissions = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerGroups = new ConcurrentHashMap<>();
    private final Map<String, PermissionGroup> groups = new ConcurrentHashMap<>();

    public static PermissionManager getInstance() {
        if (instance == null) {
            instance = new PermissionManager();
        }
        return instance;
    }

    /**
     * Initialize permissions tables in database.
     */
    public void initializeTable(Connection conn) throws SQLException {
        // Groups table
        conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS vc_groups (
                    name TEXT PRIMARY KEY,
                    priority INTEGER DEFAULT 0,
                    prefix TEXT,
                    suffix TEXT,
                    is_default INTEGER DEFAULT 0
                )
            """);

        // Group permissions table
        conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS vc_group_permissions (
                    group_name TEXT NOT NULL,
                    permission TEXT NOT NULL,
                    value INTEGER DEFAULT 1,
                    PRIMARY KEY (group_name, permission)
                )
            """);

        // Player permissions table
        conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS vc_player_permissions (
                    uuid TEXT NOT NULL,
                    permission TEXT NOT NULL,
                    value INTEGER DEFAULT 1,
                    PRIMARY KEY (uuid, permission)
                )
            """);

        // Player groups table
        conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS vc_player_groups (
                    uuid TEXT PRIMARY KEY,
                    group_name TEXT NOT NULL
                )
            """);

        conn.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_player_perms_uuid ON vc_player_permissions (uuid)");
        
        // Create default group if not exists
        createDefaultGroup(conn);
    }

    private void createDefaultGroup(Connection conn) throws SQLException {
        PreparedStatement checkStmt = conn.prepareStatement("SELECT COUNT(*) FROM vc_groups WHERE name = 'default'");
        ResultSet rs = checkStmt.executeQuery();
        if (rs.next() && rs.getInt(1) == 0) {
            PreparedStatement insertStmt = conn.prepareStatement(
                "INSERT INTO vc_groups (name, priority, prefix, is_default) VALUES ('default', 0, '§7', 1)");
            insertStmt.executeUpdate();
            VonixCore.LOGGER.info("[Permissions] Created default group");
        }
    }

    /**
     * Load all groups from database.
     */
    public void loadGroups() {
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM vc_groups ORDER BY priority DESC");
            while (rs.next()) {
                String name = rs.getString("name");
                int priority = rs.getInt("priority");
                String prefix = rs.getString("prefix");
                String suffix = rs.getString("suffix");
                boolean isDefault = rs.getInt("is_default") == 1;

                Set<String> permissions = loadGroupPermissions(conn, name);
                groups.put(name, new PermissionGroup(name, priority, prefix, suffix, isDefault, permissions));
            }
            VonixCore.LOGGER.info("[Permissions] Loaded {} groups", groups.size());
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[Permissions] Failed to load groups: {}", e.getMessage());
        }
    }

    private Set<String> loadGroupPermissions(Connection conn, String groupName) throws SQLException {
        Set<String> permissions = new HashSet<>();
        PreparedStatement stmt = conn.prepareStatement(
            "SELECT permission, value FROM vc_group_permissions WHERE group_name = ?");
        stmt.setString(1, groupName);
        ResultSet rs = stmt.executeQuery();
        while (rs.next()) {
            String perm = rs.getString("permission");
            boolean value = rs.getInt("value") == 1;
            if (value) {
                permissions.add(perm);
            } else {
                permissions.add("-" + perm);
            }
        }
        return permissions;
    }

    /**
     * Check if a player has a permission.
     */
    public boolean hasPermission(UUID uuid, String permission) {
        // Check player-specific permissions first
        Set<String> playerPerms = playerPermissions.get(uuid);
        if (playerPerms != null) {
            if (playerPerms.contains("-" + permission)) return false;
            if (playerPerms.contains(permission)) return true;
            if (playerPerms.contains("*")) return true;
        }

        // Check group permissions
        String groupName = playerGroups.getOrDefault(uuid, "default");
        PermissionGroup group = groups.get(groupName);
        if (group != null) {
            return group.hasPermission(permission);
        }

        // Check default group
        PermissionGroup defaultGroup = groups.get("default");
        return defaultGroup != null && defaultGroup.hasPermission(permission);
    }

    /**
     * Get a player's group.
     */
    public String getPlayerGroup(UUID uuid) {
        return playerGroups.getOrDefault(uuid, "default");
    }

    /**
     * Get a player's prefix.
     */
    public String getPrefix(UUID uuid) {
        String groupName = getPlayerGroup(uuid);
        PermissionGroup group = groups.get(groupName);
        return group != null ? group.prefix() : "";
    }

    /**
     * Get a player's suffix.
     */
    public String getSuffix(UUID uuid) {
        String groupName = getPlayerGroup(uuid);
        PermissionGroup group = groups.get(groupName);
        return group != null ? group.suffix() : "";
    }

    public Collection<PermissionGroup> getGroups() {
        return groups.values();
    }

    public PermissionGroup getGroup(String name) {
        return groups.get(name);
    }
}
