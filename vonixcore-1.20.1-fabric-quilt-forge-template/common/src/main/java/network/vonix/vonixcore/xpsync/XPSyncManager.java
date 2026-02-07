package network.vonix.vonixcore.xpsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
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

/**
 * XPSyncManager - Handles syncing player XP data to the Vonix Network website.
 * Ported from XPSync mod to VonixCore.
 */
public class XPSyncManager {

    private static XPSyncManager instance;

    private final MinecraftServer server;
    private final String apiEndpoint;
    private final String apiKey;
    private final String serverName;
    private final Gson gson;
    private ScheduledExecutorService scheduler;

    // High-water mark tracking: stores the maximum XP ever seen per player UUID
    // This ensures XP never decreases, even when players die or spend XP on
    // enchanting
    private static final ConcurrentHashMap<UUID, Integer> highWaterMarkXp = new ConcurrentHashMap<>();

    // Timeouts in milliseconds
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 30000;
    private static final int SHUTDOWN_READ_TIMEOUT = 10000;

    public static XPSyncManager getInstance() {
        return instance;
    }

    public XPSyncManager(MinecraftServer server) {
        instance = this;
        this.server = server;
        this.apiEndpoint = XPSyncConfig.CONFIG.apiEndpoint.get();
        this.apiKey = XPSyncConfig.CONFIG.apiKey.get();
        this.serverName = XPSyncConfig.CONFIG.serverName.get();
        this.gson = new GsonBuilder().setLenient().create();
    }

    public void start() {
        int intervalSeconds = XPSyncConfig.CONFIG.syncInterval.get();

        // Use a single-threaded scheduled executor with daemon threads for clean
        // shutdown
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VonixCore-XPSync-Thread");
            t.setDaemon(true); // Daemon thread won't block JVM shutdown
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

        VonixCore.LOGGER.info("[XPSync] Scheduled to run every {} seconds", intervalSeconds);
    }

    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            VonixCore.LOGGER.info("[XPSync] Stopping service...");

