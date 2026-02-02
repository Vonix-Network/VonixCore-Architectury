package network.vonix.vonixcore.kits;

import network.vonix.vonixcore.VonixCore;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * Manages kits - predefined item sets that players can claim.
 */
public class KitManager {

    private static KitManager instance;
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
        kits.put("starter", new Kit("starter", 86400, Arrays.asList(
                new KitItem("minecraft:wooden_sword", 1),
                new KitItem("minecraft:wooden_pickaxe", 1),
                new KitItem("minecraft:bread", 16))));

        VonixCore.LOGGER.info("[VonixCore] Loaded {} default kits", kits.size());
    }

    public Kit getKit(String name) {
        return kits.get(name.toLowerCase());
    }

    public Collection<Kit> getKits() {
        return kits.values();
    }

    public record Kit(String name, int cooldownSeconds, List<KitItem> items) {
    }

    public record KitItem(String itemId, int amount) {
    }
}
