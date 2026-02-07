package network.vonix.vonixcore.essentials.warps;

import net.minecraft.server.level.ServerPlayer;
import network.vonix.vonixcore.VonixCore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages server warps - named teleport locations accessible to all players.
 */
public class WarpManager {

    private static WarpManager instance;

    public static WarpManager getInstance() {
        if (instance == null) {
            instance = new WarpManager();
        }
        return instance;
    }

    /**
     * Initialize warps table in database.
     */
    public void initializeTable(Connection conn) throws SQLException {
        conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS vc_warps (
                        name TEXT PRIMARY KEY,
                        world TEXT NOT NULL,
                        x DOUBLE NOT NULL,
                        y DOUBLE NOT NULL,
                        z DOUBLE NOT NULL,
                        yaw REAL NOT NULL,
                        pitch REAL NOT NULL,
                        created_by TEXT,
                        created_at INTEGER NOT NULL
                    )
                """);
    }

    /**
     * Create or update a warp.
     */
    public boolean setWarp(String name, ServerPlayer player) {
        String world = player.getLevel().dimension().location().toString();
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYRot();
        float pitch = player.getXRot();

        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO vc_warps (name, world, x, y, z, yaw, pitch, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
            stmt.setString(1, name.toLowerCase());
            stmt.setString(2, world);
            stmt.setDouble(3, x);
            stmt.setDouble(4, y);
            stmt.setDouble(5, z);
            stmt.setFloat(6, yaw);
            stmt.setFloat(7, pitch);
            stmt.setString(8, player.getUUID().toString());
            stmt.setLong(9, System.currentTimeMillis() / 1000L);
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to set warp: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Delete a warp.
     */
    public boolean deleteWarp(String name) {
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("DELETE FROM vc_warps WHERE name = ?");
            stmt.setString(1, name.toLowerCase());
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to delete warp: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get a warp location.
     */
    public Warp getWarp(String name) {
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT world, x, y, z, yaw, pitch FROM vc_warps WHERE name = ?");
            stmt.setString(1, name.toLowerCase());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return new Warp(
                        name,
                        rs.getString("world"),
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("z"),
                        rs.getFloat("yaw"),
                        rs.getFloat("pitch"));
            }
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to get warp: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Get all warps.
     */
    public List<Warp> getWarps() {
        List<Warp> warps = new ArrayList<>();
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT name, world, x, y, z, yaw, pitch FROM vc_warps ORDER BY name");
            while (rs.next()) {
                warps.add(new Warp(
                        rs.getString("name"),
                        rs.getString("world"),
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("z"),
                        rs.getFloat("yaw"),
                        rs.getFloat("pitch")));
            }
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to list warps: {}", e.getMessage());
        }
        return warps;
    }

    /**
     * Warp data class.
     */
    public record Warp(String name, String world, double x, double y, double z, float yaw, float pitch) {
    }
}
