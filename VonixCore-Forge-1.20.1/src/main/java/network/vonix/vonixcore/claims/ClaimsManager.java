package network.vonix.vonixcore.claims;

import net.minecraft.core.BlockPos;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.ClaimsConfig;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages land claims - creation, deletion, and permission checks.
 */
public class ClaimsManager {

    private static ClaimsManager instance;

    private final Map<Integer, Claim> claims = new ConcurrentHashMap<>();
    private final Map<UUID, BlockPos> corner1Selections = new ConcurrentHashMap<>();
    private final Map<UUID, BlockPos> corner2Selections = new ConcurrentHashMap<>();

    public static ClaimsManager getInstance() {
        if (instance == null) {
            instance = new ClaimsManager();
        }
        return instance;
    }

    /**
     * Initialize claims table in database
     */
    public void initializeTable(Connection conn) throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS vonixcore_claims (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    owner VARCHAR(36) NOT NULL,
                    owner_name VARCHAR(16),
                    world VARCHAR(128) NOT NULL,
                    x1 INT NOT NULL, y1 INT NOT NULL, z1 INT NOT NULL,
                    x2 INT NOT NULL, y2 INT NOT NULL, z2 INT NOT NULL,
                    trusted TEXT,
                    created_at BIGINT NOT NULL
                )
                """;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
        loadClaims(conn);
        VonixCore.LOGGER.info("[VonixCore] Claims table initialized, loaded {} claims", claims.size());
    }

    /**
     * Load all claims from database
     */
    private void loadClaims(Connection conn) throws SQLException {
        claims.clear();
        String sql = "SELECT * FROM vonixcore_claims";
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                int id = rs.getInt("id");
                UUID owner = UUID.fromString(rs.getString("owner"));
                String ownerName = rs.getString("owner_name");
                String world = rs.getString("world");
                int x1 = rs.getInt("x1"), y1 = rs.getInt("y1"), z1 = rs.getInt("z1");
                int x2 = rs.getInt("x2"), y2 = rs.getInt("y2"), z2 = rs.getInt("z2");
                String trustedJson = rs.getString("trusted");
                long createdAt = rs.getLong("created_at");

                Set<UUID> trusted = parseTrusted(trustedJson);
                Claim claim = new Claim(id, owner, ownerName, world, x1, y1, z1, x2, y2, z2, trusted, createdAt);
                claims.put(id, claim);
            }
        }
    }

    /**
     * Create a new claim
     */
    public Claim createClaim(UUID owner, String ownerName, String world,
            BlockPos pos1, BlockPos pos2) {
        // Validate size
        int sizeX = Math.abs(pos2.getX() - pos1.getX()) + 1;
        int sizeZ = Math.abs(pos2.getZ() - pos1.getZ()) + 1;
        int maxSize = ClaimsConfig.CONFIG.maxClaimSize.get();
        if (maxSize > 0 && (sizeX > maxSize || sizeZ > maxSize)) {
            return null; // Too large
        }

        // Check overlap
        for (Claim existing : claims.values()) {
            if (existing.getWorld().equals(world)) {
                if (claimsOverlap(pos1, pos2, existing)) {
                    return null; // Overlaps
                }
            }
        }

        // Check player claim limit
        int maxClaims = ClaimsConfig.CONFIG.maxClaimsPerPlayer.get();
        if (maxClaims > 0) {
            long playerClaims = claims.values().stream()
                    .filter(c -> c.getOwner().equals(owner))
                    .count();
            if (playerClaims >= maxClaims) {
                return null; // Limit reached
            }
        }

        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            String sql = """
                    INSERT INTO vonixcore_claims
                    (owner, owner_name, world, x1, y1, z1, x2, y2, z2, trusted, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, owner.toString());
                ps.setString(2, ownerName);
                ps.setString(3, world);
                ps.setInt(4, Math.min(pos1.getX(), pos2.getX()));
                ps.setInt(5, Math.min(pos1.getY(), pos2.getY()));
                ps.setInt(6, Math.min(pos1.getZ(), pos2.getZ()));
                ps.setInt(7, Math.max(pos1.getX(), pos2.getX()));
                ps.setInt(8, Math.max(pos1.getY(), pos2.getY()));
                ps.setInt(9, Math.max(pos1.getZ(), pos2.getZ()));
                ps.setString(10, "[]");
                ps.setLong(11, System.currentTimeMillis());
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        int id = rs.getInt(1);
                        Claim claim = new Claim(id, owner, ownerName, world,
                                pos1.getX(), pos1.getY(), pos1.getZ(),
                                pos2.getX(), pos2.getY(), pos2.getZ(),
                                new HashSet<>(), System.currentTimeMillis());
                        claims.put(id, claim);
                        return claim;
                    }
                }
            }
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to create claim: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Delete a claim
     */
    public boolean deleteClaim(int claimId) {
        if (!claims.containsKey(claimId))
            return false;

        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            String sql = "DELETE FROM vonixcore_claims WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, claimId);
                ps.executeUpdate();
            }
            claims.remove(claimId);
            return true;
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to delete claim: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Get claim at a position
     */
    public Claim getClaimAt(String world, BlockPos pos) {
        for (Claim claim : claims.values()) {
            if (claim.contains(world, pos)) {
                return claim;
            }
        }
        return null;
    }

    /**
     * Get all claims owned by a player
     */
    public List<Claim> getPlayerClaims(UUID owner) {
        List<Claim> result = new ArrayList<>();
        for (Claim claim : claims.values()) {
            if (claim.getOwner().equals(owner)) {
                result.add(claim);
            }
        }
        return result;
    }

    /**
     * Add trusted player to claim
     */
    public boolean trustPlayer(int claimId, UUID player) {
        Claim claim = claims.get(claimId);
        if (claim == null)
            return false;

        claim.addTrusted(player);
        saveTrusted(claimId, claim.getTrusted());
        return true;
    }

    /**
     * Remove trusted player from claim
     */
    public boolean untrustPlayer(int claimId, UUID player) {
        Claim claim = claims.get(claimId);
        if (claim == null)
            return false;

        claim.removeTrusted(player);
        saveTrusted(claimId, claim.getTrusted());
        return true;
    }

    /**
     * Check if player can build at position
     */
    public boolean canBuild(UUID player, String world, BlockPos pos) {
        Claim claim = getClaimAt(world, pos);
        if (claim == null)
            return true; // No claim = can build
        return claim.canInteract(player);
    }

    /**
     * Check if player can interact at position
     */
    public boolean canInteract(UUID player, String world, BlockPos pos) {
        Claim claim = getClaimAt(world, pos);
        if (claim == null)
            return true; // No claim = can interact
        return claim.canInteract(player);
    }

    // Selection management
    public void setCorner1(UUID player, BlockPos pos) {
        corner1Selections.put(player, pos);
    }

    public void setCorner2(UUID player, BlockPos pos) {
        corner2Selections.put(player, pos);
    }

    public BlockPos getCorner1(UUID player) {
        return corner1Selections.get(player);
    }

    public BlockPos getCorner2(UUID player) {
        return corner2Selections.get(player);
    }

    public void clearSelection(UUID player) {
        corner1Selections.remove(player);
        corner2Selections.remove(player);
    }

    public boolean hasSelection(UUID player) {
        return corner1Selections.containsKey(player) && corner2Selections.containsKey(player);
    }

    // Helper methods
    private boolean claimsOverlap(BlockPos pos1, BlockPos pos2, Claim existing) {
        int minX = Math.min(pos1.getX(), pos2.getX());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        return !(maxX < existing.getX1() || minX > existing.getX2() ||
                maxZ < existing.getZ1() || minZ > existing.getZ2());
    }

    private Set<UUID> parseTrusted(String json) {
        Set<UUID> trusted = new HashSet<>();
        if (json == null || json.isEmpty() || json.equals("[]"))
            return trusted;
        // Simple JSON array parsing
        json = json.replace("[", "").replace("]", "").replace("\"", "");
        for (String part : json.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                try {
                    trusted.add(UUID.fromString(trimmed));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return trusted;
    }

    private void saveTrusted(int claimId, Set<UUID> trusted) {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (UUID uuid : trusted) {
            if (!first)
                json.append(",");
            json.append("\"").append(uuid.toString()).append("\"");
            first = false;
        }
        json.append("]");

        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            String sql = "UPDATE vonixcore_claims SET trusted = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, json.toString());
                ps.setInt(2, claimId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to save trusted: {}", e.getMessage());
        }
    }

    /**
     * Get total claim count
     */
    public int getClaimCount() {
        return claims.size();
    }

    /**
     * Get claim by ID
     */
    public Claim getClaim(int id) {
        return claims.get(id);
    }
}
