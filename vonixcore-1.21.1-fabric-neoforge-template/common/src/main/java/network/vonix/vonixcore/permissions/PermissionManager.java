package network.vonix.vonixcore.permissions;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.database.Database;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Comprehensive permission system - LuckPerms replacement.
 * Features: Groups with inheritance, per-user permissions, prefix/suffix,
 * weights.
 */
public class PermissionManager {
    private static PermissionManager instance;

    // Cache
    private final Map<UUID, PermissionUser> userCache = new ConcurrentHashMap<>();
    private final Map<String, PermissionGroup> groupCache = new ConcurrentHashMap<>();

    // LuckPerms integration fallback
    private boolean useLuckPerms = false;
    private Object luckPermsApi = null;

    public static PermissionManager getInstance() {
        if (instance == null)
            instance = new PermissionManager();
        return instance;
    }

    /**
     * Initialize permission system - check for LuckPerms first
     */
    public void initialize(Connection conn) throws SQLException {
        // Try to detect LuckPerms
        try {
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
            luckPermsApi = provider.getMethod("get").invoke(null);
            useLuckPerms = true;
            VonixCore.LOGGER.info("[Permissions] LuckPerms detected - using as backend");
            return;
        } catch (Exception e) {
            VonixCore.LOGGER.info("[Permissions] LuckPerms not found - using built-in system");
        }

        // Create tables for built-in system
        createTables(conn);
        loadGroups(conn);

        // Create default group if none exists
        if (!groupCache.containsKey("default")) {
            createDefaultGroup(conn);
        }
    }

    private void createTables(Connection conn) throws SQLException {
        // Groups table
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS vc_groups (
                            name VARCHAR(64) PRIMARY KEY,
                            display_name VARCHAR(128),
                            prefix VARCHAR(256),
                            suffix VARCHAR(256),
                            weight INT DEFAULT 0,
                            parent VARCHAR(64),
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                    """);
        }

        // Group permissions table
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS vc_group_permissions (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            group_name VARCHAR(64),
                            permission VARCHAR(256),
                            value BOOLEAN DEFAULT TRUE,
                            UNIQUE(group_name, permission)
                        )
                    """);
        }

