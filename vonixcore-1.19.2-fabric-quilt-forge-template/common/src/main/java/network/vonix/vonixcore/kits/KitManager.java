package network.vonix.vonixcore.kits;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import network.vonix.vonixcore.VonixCore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Manages kits - predefined item sets players can claim with cooldowns.
 */
public class KitManager {

    private static KitManager instance;

    // Kit definitions (loaded from config or defaults)
    private final Map<String, Kit> kits = new HashMap<>();

    public static KitManager getInstance() {
        if (instance == null) {
            instance = new KitManager();
        }
        return instance;
    }

    /**
     * Initialize kits table in database.
     */
    public void initializeTable(Connection conn) throws SQLException {
        conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS vc_kit_cooldowns (
                        uuid TEXT NOT NULL,
                        kit_name TEXT NOT NULL,
                        last_used INTEGER NOT NULL,
                        PRIMARY KEY (uuid, kit_name)
                    )
                """);
    }

    /**
     * Load default kits.
     */
    public void loadDefaultKits() {
        // Starter kit
        kits.put("starter", new Kit("starter",
                List.of(
                        new KitItem("minecraft:stone_sword", 1),
                        new KitItem("minecraft:stone_pickaxe", 1),
                        new KitItem("minecraft:stone_axe", 1),
                        new KitItem("minecraft:bread", 16),
                        new KitItem("minecraft:torch", 32)),
                3600, // 1 hour cooldown
                false // not one-time
        ));

        // Tools kit
        kits.put("tools", new Kit("tools",
                List.of(
                        new KitItem("minecraft:iron_pickaxe", 1),
                        new KitItem("minecraft:iron_axe", 1),
                        new KitItem("minecraft:iron_shovel", 1),
                        new KitItem("minecraft:iron_hoe", 1)),
                7200, // 2 hour cooldown
                false));

        // Food kit
        kits.put("food", new Kit("food",
                List.of(
                        new KitItem("minecraft:cooked_beef", 32),
                        new KitItem("minecraft:golden_apple", 2),
                        new KitItem("minecraft:cake", 1)),
                1800, // 30 min cooldown
                false));
    }

    /**
     * Give a kit to a player.
     */
    public KitResult giveKit(ServerPlayer player, String kitName) {
        Kit kit = kits.get(kitName.toLowerCase());
        if (kit == null) {
            return KitResult.NOT_FOUND;
        }

        UUID uuid = player.getUUID();

        // Check cooldown
        long lastUsed = getLastUsed(uuid, kitName);
        long now = System.currentTimeMillis() / 1000L;
        long remaining = (lastUsed + kit.cooldownSeconds()) - now;

        if (remaining > 0) {
            return KitResult.ON_COOLDOWN;
        }

        // Check if one-time kit already claimed
        if (kit.oneTime() && lastUsed > 0) {
            return KitResult.ALREADY_CLAIMED;
        }

        // Give items
        for (KitItem item : kit.items()) {
            ItemStack stack = createItemStack(item.itemId(), item.count());
            if (!stack.isEmpty()) {
                if (!player.getInventory().add(stack)) {
                    // Drop on ground if inventory full
                    player.drop(stack, false);
                }
            }
        }

        // Set cooldown
        setLastUsed(uuid, kitName, now);

        return KitResult.SUCCESS;
    }

    /**
     * Get remaining cooldown in seconds.
     */
    public int getRemainingCooldown(UUID uuid, String kitName) {
        Kit kit = kits.get(kitName.toLowerCase());
        if (kit == null)
            return 0;

        long lastUsed = getLastUsed(uuid, kitName);
        long now = System.currentTimeMillis() / 1000L;
        long remaining = (lastUsed + kit.cooldownSeconds()) - now;

        return Math.max(0, (int) remaining);
    }

    private long getLastUsed(UUID uuid, String kitName) {
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT last_used FROM vc_kit_cooldowns WHERE uuid = ? AND kit_name = ?");
            stmt.setString(1, uuid.toString());
            stmt.setString(2, kitName.toLowerCase());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getLong("last_used");
            }
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to get kit cooldown: {}", e.getMessage());
        }
        return 0;
    }

    private void setLastUsed(UUID uuid, String kitName, long time) {
        try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO vc_kit_cooldowns (uuid, kit_name, last_used) VALUES (?, ?, ?)");
            stmt.setString(1, uuid.toString());
            stmt.setString(2, kitName.toLowerCase());
            stmt.setLong(3, time);
            stmt.executeUpdate();
        } catch (SQLException e) {
            VonixCore.LOGGER.error("[VonixCore] Failed to set kit cooldown: {}", e.getMessage());
        }
    }

    /**
     * Get all available kit names.
     */
    public Set<String> getKitNames() {
        return Collections.unmodifiableSet(kits.keySet());
    }

    /**
     * Get a kit by name.
     */
    public Kit getKit(String name) {
        return kits.get(name.toLowerCase());
    }

    /**
     * Register a custom kit.
     */
    public void registerKit(Kit kit) {
        kits.put(kit.name().toLowerCase(), kit);
    }

    private ItemStack createItemStack(String itemId, int count) {
        try {
            ResourceLocation location = new ResourceLocation(itemId);
            var item = Registry.ITEM.get(location);
            if (item != Items.AIR) {
                return new ItemStack(item, count);
            }
        } catch (Exception e) {
            VonixCore.LOGGER.warn("[VonixCore] Invalid item ID: {}", itemId);
        }
        return ItemStack.EMPTY;
    }

    /**
     * Kit result enum.
     */
    public enum KitResult {
        SUCCESS,
        NOT_FOUND,
        ON_COOLDOWN,
        ALREADY_CLAIMED
    }

    /**
     * Kit definition record.
     */
    public record Kit(String name, List<KitItem> items, int cooldownSeconds, boolean oneTime) {
    }

    /**
     * Kit item record.
     */
    public record KitItem(String itemId, int count) {
    }
}
