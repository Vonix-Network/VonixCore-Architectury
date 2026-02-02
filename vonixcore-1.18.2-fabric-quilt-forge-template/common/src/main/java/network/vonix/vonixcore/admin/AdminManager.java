package network.vonix.vonixcore.admin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.GameType;
import network.vonix.vonixcore.VonixCore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Manages admin features - bans, mutes, vanish, god mode, etc.
 */
public class AdminManager {

    private static AdminManager instance;

    // In-memory states (cleared on restart)
    private final Set<UUID> vanishedPlayers = new HashSet<>();
    private final Set<UUID> godModePlayers = new HashSet<>();
    private final Set<UUID> flyingPlayers = new HashSet<>();
    private final Map<UUID, UUID> replyTargets = new HashMap<>();

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
                        banned_by TEXT NOT NULL,
                        reason TEXT,
                        expires_at INTEGER,
                        created_at INTEGER NOT NULL
                    )
                """);

        // Mutes table
        conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS vc_mutes (
                        uuid TEXT PRIMARY KEY,
                        muted_by TEXT NOT NULL,
                        reason TEXT,
                        expires_at INTEGER,
                        created_at INTEGER NOT NULL
                    )
                """);
    }

    // ===== Ban Management =====

    public boolean banPlayer(UUID uuid, String bannedBy, String reason, Long expiresAt) {
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO vc_bans (uuid, banned_by, reason, expires_at, created_at) VALUES (?, ?, ?, ?, ?)");
            stmt.setString(1, uuid.toString());
            stmt.setString(2, bannedBy);
            stmt.setString(3, reason);
            stmt.setObject(4, expiresAt);
            stmt.setLong(5, System.currentTimeMillis() / 1000L);
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to ban player: {}", e.getMessage());
            return false;
        }
    }

    public boolean unbanPlayer(UUID uuid) {
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("DELETE FROM vc_bans WHERE uuid = ?");
            stmt.setString(1, uuid.toString());
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to unban player: {}", e.getMessage());
            return false;
        }
    }

    public BanInfo getBan(UUID uuid) {
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT banned_by, reason, expires_at, created_at FROM vc_bans WHERE uuid = ?");
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                Long expiresAt = rs.getObject("expires_at") != null ? rs.getLong("expires_at") : null;
                // Check if expired
                if (expiresAt != null && expiresAt < System.currentTimeMillis() / 1000L) {
                    unbanPlayer(uuid);
                    return null;
                }
                return new BanInfo(
                        rs.getString("banned_by"),
                        rs.getString("reason"),
                        expiresAt,
                        rs.getLong("created_at"));
            }
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to check ban: {}", e.getMessage());
        }
        return null;
    }

    public boolean isBanned(UUID uuid) {
        return getBan(uuid) != null;
    }

    // ===== Mute Management =====

    public boolean mutePlayer(UUID uuid, String mutedBy, String reason, Long expiresAt) {
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO vc_mutes (uuid, muted_by, reason, expires_at, created_at) VALUES (?, ?, ?, ?, ?)");
            stmt.setString(1, uuid.toString());
            stmt.setString(2, mutedBy);
            stmt.setString(3, reason);
            stmt.setObject(4, expiresAt);
            stmt.setLong(5, System.currentTimeMillis() / 1000L);
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to mute player: {}", e.getMessage());
            return false;
        }
    }

    public boolean unmutePlayer(UUID uuid) {
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("DELETE FROM vc_mutes WHERE uuid = ?");
            stmt.setString(1, uuid.toString());
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to unmute player: {}", e.getMessage());
            return false;
        }
    }

    public boolean isMuted(UUID uuid) {
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT expires_at FROM vc_mutes WHERE uuid = ?");
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                Long expiresAt = rs.getObject("expires_at") != null ? rs.getLong("expires_at") : null;
                if (expiresAt != null && expiresAt < System.currentTimeMillis() / 1000L) {
                    unmutePlayer(uuid);
                    return false;
                }
                return true;
            }
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to check mute: {}", e.getMessage());
        }
        return false;
    }

    // ===== Vanish =====

    public void toggleVanish(ServerPlayer player, MinecraftServer server) {
        UUID uuid = player.getUUID();
        if (vanishedPlayers.contains(uuid)) {
            vanishedPlayers.remove(uuid);
            // Show player to others
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (!other.equals(player)) {
                    other.connection.send(new net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket(
                            net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket.Action.ADD_PLAYER,
                            player));
                }
            }
            player.sendMessage(new TextComponent("§a[VC] You are now visible."), uuid);
        } else {
            vanishedPlayers.add(uuid);
            // Hide player from others
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (!other.equals(player) && !other.hasPermissions(2)) {
                    other.connection.send(
                            new net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket(
                                    net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket.Action.REMOVE_PLAYER,
                                    player));
                }
            }
            player.sendMessage(new TextComponent("§a[VC] You are now vanished."), uuid);
        }
    }

    public boolean isVanished(UUID uuid) {
        return vanishedPlayers.contains(uuid);
    }

    // ===== God Mode =====

    public void toggleGodMode(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (godModePlayers.contains(uuid)) {
            godModePlayers.remove(uuid);
            player.setInvulnerable(false);
            player.sendMessage(new TextComponent("§c[VC] God mode disabled."), uuid);
        } else {
            godModePlayers.add(uuid);
            player.setInvulnerable(true);
            player.sendMessage(new TextComponent("§a[VC] God mode enabled."), uuid);
        }
    }

    public boolean isGodMode(UUID uuid) {
        return godModePlayers.contains(uuid);
    }

    // ===== Fly Mode =====

    public void toggleFly(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (flyingPlayers.contains(uuid)) {
            flyingPlayers.remove(uuid);
            player.getAbilities().mayfly = player.gameMode.getGameModeForPlayer() == GameType.CREATIVE;
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
            player.sendMessage(new TextComponent("§c[VC] Fly mode disabled."), uuid);
        } else {
            flyingPlayers.add(uuid);
            player.getAbilities().mayfly = true;
            player.onUpdateAbilities();
            player.sendMessage(new TextComponent("§a[VC] Fly mode enabled."), uuid);
        }
    }

    // ===== Heal & Feed =====

    public void healPlayer(ServerPlayer player) {
        player.setHealth(player.getMaxHealth());
        player.removeAllEffects();
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
    }

    public void feedPlayer(ServerPlayer player) {
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(20.0f);
    }

    // ===== Private Messaging =====

    public void setReplyTarget(UUID sender, UUID target) {
        replyTargets.put(sender, target);
    }

    public UUID getReplyTarget(UUID sender) {
        return replyTargets.get(sender);
    }

    /**
     * Ban info record.
     */
    public record BanInfo(String bannedBy, String reason, Long expiresAt, long createdAt) {
        public boolean isPermanent() {
            return expiresAt == null;
        }
    }
}