package network.vonix.vonixcore.xpsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.storage.LevelResource;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.XPSyncConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Optional;

/**
 * XPSyncManager - Handles syncing player XP data to the Vonix Network website.
 * Fabric 1.20.1 Implementation
 */
public class XPSyncManager {

    private static XPSyncManager instance;

    private final MinecraftServer server;
    private final Gson gson;
    private ScheduledExecutorService scheduler;

    // High-water mark tracking: stores the maximum XP ever seen per player UUID
    private static final ConcurrentHashMap<UUID, Integer> highWaterMarkXp = new ConcurrentHashMap<>();

    // Timeouts in milliseconds
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 30000;
    private static final int SHUTDOWN_READ_TIMEOUT = 10000;

    public XPSyncManager(MinecraftServer server) {
        instance = this;
        this.server = server;
        this.gson = new GsonBuilder().setLenient().create();
    }

    public static XPSyncManager getInstance() {
        return instance;
    }

    public void start() {
        XPSyncConfig config = XPSyncConfig.getInstance();
        if (!config.isEnabled()) {
            VonixCore.LOGGER.info("[XPSync] Disabled in config");
            return;
        }

        int intervalSeconds = config.getSyncInterval();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VonixCore-XPSync-Thread");
            t.setDaemon(true);
            return t;
        });

        // Sync ALL players from world data on startup
        VonixCore.LOGGER.info("[XPSync] Running startup sync for ALL players from world data...");
        scheduler.execute(this::syncAllPlayersFromWorldData);

        // Schedule regular syncs for online players only
        scheduler.scheduleAtFixedRate(
                this::syncOnlinePlayers,
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS);

        VonixCore.LOGGER.info("[XPSync] Started with {}s interval", intervalSeconds);
    }

    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            VonixCore.LOGGER.info("[XPSync] Stopping service...");

            // Sync ALL players from world data before shutdown
            VonixCore.LOGGER.info("[XPSync] Running final sync for ALL players...");
            syncAllPlayersFromWorldDataSync();

            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    VonixCore.LOGGER.warn("[XPSync] Scheduler did not terminate in time, forcing shutdown...");
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }

            VonixCore.LOGGER.info("[XPSync] Stopped.");
            instance = null;
        }
    }

    private void syncAllPlayersFromWorldData() {
        try {
            List<JsonObject> allPlayers = getAllPlayersFromWorldData();

            if (allPlayers.isEmpty()) {
                VonixCore.LOGGER.info("[XPSync] No player data found in world files");
                return;
            }

            VonixCore.LOGGER.info("[XPSync] Found {} players in world data, syncing to API...", allPlayers.size());

            int batchSize = 50;
            for (int i = 0; i < allPlayers.size(); i += batchSize) {
                int end = Math.min(i + batchSize, allPlayers.size());
                List<JsonObject> batch = allPlayers.subList(i, end);

                JsonObject payload = new JsonObject();
                payload.addProperty("serverName", XPSyncConfig.getInstance().getServerName());
                JsonArray playersArray = new JsonArray();
                batch.forEach(playersArray::add);
                payload.add("players", playersArray);
                payload.addProperty("_playerCount", batch.size());

                VonixCore.LOGGER.info("[XPSync] Syncing batch {}-{} of {} players...", i + 1, end, allPlayers.size());
                sendToAPI(payload, false);

                if (end < allPlayers.size()) {
                    Thread.sleep(500);
                }
            }

            VonixCore.LOGGER.info("[XPSync] Completed syncing all {} players from world data", allPlayers.size());

        } catch (Exception e) {
            VonixCore.LOGGER.error("[XPSync] Error syncing all player data from world files", e);
        }
    }

    private void syncAllPlayersFromWorldDataSync() {
        try {
            List<JsonObject> allPlayers = getAllPlayersFromWorldData();

            if (allPlayers.isEmpty()) {
                return;
            }

            VonixCore.LOGGER.info("[XPSync] Final sync: {} players from world data", allPlayers.size());

            JsonObject payload = new JsonObject();
            payload.addProperty("serverName", XPSyncConfig.getInstance().getServerName());
            JsonArray playersArray = new JsonArray();
            allPlayers.forEach(playersArray::add);
            payload.add("players", playersArray);
            payload.addProperty("_playerCount", allPlayers.size());

            sendToAPI(payload, true);

        } catch (Exception e) {
            VonixCore.LOGGER.error("[XPSync] Error during final sync", e);
        }
    }

    private List<JsonObject> getAllPlayersFromWorldData() {
        List<JsonObject> players = new ArrayList<>();

        try {
            Path worldPath = server.getWorldPath(LevelResource.ROOT);
            Path playerDataPath = worldPath.resolve("playerdata");
            Path statsPath = worldPath.resolve("stats");

            if (!Files.exists(playerDataPath)) {
                return players;
            }

            List<String> onlineUuids = new ArrayList<>();

            // Handle case where server player list is not available (early startup)
            if (server.getPlayerList() == null) {
                VonixCore.LOGGER.warn("[XPSync] Player list not available yet, skipping online player sync");
            } else {
                List<ServerPlayer> online = server.getPlayerList().getPlayers();
                for (ServerPlayer p : online) {
                    players.add(buildPlayerDataFromOnline(p));
                    onlineUuids.add(p.getUUID().toString());
                }
            }

            File[] datFiles = playerDataPath.toFile().listFiles((dir, name) -> name.endsWith(".dat"));

            if (datFiles == null)
                return players;

            for (File datFile : datFiles) {
                try {
                    String fileName = datFile.getName();
                    String uuidStr = fileName.replace(".dat", "");

                    if (onlineUuids.contains(uuidStr))
                        continue;

                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidStr);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }

                    CompoundTag nbt;
                    try (java.io.InputStream is = Files.newInputStream(datFile.toPath())) {
                        nbt = NbtIo.readCompressed(is);
                    } catch (Exception e) {
                        try {
                            nbt = NbtIo.readCompressed(datFile);
                        } catch (Exception e2) {
                            continue;
                        }
                    }

                    if (nbt == null)
                        continue;

                    JsonObject playerData = new JsonObject();
                    playerData.addProperty("uuid", uuid.toString());

                    int xpLevel = nbt.getInt("XpLevel");
                    float xpProgress = nbt.getFloat("XpP");
                    int xpTotal = nbt.getInt("XpTotal");

                    if (xpTotal == 0 && xpLevel > 0) {
                        xpTotal = calculateTotalXP(xpLevel, xpProgress);
                    }

                    int storedMax = highWaterMarkXp.getOrDefault(uuid, 0);
                    if (xpTotal > storedMax) {
                        highWaterMarkXp.put(uuid, xpTotal);
                    } else {
                        xpTotal = storedMax;
                    }

                    playerData.addProperty("level", xpLevel);
                    playerData.addProperty("totalExperience", xpTotal);

                    String username = getPlayerUsername(uuid);
                    if (username != null) {
                        playerData.addProperty("username", username);
                    } else {
                        playerData.addProperty("username", uuidStr);
                    }

                    if (XPSyncConfig.getInstance().isTrackPlaytime()) {
                        int playtime = getPlaytimeFromStats(uuid, statsPath);
                        playerData.addProperty("playtimeSeconds", Math.max(0, playtime));
                    }

                    players.add(playerData);

                } catch (Exception e) {
                    // Ignore specific file errors
                }
            }

        } catch (Exception e) {
            VonixCore.LOGGER.error("[XPSync] Error reading player data", e);
        }

        return players;
    }

    private JsonObject buildPlayerDataFromOnline(ServerPlayer player) {
        JsonObject playerData = new JsonObject();
        playerData.addProperty("uuid", player.getUUID().toString());
        playerData.addProperty("username", player.getName().getString());
        playerData.addProperty("level", player.experienceLevel);
        playerData.addProperty("totalExperience", getTotalExperience(player));

        if (XPSyncConfig.getInstance().isTrackHealth()) {
            playerData.addProperty("currentHealth", player.getHealth());
        }

        if (XPSyncConfig.getInstance().isTrackPlaytime()) {
            try {
                int playTimeTicks = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
                int playTimeSeconds = Math.max(0, playTimeTicks / 20);
                playerData.addProperty("playtimeSeconds", playTimeSeconds);
            } catch (Exception e) {
                playerData.addProperty("playtimeSeconds", 0);
            }
        }

        return playerData;
    }

    private String getPlayerUsername(UUID uuid) {
        try {
            var profile = server.getProfileCache();
            if (profile != null) {
                var optional = profile.get(uuid);
                if (optional.isPresent()) {
                    return optional.get().getName();
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    private int getPlaytimeFromStats(UUID uuid, Path statsPath) {
        try {
            Path statsFile = statsPath.resolve(uuid.toString() + ".json");
            if (!Files.exists(statsFile))
                return 0;

            String content = Files.readString(statsFile);
            JsonObject stats = JsonParser.parseString(content).getAsJsonObject();

            if (stats.has("stats")) {
                JsonObject statsObj = stats.getAsJsonObject("stats");
                if (statsObj.has("minecraft:custom")) {
                    JsonObject custom = statsObj.getAsJsonObject("minecraft:custom");
                    if (custom.has("minecraft:play_time")) {
                        return custom.get("minecraft:play_time").getAsInt() / 20;
                    }
                }
            }
        } catch (Exception e) {
        }
        return 0;
    }

    private int calculateTotalXP(int level, float progress) {
        int totalXP = 0;
        if (level >= 32) {
            totalXP = (int) (4.5 * level * level - 162.5 * level + 2220);
        } else if (level >= 17) {
            totalXP = (int) (2.5 * level * level - 40.5 * level + 360);
        } else {
            totalXP = level * level + 6 * level;
        }

        int xpForNextLevel;
        if (level >= 31) {
            xpForNextLevel = 9 * level - 158;
        } else if (level >= 16) {
            xpForNextLevel = 5 * level - 38;
        } else {
            xpForNextLevel = 2 * level + 7;
        }

        totalXP += Math.round(progress * xpForNextLevel);
        return totalXP;
    }

    private void syncOnlinePlayers() {
        try {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players.isEmpty())
                return;

            JsonObject payload = buildPayload(players);
            sendToAPI(payload, false);
        } catch (Exception e) {
            VonixCore.LOGGER.error("[XPSync] Error syncing online", e);
        }
    }

    private JsonObject buildPayload(List<ServerPlayer> players) {
        JsonObject root = new JsonObject();
        root.addProperty("serverName", XPSyncConfig.getInstance().getServerName());

        JsonArray playersArray = new JsonArray();
        for (ServerPlayer player : players) {
            playersArray.add(buildPlayerDataFromOnline(player));
        }
        root.add("players", playersArray);
        root.addProperty("_playerCount", players.size());

        return root;
    }

    private int getTotalExperience(ServerPlayer player) {
        int level = player.experienceLevel;
        int currentXP = calculateTotalXP(level, player.experienceProgress);

        UUID playerUuid = player.getUUID();
        int storedMax = highWaterMarkXp.getOrDefault(playerUuid, 0);

        if (currentXP > storedMax) {
            highWaterMarkXp.put(playerUuid, currentXP);
            return currentXP;
        }

        return storedMax;
    }

    private String readResponse(HttpURLConnection conn, int statusCode) throws IOException {
        var stream = statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null)
            return "";

        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(256);
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private void sendToAPI(JsonObject payload, boolean isShutdown) {
        int maxRetries = XPSyncConfig.getInstance().getMaxRetries();
        int attempt = 0;
        while (attempt < maxRetries) {
            attempt++;
            if (trySendToAPI(payload, isShutdown, attempt)) {
                return;
            }
            if (attempt < maxRetries && !isShutdown) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        VonixCore.LOGGER.error("[XPSync] Failed to sync after {} attempts", maxRetries);
    }

    private boolean trySendToAPI(JsonObject payload, boolean isShutdown, int attempt) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(XPSyncConfig.getInstance().getApiEndpoint());
            conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + XPSyncConfig.getInstance().getApiKey());
            conn.setRequestProperty("User-Agent", "VonixCore-XPSync-Fabric/1.0.0");
            conn.setConnectTimeout(XPSyncConfig.getInstance().getConnectionTimeout());
            conn.setReadTimeout(isShutdown ? SHUTDOWN_READ_TIMEOUT : READ_TIMEOUT);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(gson.toJson(payload).getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int statusCode = conn.getResponseCode();
            String responseBody = readResponse(conn, statusCode);

            if (statusCode == 200) {
                int expectedCount = payload.has("_playerCount") ? payload.get("_playerCount").getAsInt() : 0;
                handleSuccessResponse(responseBody, expectedCount);
                return true;
            } else if (statusCode == 401 || statusCode == 403) {
                VonixCore.LOGGER.error("[XPSync] Auth failed: HTTP {}", statusCode);
                return true;
            } else if (statusCode >= 500) {
                if (XPSyncConfig.getInstance().isVerboseLogging()) {
                    VonixCore.LOGGER.warn("[XPSync] Server error: {}", responseBody);
                }
                return false;
            } else {
                VonixCore.LOGGER.error("[XPSync] HTTP {}", statusCode);
                return true;
            }

        } catch (Exception e) {
            if (XPSyncConfig.getInstance().isVerboseLogging()) {
                VonixCore.LOGGER.warn("[XPSync] Connection error: {}", e.getMessage());
            }
            return false;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    private void handleSuccessResponse(String responseBody, int expectedCount) {
        if (responseBody == null || responseBody.isEmpty())
            return;

        try {
            JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
            if (response.has("success") && response.get("success").getAsBoolean()) {
                int synced = response.has("syncedCount") ? response.get("syncedCount").getAsInt() : 0;
                if (synced == expectedCount) {
                    VonixCore.LOGGER.info("[XPSync] Synced {} players", synced);
                } else {
                    VonixCore.LOGGER.warn("[XPSync] Partial sync: {}/{}", synced, expectedCount);
                }
            }
        } catch (Exception e) {
            VonixCore.LOGGER.error("[XPSync] Invalid response", e);
        }
    }
}