            // Sync ALL players from world data before shutdown - with timeout to prevent hangs
            VonixCore.LOGGER.info("[XPSync] Running final sync for ALL players...");
            try {
                // Run sync on a separate thread with timeout to prevent blocking server shutdown
                java.util.concurrent.CompletableFuture<Void> syncFuture = java.util.concurrent.CompletableFuture.runAsync(
                    this::syncAllPlayersFromWorldDataSync, 
                    VonixCore.ASYNC_EXECUTOR
                );
                // Wait max 10 seconds for final sync
                syncFuture.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                VonixCore.LOGGER.warn("[XPSync] Final sync timed out, continuing with shutdown");
            } catch (Exception e) {
                VonixCore.LOGGER.warn("[XPSync] Final sync failed: {}", e.getMessage());
            }

            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    VonixCore.LOGGER.warn("[XPSync] Scheduler did not terminate in time, forcing shutdown...");
                    scheduler.shutdownNow();
                    if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                        VonixCore.LOGGER.error("[XPSync] Scheduler could not be terminated!");
                    }
                }
            } catch (InterruptedException e) {
                VonixCore.LOGGER.warn("[XPSync] Interrupted while waiting for shutdown");
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }

            VonixCore.LOGGER.info("[XPSync] Service stopped.");
            instance = null;
        }
    }

    /**
     * Sync ALL players from world data files (startup/shutdown)
     */
    private void syncAllPlayersFromWorldData() {
        try {
            List<JsonObject> allPlayers = getAllPlayersFromWorldData();

            if (allPlayers.isEmpty()) {
                VonixCore.LOGGER.info("[XPSync] No player data found in world files");
                return;
            }

            VonixCore.LOGGER.info("[XPSync] Found {} players in world data, syncing to API...", allPlayers.size());

            // Send in batches of 50 to avoid overwhelming the API
            int batchSize = 50;
            for (int i = 0; i < allPlayers.size(); i += batchSize) {
                int end = Math.min(i + batchSize, allPlayers.size());
                List<JsonObject> batch = allPlayers.subList(i, end);

                JsonObject payload = new JsonObject();
                payload.addProperty("serverName", serverName);
                JsonArray playersArray = new JsonArray();
                batch.forEach(playersArray::add);
                payload.add("players", playersArray);

                VonixCore.LOGGER.info("[XPSync] Syncing batch {}-{} of {} players...", i + 1, end, allPlayers.size());
                sendToAPI(payload, false);

                // Small delay between batches
                if (end < allPlayers.size()) {
                    Thread.sleep(500);
                }
            }

            VonixCore.LOGGER.info("[XPSync] Completed syncing all {} players from world data", allPlayers.size());

        } catch (Exception e) {
            VonixCore.LOGGER.error("[XPSync] Error syncing all player data from world files", e);
        }
    }

    /**
     * Synchronous version for shutdown
     */
    private void syncAllPlayersFromWorldDataSync() {
        try {
            List<JsonObject> allPlayers = getAllPlayersFromWorldData();

            if (allPlayers.isEmpty()) {
                VonixCore.LOGGER.info("[XPSync] No player data found in world files for final sync");
                return;
            }

            VonixCore.LOGGER.info("[XPSync] Final sync: {} players from world data", allPlayers.size());

            JsonObject payload = new JsonObject();
            payload.addProperty("serverName", serverName);
            JsonArray playersArray = new JsonArray();
            allPlayers.forEach(playersArray::add);
            payload.add("players", playersArray);

            sendToAPI(payload, true);

        } catch (Exception e) {
            VonixCore.LOGGER.error("[XPSync] Error during final sync of all players", e);
        }
    }

    /**
     * Read all player data from world/playerdata/ folder
     */
    private List<JsonObject> getAllPlayersFromWorldData() {
        List<JsonObject> players = new ArrayList<>();

        try {
            Path worldPath = server.getWorldPath(LevelResource.ROOT);
            Path playerDataPath = worldPath.resolve("playerdata");
            Path statsPath = worldPath.resolve("stats");

            if (!Files.exists(playerDataPath)) {
                VonixCore.LOGGER.warn("[XPSync] Player data folder not found: {}", playerDataPath);
                return players;
            }

            // First, collect all online players (most accurate data)
            for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
                players.add(buildPlayerDataFromOnline(onlinePlayer));
            }

            // Get list of online UUIDs to skip
            List<String> onlineUuids = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                onlineUuids.add(p.getUUID().toString());
            }

            // Then read offline player data files
            File[] datFiles = playerDataPath.toFile().listFiles((dir, name) -> name.endsWith(".dat"));

            if (datFiles == null) {
                return players;
            }

            for (File datFile : datFiles) {
                try {
                    String fileName = datFile.getName();
                    String uuidStr = fileName.replace(".dat", "");

                    // Skip online players (already added with current data)
                    if (onlineUuids.contains(uuidStr)) {
                        continue;
                    }

                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidStr);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }

                    CompoundTag nbt = NbtIo.readCompressed(datFile);

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

                    // Use high-water mark: only use current XP if higher than stored max
                    int storedMax = highWaterMarkXp.getOrDefault(uuid, 0);
                    if (xpTotal > storedMax) {
                        highWaterMarkXp.put(uuid, xpTotal);
                    } else {
                        xpTotal = storedMax;
                    }

                    playerData.addProperty("level", xpLevel);
                    playerData.addProperty("totalExperience", xpTotal);

                    String username = getPlayerUsername(uuid, statsPath);
                    if (username != null) {
                        playerData.addProperty("username", username);
                    } else {
                        playerData.addProperty("username", uuidStr);
                    }

                    if (XPSyncConfig.CONFIG.trackPlaytime.get()) {
                        int playtime = getPlaytimeFromStats(uuid, statsPath);
                        if (playtime > 0) {
                            playerData.addProperty("playtimeSeconds", playtime);
                        }
                    }

                    players.add(playerData);

                } catch (Exception e) {
                    if (XPSyncConfig.CONFIG.verboseLogging.get()) {
                        VonixCore.LOGGER.warn("[XPSync] Failed to read player data file: {}", datFile.getName(), e);
                    }
                }
            }

        } catch (Exception e) {
            VonixCore.LOGGER.error("[XPSync] Error reading player data from world files", e);
        }

        return players;
    }

    /**
     * Build player data from an online player (most accurate)
     */
    private JsonObject buildPlayerDataFromOnline(ServerPlayer player) {
        JsonObject playerData = new JsonObject();
        playerData.addProperty("uuid", player.getUUID().toString());
        playerData.addProperty("username", player.getName().getString());
        playerData.addProperty("level", player.experienceLevel);
        playerData.addProperty("totalExperience", getTotalExperience(player));

        if (XPSyncConfig.CONFIG.trackHealth.get()) {
            playerData.addProperty("currentHealth", player.getHealth());
        }

        if (XPSyncConfig.CONFIG.trackPlaytime.get()) {
            int playTimeTicks = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
            int playTimeSeconds = playTimeTicks / 20;
            playerData.addProperty("playtimeSeconds", playTimeSeconds);
        }

        return playerData;
    }

    /**
     * Try to get player username from stats file or server cache
     */
    private String getPlayerUsername(UUID uuid, Path statsPath) {
        try {
            var profile = server.getProfileCache();
            if (profile != null) {
                var optional = profile.get(uuid);
                if (optional.isPresent()) {
                    return optional.get().getName();
                }
            }
        } catch (Exception e) {
            // Ignore
        }

        return null;
    }

    /**
     * Get playtime from stats JSON file
     */
    private int getPlaytimeFromStats(UUID uuid, Path statsPath) {
        try {
            Path statsFile = statsPath.resolve(uuid.toString() + ".json");
            if (!Files.exists(statsFile)) {
                return 0;
            }

            String content = Files.readString(statsFile);
            JsonObject stats = JsonParser.parseString(content).getAsJsonObject();

            if (stats.has("stats")) {
                JsonObject statsObj = stats.getAsJsonObject("stats");
                if (statsObj.has("minecraft:custom")) {
                    JsonObject custom = statsObj.getAsJsonObject("minecraft:custom");
                    if (custom.has("minecraft:play_time")) {
                        int ticks = custom.get("minecraft:play_time").getAsInt();
                        return ticks / 20;
                    }
                }
            }
        } catch (Exception e) {
            if (XPSyncConfig.CONFIG.verboseLogging.get()) {
                VonixCore.LOGGER.warn("[XPSync] Failed to read stats for {}", uuid, e);
            }
        }
        return 0;
    }

    /**
     * Calculate total XP from level and progress
     */
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

    /**
     * Sync only online players (for regular intervals)
     */
    private void syncOnlinePlayers() {
        try {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();

            if (players.isEmpty()) {
                if (XPSyncConfig.CONFIG.verboseLogging.get()) {
                    VonixCore.LOGGER.debug("[XPSync] No players online, skipping interval sync");
                }
                return;
            }

            JsonObject payload = buildPayload(players);
            sendToAPI(payload, false);

        } catch (Exception e) {
            VonixCore.LOGGER.error("[XPSync] Error syncing online player data", e);
        }
    }

    // Batch sync only - single-player sync removed for optimization
    // All players are now synced together on intervals

    private JsonObject buildPayload(List<ServerPlayer> players) {
        JsonObject root = new JsonObject();
        root.addProperty("serverName", serverName);

        JsonArray playersArray = new JsonArray();
        for (ServerPlayer player : players) {
            playersArray.add(buildPlayerDataFromOnline(player));
        }
        root.add("players", playersArray);
        // Store count for verification after API response
        root.addProperty("_playerCount", players.size());

        return root;
    }

    /**
     * Get the lifetime total XP for a player using high-water mark tracking.
     * This ensures XP never decreases even when players die or spend XP.
     */
    private int getTotalExperience(ServerPlayer player) {
        // Calculate current XP from level and progress
        int level = player.experienceLevel;
        int currentXP = 0;

        if (level >= 32) {
            currentXP = (int) (4.5 * level * level - 162.5 * level + 2220);
        } else if (level >= 17) {
            currentXP = (int) (2.5 * level * level - 40.5 * level + 360);
        } else {
            currentXP = level * level + 6 * level;
        }

        currentXP += Math.round(player.experienceProgress * player.getXpNeededForNextLevel());

        // Use high-water mark: only update if current XP is higher than stored
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

    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 2000;

    private void sendToAPI(JsonObject payload, boolean isShutdown) {
        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            attempt++;
            if (trySendToAPI(payload, isShutdown, attempt)) {
                return; // Success
            }
            if (attempt < MAX_RETRIES && !isShutdown) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        VonixCore.LOGGER.error("[XPSync] Failed to sync after {} attempts", MAX_RETRIES);
    }

    private boolean trySendToAPI(JsonObject payload, boolean isShutdown, int attempt) {
        HttpURLConnection conn = null;
        try {
            String jsonPayload = gson.toJson(payload);

            if (XPSyncConfig.CONFIG.verboseLogging.get()) {
                VonixCore.LOGGER.debug("[XPSync] Sending payload to {} (attempt {})", apiEndpoint, attempt);
            }

            URL url = new URL(apiEndpoint);
            conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("User-Agent", "VonixCore-XPSync/1.0.0");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(isShutdown ? SHUTDOWN_READ_TIMEOUT : READ_TIMEOUT);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int statusCode = conn.getResponseCode();
            String responseBody = readResponse(conn, statusCode);

            if (statusCode == 200) {
                // Extract expected count from payload (stored by buildPayload)
                int expectedCount = payload.has("_playerCount") ? payload.get("_playerCount").getAsInt()
                        : (payload.has("players") ? payload.getAsJsonArray("players").size() : 0);
                handleSuccessResponse(responseBody, expectedCount);
                return true;
            } else if (statusCode == 401) {
                VonixCore.LOGGER.error("[XPSync] Authentication failed! Check your API key.");
                return true; // Don't retry auth errors
            } else if (statusCode == 403) {
                VonixCore.LOGGER.error("[XPSync] API key invalid or server not recognized.");
                return true; // Don't retry auth errors
            } else if (statusCode >= 500) {
                VonixCore.LOGGER.warn("[XPSync] Server error {} (attempt {}/{}): {}", statusCode, attempt, MAX_RETRIES,
                        responseBody);
                return false; // Retry server errors
            } else {
                VonixCore.LOGGER.error("[XPSync] Failed to sync. HTTP {}: {}", statusCode, responseBody);
                return true; // Don't retry client errors
            }

        } catch (java.net.ConnectException e) {
            VonixCore.LOGGER.warn("[XPSync] Cannot connect to API (attempt {}/{}): {}", attempt, MAX_RETRIES,
                    apiEndpoint);
            return false;
        } catch (java.net.SocketTimeoutException e) {
            VonixCore.LOGGER.warn("[XPSync] Request timed out (attempt {}/{}): {}", attempt, MAX_RETRIES, apiEndpoint);
            return false;
        } catch (java.net.UnknownHostException e) {
            VonixCore.LOGGER.error("[XPSync] Unknown host: {}", apiEndpoint);
            return true; // Don't retry DNS errors
        } catch (Exception e) {
            VonixCore.LOGGER.warn("[XPSync] Error sending to API (attempt {}/{}): {}", attempt, MAX_RETRIES,
                    e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
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
                    VonixCore.LOGGER.info("[XPSync] Successfully synced {} players", synced);
                } else if (synced > 0) {
                    VonixCore.LOGGER.warn("[XPSync] Partial sync: {} of {} players synced", synced, expectedCount);
                } else {
                    VonixCore.LOGGER.warn("[XPSync] API returned success but syncedCount=0 (expected {})",
                            expectedCount);
                }
            } else {
                String error = response.has("error") ? response.get("error").getAsString() : "Unknown";
                VonixCore.LOGGER.warn("[XPSync] API response: {}", error);
            }
        } catch (Exception e) {
            VonixCore.LOGGER.error("[XPSync] Failed to parse response: {}", responseBody);
        }
    }
}
