package network.vonix.vonixcore.homes;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.vonixcore.VonixCore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manages player homes - set, delete, and teleport to saved locations.
 */
public class HomeManager {

    private static HomeManager instance;

    public static HomeManager getInstance() {
        if (instance == null) {
            instance = new HomeManager();
        }
        return instance;
    }

    /**
     * Initialize homes table in database.
     */
    public void initializeTable(Connection conn) throws SQLException {
        conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS vc_homes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL,
                        name TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x DOUBLE NOT NULL,
                        y DOUBLE NOT NULL,
                        z DOUBLE NOT NULL,
                        yaw REAL NOT NULL,
                        pitch REAL NOT NULL,
                        UNIQUE(uuid, name)
                    )
                """);
        conn.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_homes_uuid ON vc_homes (uuid)");
    }

    /**
     * Set or update a home for a player.
     */
    public boolean setHome(ServerPlayer player, String name) {
        UUID uuid = player.getUUID();
        String world = player.getLevel().dimension().location().toString();
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYRot();
        float pitch = player.getXRot();

        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            // Check home limit
            int homeCount = getHomeCount(uuid);
            int maxHomes = VonixCore.getInstance().getMaxHomes();
            if (homeCount >= maxHomes && !homeExists(uuid, name)) {
                return false; // At limit
            }

            PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO vc_homes (uuid, name, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            stmt.setString(1, uuid.toString());
            stmt.setString(2, name.toLowerCase());
            stmt.setString(3, world);
            stmt.setDouble(4, x);
            stmt.setDouble(5, y);
            stmt.setDouble(6, z);
            stmt.setFloat(7, yaw);
            stmt.setFloat(8, pitch);
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to set home: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Delete a home.
     */
    public boolean deleteHome(UUID uuid, String name) {
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM vc_homes WHERE uuid = ? AND name = ?");
            stmt.setString(1, uuid.toString());
            stmt.setString(2, name.toLowerCase());
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to delete home: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get a home location.
     */
    public Home getHome(UUID uuid, String name) {
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT world, x, y, z, yaw, pitch FROM vc_homes WHERE uuid = ? AND name = ?");
            stmt.setString(1, uuid.toString());
            stmt.setString(2, name.toLowerCase());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return new Home(
                        name,
                        rs.getString("world"),
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("z"),
                        rs.getFloat("yaw"),
                        rs.getFloat("pitch"));
            }
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to get home: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Get all homes for a player.
     */
    public List<Home> getHomes(UUID uuid) {
        List<Home> homes = new ArrayList<>();
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT name, world, x, y, z, yaw, pitch FROM vc_homes WHERE uuid = ? ORDER BY name");
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                homes.add(new Home(
                        rs.getString("name"),
                        rs.getString("world"),
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("z"),
                        rs.getFloat("yaw"),
                        rs.getFloat("pitch")));
            }
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to list homes: {}", e.getMessage());
        }
        return homes;
    }

    /**
     * Get home count for a player.
     */
    public int getHomeCount(UUID uuid) {
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM vc_homes WHERE uuid = ?");
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to count homes: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * Check if a home exists.
     */
    public boolean homeExists(UUID uuid, String name) {
        return getHome(uuid, name) != null;
    }

    /**
     * Home data class.
     */
    public record Home(String name, String world, double x, double y, double z, float yaw, float pitch) {
    }
}