        // User data table
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS vc_user_permissions (
                            uuid VARCHAR(36) PRIMARY KEY,
                            username VARCHAR(16),
                            primary_group VARCHAR(64) DEFAULT 'default',
                            prefix VARCHAR(256),
                            suffix VARCHAR(256),
                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                    """);
        }

        // User additional groups
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS vc_user_groups (
                            uuid VARCHAR(36),
                            group_name VARCHAR(64),
                            expires_at TIMESTAMP NULL,
                            PRIMARY KEY(uuid, group_name)
                        )
                    """);
        }

        // User specific permissions
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS vc_user_perms (
                            uuid VARCHAR(36),
                            permission VARCHAR(256),
                            value BOOLEAN DEFAULT TRUE,
                            PRIMARY KEY(uuid, permission)
                        )
                    """);
        }
    }

    private void createDefaultGroup(Connection conn) throws SQLException {
        PermissionGroup defaultGroup = new PermissionGroup("default");
        defaultGroup.setDisplayName("§7Member");
        defaultGroup.setPrefix("§7");
        defaultGroup.setWeight(0);
        saveGroup(conn, defaultGroup);
        groupCache.put("default", defaultGroup);
        VonixCore.LOGGER.info("[Permissions] Created default group");
    }

    private void loadGroups(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM vc_groups")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                PermissionGroup group = new PermissionGroup(rs.getString("name"));
                group.setDisplayName(rs.getString("display_name"));
                group.setPrefix(rs.getString("prefix"));
                group.setSuffix(rs.getString("suffix"));
                group.setWeight(rs.getInt("weight"));
                group.setParent(rs.getString("parent"));
                loadGroupPermissions(conn, group);
                groupCache.put(group.getName(), group);
            }
        }
        VonixCore.LOGGER.info("[Permissions] Loaded {} groups", groupCache.size());
    }

    private void loadGroupPermissions(Connection conn, PermissionGroup group) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT permission, value FROM vc_group_permissions WHERE group_name = ?")) {
            ps.setString(1, group.getName());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                group.setPermission(rs.getString("permission"), rs.getBoolean("value"));
            }
        }
    }

    // === PUBLIC API ===

    public boolean hasPermission(ServerPlayer player, String permission) {
        if (useLuckPerms)
            return hasPermissionLuckPerms(player, permission);

        PermissionUser user = getUser(player.getUUID());
        if (user == null)
            return false;

        // Check user-specific permissions first
        Boolean userPerm = user.getPermission(permission);
        if (userPerm != null)
            return userPerm;

        // Check wildcard
        if (user.hasPermission("*"))
            return true;

        // Check groups (primary first, then additional by weight)
        List<PermissionGroup> groups = getUserGroups(user);
        for (PermissionGroup group : groups) {
            Boolean groupPerm = getGroupPermissionWithInheritance(group, permission);
            if (groupPerm != null)
                return groupPerm;
        }

        return false;
    }

    public boolean hasPermission(UUID uuid, String permission) {
        ServerPlayer player = getPlayerByUuid(uuid);
        if (player != null)
            return hasPermission(player, permission);

        // Offline check
        PermissionUser user = getUser(uuid);
        if (user == null)
            return false;

        Boolean userPerm = user.getPermission(permission);
        if (userPerm != null)
            return userPerm;

        List<PermissionGroup> groups = getUserGroups(user);
        for (PermissionGroup group : groups) {
            Boolean groupPerm = getGroupPermissionWithInheritance(group, permission);
            if (groupPerm != null)
                return groupPerm;
        }
        return false;
    }

    private Boolean getGroupPermissionWithInheritance(PermissionGroup group, String permission) {
        if (group == null)
            return null;

        // Check this group
        Boolean perm = group.getPermission(permission);
        if (perm != null)
            return perm;

        // Wildcard check
        if (group.hasPermission("*"))
            return true;

        // Check parent group (inheritance)
        if (group.getParent() != null) {
            PermissionGroup parent = groupCache.get(group.getParent());
            return getGroupPermissionWithInheritance(parent, permission);
        }

        return null;
    }

    private List<PermissionGroup> getUserGroups(PermissionUser user) {
        List<PermissionGroup> groups = new ArrayList<>();

        // Primary group
        PermissionGroup primary = groupCache.get(user.getPrimaryGroup());
        if (primary != null)
            groups.add(primary);

        // Additional groups sorted by weight
        for (String groupName : user.getGroups()) {
            PermissionGroup g = groupCache.get(groupName);
            if (g != null && !groups.contains(g))
                groups.add(g);
        }

        groups.sort((a, b) -> Integer.compare(b.getWeight(), a.getWeight()));
        return groups;
    }

    public String getPrefix(UUID uuid) {
        if (useLuckPerms)
            return getPrefixLuckPerms(uuid);

        PermissionUser user = getUser(uuid);
        if (user == null)
            return "";

        if (user.getPrefix() != null && !user.getPrefix().isEmpty()) {
            return user.getPrefix();
        } else {
            // Debug log
            // VonixCore.LOGGER.info("User {} has no personal prefix", uuid);
        }

        // Get from highest weight group
        List<PermissionGroup> groups = getUserGroups(user);
        for (PermissionGroup group : groups) {
            if (group.getPrefix() != null && !group.getPrefix().isEmpty()) {
                return group.getPrefix();
            }
        }
        return "";
    }

    public String getSuffix(UUID uuid) {
        if (useLuckPerms)
            return getSuffixLuckPerms(uuid);

        PermissionUser user = getUser(uuid);
        if (user == null)
            return "";

        if (user.getSuffix() != null && !user.getSuffix().isEmpty()) {
            return user.getSuffix();
        }

        List<PermissionGroup> groups = getUserGroups(user);
        for (PermissionGroup group : groups) {
            if (group.getSuffix() != null && !group.getSuffix().isEmpty()) {
                return group.getSuffix();
            }
        }
        return "";
    }

    public String getPrimaryGroup(UUID uuid) {
        if (useLuckPerms)
            return getPrimaryGroupLuckPerms(uuid);
        PermissionUser user = getUser(uuid);
        return user != null ? user.getPrimaryGroup() : "default";
    }

    public PermissionGroup getGroup(String name) {
        return groupCache.get(name.toLowerCase());
    }

    public Collection<PermissionGroup> getGroups() {
        return Collections.unmodifiableCollection(groupCache.values());
    }

    // === USER MANAGEMENT ===

    public PermissionUser getUser(UUID uuid) {
        // IMPORTANT: Don't use computeIfAbsent with database I/O - it can cause
        // deadlocks!
        // The mapping function runs while holding a lock on the ConcurrentHashMap
        // segment.
        PermissionUser user = userCache.get(uuid);
        if (user != null) {
            return user;
        }
        // Load outside the lock
        user = loadUser(uuid);
        // Use putIfAbsent to handle race conditions safely
        PermissionUser existing = userCache.putIfAbsent(uuid, user);
        return existing != null ? existing : user;
    }

    private PermissionUser loadUser(UUID uuid) {
        Database db = VonixCore.getInstance().getDatabase();
        if (db == null)
            return new PermissionUser(uuid);

        try (Connection conn = db.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM vc_user_permissions WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    PermissionUser user = new PermissionUser(uuid);
                    user.setUsername(rs.getString("username"));
                    user.setPrimaryGroup(rs.getString("primary_group"));
                    user.setPrefix(rs.getString("prefix"));
                    user.setSuffix(rs.getString("suffix"));
                    loadUserGroups(conn, user);
                    loadUserPermissions(conn, user);
                    return user;
                }
            }
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[Permissions] Error loading user {}", uuid, e);
        }
        return new PermissionUser(uuid);
    }

    private void loadUserGroups(Connection conn, PermissionUser user) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT group_name FROM vc_user_groups WHERE uuid = ? AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)")) {
            ps.setString(1, user.getUuid().toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                user.addGroup(rs.getString("group_name"));
            }
        }
    }

    private void loadUserPermissions(Connection conn, PermissionUser user) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT permission, value FROM vc_user_perms WHERE uuid = ?")) {
            ps.setString(1, user.getUuid().toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                user.setPermission(rs.getString("permission"), rs.getBoolean("value"));
            }
        }
    }

    public void saveUser(PermissionUser user) {
        Database db = VonixCore.getInstance().getDatabase();
        if (db == null)
            return;

        try (Connection conn = db.getConnection()) {
            // Upsert user data
            try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT OR REPLACE INTO vc_user_permissions
                        (uuid, username, primary_group, prefix, suffix, updated_at)
                        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """)) {
                ps.setString(1, user.getUuid().toString());
                ps.setString(2, user.getUsername());
                ps.setString(3, user.getPrimaryGroup());
                ps.setString(4, user.getPrefix());
                ps.setString(5, user.getSuffix());
                ps.executeUpdate();
            }

            // Save permissions
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM vc_user_perms WHERE uuid = ?")) {
                del.setString(1, user.getUuid().toString());
                del.executeUpdate();
            }
            for (Map.Entry<String, Boolean> perm : user.getPermissions().entrySet()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO vc_user_perms (uuid, permission, value) VALUES (?, ?, ?)")) {
                    ps.setString(1, user.getUuid().toString());
                    ps.setString(2, perm.getKey());
                    ps.setBoolean(3, perm.getValue());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[Permissions] Error saving user {}", user.getUuid(), e);
        }
    }

    // === GROUP MANAGEMENT ===

    public void saveGroup(Connection conn, PermissionGroup group) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT OR REPLACE INTO vc_groups
                    (name, display_name, prefix, suffix, weight, parent)
                    VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, group.getName());
            ps.setString(2, group.getDisplayName());
            ps.setString(3, group.getPrefix());
            ps.setString(4, group.getSuffix());
            ps.setInt(5, group.getWeight());
            ps.setString(6, group.getParent());
            ps.executeUpdate();
        }

        // Save permissions
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM vc_group_permissions WHERE group_name = ?")) {
            del.setString(1, group.getName());
            del.executeUpdate();
        }
        for (Map.Entry<String, Boolean> perm : group.getPermissions().entrySet()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO vc_group_permissions (group_name, permission, value) VALUES (?, ?, ?)")) {
                ps.setString(1, group.getName());
                ps.setString(2, perm.getKey());
                ps.setBoolean(3, perm.getValue());
                ps.executeUpdate();
            }
        }

        groupCache.put(group.getName(), group);
    }

    public void createGroup(String name) {
        PermissionGroup group = new PermissionGroup(name.toLowerCase());
        group.setParent("default");
        groupCache.put(name.toLowerCase(), group);

        Database db = VonixCore.getInstance().getDatabase();
        if (db != null) {
            try (Connection conn = db.getConnection()) {
                saveGroup(conn, group);
            } catch (SQLException e) {
                VonixCore.LOGGER.error("[Permissions] Error creating group {}", name, e);
            }
        }
    }

    public void deleteGroup(String name) {
        if (name.equalsIgnoreCase("default"))
            return; // Cannot delete default
        groupCache.remove(name.toLowerCase());

        Database db = VonixCore.getInstance().getDatabase();
        if (db != null) {
            try (Connection conn = db.getConnection()) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM vc_groups WHERE name = ?")) {
                    ps.setString(1, name.toLowerCase());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn
                        .prepareStatement("DELETE FROM vc_group_permissions WHERE group_name = ?")) {
                    ps.setString(1, name.toLowerCase());
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                VonixCore.LOGGER.error("[Permissions] Error deleting group {}", name, e);
            }
        }
    }

    // === LUCKPERMS INTEGRATION ===

    private boolean hasPermissionLuckPerms(ServerPlayer player, String permission) {
        try {
            Object userManager = luckPermsApi.getClass().getMethod("getUserManager").invoke(luckPermsApi);
            Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, player.getUUID());
            if (user == null)
                return false;
            Object data = user.getClass().getMethod("getCachedData").invoke(user);
            Object permData = data.getClass().getMethod("getPermissionData").invoke(data);
            Object result = permData.getClass().getMethod("checkPermission", String.class).invoke(permData, permission);
            return result.toString().equals("TRUE");
        } catch (Exception e) {
            return false;
        }
    }

    private String getPrefixLuckPerms(UUID uuid) {
        try {
            Object userManager = luckPermsApi.getClass().getMethod("getUserManager").invoke(luckPermsApi);
            Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, uuid);
            if (user == null)
                return "";
            Object data = user.getClass().getMethod("getCachedData").invoke(user);
            Object metaData = data.getClass().getMethod("getMetaData").invoke(data);
            Object prefix = metaData.getClass().getMethod("getPrefix").invoke(metaData);
            return prefix != null ? prefix.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String getSuffixLuckPerms(UUID uuid) {
        try {
            Object userManager = luckPermsApi.getClass().getMethod("getUserManager").invoke(luckPermsApi);
            Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, uuid);
            if (user == null)
                return "";
            Object data = user.getClass().getMethod("getCachedData").invoke(user);
            Object metaData = data.getClass().getMethod("getMetaData").invoke(data);
            Object suffix = metaData.getClass().getMethod("getSuffix").invoke(metaData);
            return suffix != null ? suffix.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String getPrimaryGroupLuckPerms(UUID uuid) {
        try {
            Object userManager = luckPermsApi.getClass().getMethod("getUserManager").invoke(luckPermsApi);
            Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, uuid);
            if (user == null)
                return "default";
            Object group = user.getClass().getMethod("getPrimaryGroup").invoke(user);
            return group != null ? group.toString() : "default";
        } catch (Exception e) {
            return "default";
        }
    }

    private ServerPlayer getPlayerByUuid(UUID uuid) {
        MinecraftServer server = VonixCore.getInstance().getServer();
        if (server != null) {
            return server.getPlayerList().getPlayer(uuid);
        }
        return null;
    }

    public boolean isUsingLuckPerms() {
        return useLuckPerms;
    }

    public void clearCache() {
        userCache.clear();
    }

    public void clearUserCache(UUID uuid) {
        userCache.remove(uuid);
    }
}
