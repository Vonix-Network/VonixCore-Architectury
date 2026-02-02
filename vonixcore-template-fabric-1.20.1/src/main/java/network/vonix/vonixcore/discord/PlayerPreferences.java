package network.vonix.vonixcore.discord;

import network.vonix.vonixcore.VonixCore;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player preferences for Discord messages.
 */
public class PlayerPreferences {

    private final Path dataFile;
    private final Set<UUID> serverMessagesFiltered = ConcurrentHashMap.newKeySet();
    private final Set<UUID> eventsFiltered = ConcurrentHashMap.newKeySet();

    public PlayerPreferences(Path configDir) throws IOException {
        this.dataFile = configDir.resolve("vonixcore-player-prefs.yml");
        load();
    }

    private void load() {
        if (!Files.exists(dataFile)) {
            return;
        }

        try {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(dataFile));
            if (data == null) return;

            @SuppressWarnings("unchecked")
            List<String> serverFiltered = (List<String>) data.get("serverMessagesFiltered");
            if (serverFiltered != null) {
                for (String uuid : serverFiltered) {
                    serverMessagesFiltered.add(UUID.fromString(uuid));
                }
            }

            @SuppressWarnings("unchecked")
            List<String> eventsFiltered = (List<String>) data.get("eventsFiltered");
            if (eventsFiltered != null) {
                for (String uuid : eventsFiltered) {
                    this.eventsFiltered.add(UUID.fromString(uuid));
                }
            }
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Failed to load player preferences: {}", e.getMessage());
        }
    }

    private void save() {
        try {
            Map<String, Object> data = new HashMap<>();
            
            List<String> serverFiltered = new ArrayList<>();
            for (UUID uuid : serverMessagesFiltered) {
                serverFiltered.add(uuid.toString());
            }
            data.put("serverMessagesFiltered", serverFiltered);

            List<String> eventsFiltered = new ArrayList<>();
            for (UUID uuid : this.eventsFiltered) {
                eventsFiltered.add(uuid.toString());
            }
            data.put("eventsFiltered", eventsFiltered);

            Yaml yaml = new Yaml();
            Files.writeString(dataFile, yaml.dump(data));
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Failed to save player preferences: {}", e.getMessage());
        }
    }

    public boolean hasServerMessagesFiltered(UUID uuid) {
        return serverMessagesFiltered.contains(uuid);
    }

    public void setServerMessagesFiltered(UUID uuid, boolean filtered) {
        if (filtered) {
            serverMessagesFiltered.add(uuid);
        } else {
            serverMessagesFiltered.remove(uuid);
        }
        save();
    }

    public boolean hasEventsFiltered(UUID uuid) {
        return eventsFiltered.contains(uuid);
    }

    public void setEventsFiltered(UUID uuid, boolean filtered) {
        if (filtered) {
            eventsFiltered.add(uuid);
        } else {
            eventsFiltered.remove(uuid);
        }
        save();
    }
}
