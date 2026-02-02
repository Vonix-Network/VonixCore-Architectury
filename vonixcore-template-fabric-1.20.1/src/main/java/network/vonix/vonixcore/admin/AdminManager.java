package network.vonix.vonixcore.admin;

import network.vonix.vonixcore.VonixCore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Manages admin-related data such as bans, mutes, and warnings.
 */
public class AdminManager {

    private static AdminManager instance;

    public static AdminManager getInstance() {
        if (instance == null) {
            instance = new AdminManager();
        }
        return instance;
    }

    /**
     * Initialize admin tables in database.
     */
    public void initializeTable(Connection conn) throws SQLException {
        // Bans table
        conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS vc_bans (
                        uuid TEXT PRIMARY KEY,
                        username TEXT,
                        reason TEXT,
                        banned_by TEXT,
                        banned_at INTEGER NOT NULL,
                        expires_at INTEGER
                    )
                """);

        // Mutes table
        conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS vc_mutes (
                        uuid TEXT PRIMARY KEY,
                        username TEXT,
                        reason TEXT,
                        muted_by TEXT,
                        muted_at INTEGER NOT NULL,
                        expires_at INTEGER
                    )
                """);

        // Warnings table
        conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS vc_warnings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL,
                        username TEXT,
                        reason TEXT,
                        warned_by TEXT,
                        warned_at INTEGER NOT NULL
                    )
                """);

        conn.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_warnings_uuid ON vc_warnings (uuid)");
    }

    public boolean isBanned(UUID uuid) {
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT expires_at FROM vc_bans WHERE uuid = ?");
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                long expires = rs.getLong("expires_at");
                if (expires == 0 || expires > System.currentTimeMillis() / 1000) {
                    return true;
                }
            }
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to check ban: {}", e.getMessage());
        }
        return false;
    }

    public boolean isMuted(UUID uuid) {
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT expires_at FROM vc_mutes WHERE uuid = ?");
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                long expires = rs.getLong("expires_at");
                if (expires == 0 || expires > System.currentTimeMillis() / 1000) {
                    return true;
                }
            }
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to check mute: {}", e.getMessage());
        }
        return false;
    }
}
