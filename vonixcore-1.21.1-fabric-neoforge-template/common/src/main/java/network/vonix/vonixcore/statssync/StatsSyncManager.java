package network.vonix.vonixcore.statssync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.storage.LevelResource;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.StatsSyncConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * StatsSyncManager — Syncs comprehensive vanilla Minecraft statistics
 * to the Vonix Network website, replacing the old XP-only sync.
 *
 * Reads player stats from:
 *   - Online players: ServerPlayer.getStats() (most accurate)
 *   - Offline players: world/stats/<uuid>.json files
 *
 * Sends the full minecraft:custom stats block for each player.
 */
public class StatsSyncManager {

    private static StatsSyncManager instance;

    private final MinecraftServer server;
    private final String apiEndpoint;
    private final String apiKey;
    private final String serverName;
    private final Gson gson;
    private ScheduledExecutorService scheduler;

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 30000;
    private static final int SHUTDOWN_READ_TIMEOUT = 10000;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 2000;

    public static StatsSyncManager getInstance() {
        return instance;
    }

    public StatsSyncManager(MinecraftServer server) {
        instance = this;
        this.server = server;
        this.apiEndpoint = StatsSyncConfig.CONFIG.apiEndpoint.get();
        this.apiKey = StatsSyncConfig.CONFIG.apiKey.get();
        this.serverName = StatsSyncConfig.CONFIG.serverName.get();
        this.gson = new GsonBuilder().setLenient().create();
    }

    public void start() {
        int intervalSeconds = StatsSyncConfig.CONFIG.syncInterval.get();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VonixCore-StatsSync-Thread");
            t.setDaemon(true);
            return t;
        });

        // Sync ALL players from world data on startup
        VonixCore.LOGGER.info("[StatsSync] Running startup sync for ALL players from world data...");
        scheduler.execute(this::syncAllPlayersFromWorldData);

        // Schedule regular syncs for online players only
        scheduler.scheduleAtFixedRate(
                this::syncOnlinePlayers,
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS);

        VonixCore.LOGGER.info("[StatsSync] Scheduled to run every {} seconds", intervalSeconds);
    }

    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            VonixCore.LOGGER.info("[StatsSync] Stopping service...");

            // Final sync with timeout
            VonixCore.LOGGER.info("[StatsSync] Running final sync for ALL players...");
            try {
                java.util.concurrent.CompletableFuture<Void> syncFuture =
                        java.util.concurrent.CompletableFuture.runAsync(
                                this::syncAllPlayersFromWorldData, VonixCore.ASYNC_EXECUTOR);
                syncFuture.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                VonixCore.LOGGER.warn("[StatsSync] Final sync timed out, continuing with shutdown");
            } catch (Exception e) {
                VonixCore.LOGGER.warn("[StatsSync] Final sync failed: {}", e.getMessage());
            }

            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    VonixCore.LOGGER.warn("[StatsSync] Scheduler did not terminate in time, forcing shutdown...");
                    scheduler.shutdownNow();
                    if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                        VonixCore.LOGGER.error("[StatsSync] Scheduler could not be terminated!");
                    }
                }
            } catch (InterruptedException e) {
                VonixCore.LOGGER.warn("[StatsSync] Interrupted while waiting for shutdown");
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }

            VonixCore.LOGGER.info("[StatsSync] Service stopped.");
            instance = null;
        }
    }

    // ═══════════════════════════════════════════
    // Sync Logic
    // ═══════════════════════════════════════════

    private void syncAllPlayersFromWorldData() {
        try {
            List<JsonObject> allPlayers = getAllPlayersFromWorldData();

            if (allPlayers.isEmpty()) {
                VonixCore.LOGGER.info("[StatsSync] No player data found in world files");
                return;
            }

            VonixCore.LOGGER.info("[StatsSync] Found {} players in world data, syncing...", allPlayers.size());

            // Send in batches of 50
            int batchSize = 50;
            for (int i = 0; i < allPlayers.size(); i += batchSize) {
                int end = Math.min(i + batchSize, allPlayers.size());
                List<JsonObject> batch = allPlayers.subList(i, end);

                JsonObject payload = new JsonObject();
                payload.addProperty("serverName", serverName);
                JsonArray playersArray = new JsonArray();
                batch.forEach(playersArray::add);
                payload.add("players", playersArray);

                VonixCore.LOGGER.info("[StatsSync] Syncing batch {}-{} of {} players...", i + 1, end, allPlayers.size());
                sendToAPI(payload);

                if (end < allPlayers.size()) {
                    Thread.sleep(500);
                }
            }

            VonixCore.LOGGER.info("[StatsSync] Completed syncing all {} players", allPlayers.size());
        } catch (Exception e) {
            VonixCore.LOGGER.error("[StatsSync] Error syncing all player data", e);
        }
    }

    private void syncOnlinePlayers() {
        try {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                if (StatsSyncConfig.CONFIG.verboseLogging.get()) {
                    VonixCore.LOGGER.debug("[StatsSync] No players online, skipping interval sync");
                }
                return;
            }

            JsonObject payload = new JsonObject();
            payload.addProperty("serverName", serverName);
            JsonArray playersArray = new JsonArray();

            for (ServerPlayer player : players) {
                playersArray.add(buildPlayerDataFromOnline(player));
            }

            payload.add("players", playersArray);
            payload.addProperty("_playerCount", players.size());
            sendToAPI(payload);
        } catch (Exception e) {
            VonixCore.LOGGER.error("[StatsSync] Error syncing online player data", e);
        }
    }

    // ═══════════════════════════════════════════
    // Player Data Building
    // ═══════════════════════════════════════════

    /**
     * Build stats for an online player using ServerStatsCounter (most accurate).
     * Reads all minecraft:custom stats directly from the player's stats object.
     */
    private JsonObject buildPlayerDataFromOnline(ServerPlayer player) {
        JsonObject playerData = new JsonObject();
        playerData.addProperty("uuid", player.getUUID().toString());
        playerData.addProperty("username", player.getName().getString());

        // Read stats from the player's stats file on disk for completeness
        // (ServerStatsCounter.save() flushes periodically)
        JsonObject stats = readStatsFromFile(player.getUUID());
        if (stats == null) {
            // Fallback: read key stats directly from the player object
            stats = buildCoreStatsFromPlayer(player);
        }
        playerData.add("stats", stats);

        return playerData;
    }

    /**
     * Build core stats directly from a ServerPlayer's stats counter.
     * Fallback when the stats JSON file isn't available.
     */
    private JsonObject buildCoreStatsFromPlayer(ServerPlayer player) {
        JsonObject stats = new JsonObject();
        var counter = player.getStats();

        // Core stats
        stats.addProperty("deaths", counter.getValue(Stats.CUSTOM.get(Stats.DEATHS)));
        stats.addProperty("mob_kills", counter.getValue(Stats.CUSTOM.get(Stats.MOB_KILLS)));
        stats.addProperty("player_kills", counter.getValue(Stats.CUSTOM.get(Stats.PLAYER_KILLS)));
        stats.addProperty("play_time", counter.getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)));
        stats.addProperty("total_world_time", counter.getValue(Stats.CUSTOM.get(Stats.TOTAL_WORLD_TIME)));

        // Movement
        stats.addProperty("walk_one_cm", counter.getValue(Stats.CUSTOM.get(Stats.WALK_ONE_CM)));
        stats.addProperty("sprint_one_cm", counter.getValue(Stats.CUSTOM.get(Stats.SPRINT_ONE_CM)));
        stats.addProperty("crouch_one_cm", counter.getValue(Stats.CUSTOM.get(Stats.CROUCH_ONE_CM)));
        stats.addProperty("climb_one_cm", counter.getValue(Stats.CUSTOM.get(Stats.CLIMB_ONE_CM)));
        stats.addProperty("fly_one_cm", counter.getValue(Stats.CUSTOM.get(Stats.FLY_ONE_CM)));
        stats.addProperty("swim_one_cm", counter.getValue(Stats.CUSTOM.get(Stats.SWIM_ONE_CM)));
        stats.addProperty("fall_one_cm", counter.getValue(Stats.CUSTOM.get(Stats.FALL_ONE_CM)));
        stats.addProperty("walk_on_water_one_cm", counter.getValue(Stats.CUSTOM.get(Stats.WALK_ON_WATER_ONE_CM)));
        stats.addProperty("walk_under_water_one_cm", counter.getValue(Stats.CUSTOM.get(Stats.WALK_UNDER_WATER_ONE_CM)));

        // Vehicle travel
        stats.addProperty("horse_one_cm", counter.getValue(Stats.CUSTOM.get(Stats.HORSE_ONE_CM)));
        stats.addProperty("boat_one_cm", counter.getValue(Stats.CUSTOM.get(Stats.BOAT_ONE_CM)));
        stats.addProperty("minecart_one_cm", counter.getValue(Stats.CUSTOM.get(Stats.MINECART_ONE_CM)));
        stats.addProperty("aviate_one_cm", counter.getValue(Stats.CUSTOM.get(Stats.AVIATE_ONE_CM)));
        stats.addProperty("pig_one_cm", counter.getValue(Stats.CUSTOM.get(Stats.PIG_ONE_CM)));
        stats.addProperty("strider_one_cm", counter.getValue(Stats.CUSTOM.get(Stats.STRIDER_ONE_CM)));

        // Combat
        stats.addProperty("damage_dealt", counter.getValue(Stats.CUSTOM.get(Stats.DAMAGE_DEALT)));
        stats.addProperty("damage_taken", counter.getValue(Stats.CUSTOM.get(Stats.DAMAGE_TAKEN)));
        stats.addProperty("damage_blocked_by_shield", counter.getValue(Stats.CUSTOM.get(Stats.DAMAGE_BLOCKED_BY_SHIELD)));
        stats.addProperty("damage_absorbed", counter.getValue(Stats.CUSTOM.get(Stats.DAMAGE_ABSORBED)));
        stats.addProperty("damage_resisted", counter.getValue(Stats.CUSTOM.get(Stats.DAMAGE_RESISTED)));
        stats.addProperty("damage_dealt_absorbed", counter.getValue(Stats.CUSTOM.get(Stats.DAMAGE_DEALT_ABSORBED)));
        stats.addProperty("damage_dealt_resisted", counter.getValue(Stats.CUSTOM.get(Stats.DAMAGE_DEALT_RESISTED)));

        // Progression
        stats.addProperty("animals_bred", counter.getValue(Stats.CUSTOM.get(Stats.ANIMALS_BRED)));
        stats.addProperty("fish_caught", counter.getValue(Stats.CUSTOM.get(Stats.FISH_CAUGHT)));
        stats.addProperty("raid_win", counter.getValue(Stats.CUSTOM.get(Stats.RAID_WIN)));
        stats.addProperty("raid_trigger", counter.getValue(Stats.CUSTOM.get(Stats.RAID_TRIGGER)));
        stats.addProperty("traded_with_villager", counter.getValue(Stats.CUSTOM.get(Stats.TRADED_WITH_VILLAGER)));
        stats.addProperty("enchant_item", counter.getValue(Stats.CUSTOM.get(Stats.ENCHANT_ITEM)));

        // Interactions
        stats.addProperty("jump", counter.getValue(Stats.CUSTOM.get(Stats.JUMP)));
        stats.addProperty("drop", counter.getValue(Stats.CUSTOM.get(Stats.DROP)));
        stats.addProperty("open_chest", counter.getValue(Stats.CUSTOM.get(Stats.OPEN_CHEST)));
        stats.addProperty("open_enderchest", counter.getValue(Stats.CUSTOM.get(Stats.OPEN_ENDERCHEST)));
        stats.addProperty("open_shulker_box", counter.getValue(Stats.CUSTOM.get(Stats.OPEN_SHULKER_BOX)));
        stats.addProperty("play_noteblock", counter.getValue(Stats.CUSTOM.get(Stats.PLAY_NOTEBLOCK)));
        stats.addProperty("interact_with_crafting_table", counter.getValue(Stats.CUSTOM.get(Stats.INTERACT_WITH_CRAFTING_TABLE)));
        stats.addProperty("interact_with_furnace", counter.getValue(Stats.CUSTOM.get(Stats.INTERACT_WITH_FURNACE)));
        stats.addProperty("interact_with_blast_furnace", counter.getValue(Stats.CUSTOM.get(Stats.INTERACT_WITH_BLAST_FURNACE)));
        stats.addProperty("interact_with_smoker", counter.getValue(Stats.CUSTOM.get(Stats.INTERACT_WITH_SMOKER)));
        stats.addProperty("interact_with_anvil", counter.getValue(Stats.CUSTOM.get(Stats.INTERACT_WITH_ANVIL)));
        stats.addProperty("interact_with_brewingstand", counter.getValue(Stats.CUSTOM.get(Stats.INTERACT_WITH_BREWINGSTAND)));
        stats.addProperty("interact_with_beacon", counter.getValue(Stats.CUSTOM.get(Stats.INTERACT_WITH_BEACON)));
        stats.addProperty("interact_with_smithing_table", counter.getValue(Stats.CUSTOM.get(Stats.INTERACT_WITH_SMITHING_TABLE)));
        stats.addProperty("interact_with_grindstone", counter.getValue(Stats.CUSTOM.get(Stats.INTERACT_WITH_GRINDSTONE)));
        stats.addProperty("interact_with_stonecutter", counter.getValue(Stats.CUSTOM.get(Stats.INTERACT_WITH_STONECUTTER)));
        stats.addProperty("interact_with_loom", counter.getValue(Stats.CUSTOM.get(Stats.INTERACT_WITH_LOOM)));
        stats.addProperty("interact_with_cartography_table", counter.getValue(Stats.CUSTOM.get(Stats.INTERACT_WITH_CARTOGRAPHY_TABLE)));
        stats.addProperty("sleep_in_bed", counter.getValue(Stats.CUSTOM.get(Stats.SLEEP_IN_BED)));
        stats.addProperty("leave_game", counter.getValue(Stats.CUSTOM.get(Stats.LEAVE_GAME)));
        stats.addProperty("time_since_death", counter.getValue(Stats.CUSTOM.get(Stats.TIME_SINCE_DEATH)));
        stats.addProperty("time_since_rest", counter.getValue(Stats.CUSTOM.get(Stats.TIME_SINCE_REST)));

        return stats;
    }

    /**
     * Read all player data from world files (for startup/shutdown sync).
     */
    private List<JsonObject> getAllPlayersFromWorldData() {
        List<JsonObject> players = new ArrayList<>();

        try {
            Path worldPath = server.getWorldPath(LevelResource.ROOT);
            Path statsPath = worldPath.resolve("stats");
            Path playerDataPath = worldPath.resolve("playerdata");

            // First, add online players (most accurate data)
            List<String> onlineUuids = new ArrayList<>();
            for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
                players.add(buildPlayerDataFromOnline(onlinePlayer));
                onlineUuids.add(onlinePlayer.getUUID().toString());
            }

            // Then read offline player stats files
            if (!Files.exists(statsPath)) {
                VonixCore.LOGGER.warn("[StatsSync] Stats folder not found: {}", statsPath);
                return players;
            }

            File[] statsFiles = statsPath.toFile().listFiles((dir, name) -> name.endsWith(".json"));
            if (statsFiles == null) return players;

            for (File statsFile : statsFiles) {
                try {
                    String fileName = statsFile.getName();
                    String uuidStr = fileName.replace(".json", "");

                    // Skip online players (already added)
                    if (onlineUuids.contains(uuidStr)) continue;

                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidStr);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }

                    JsonObject stats = readStatsFromFile(uuid);
                    if (stats == null || stats.size() == 0) continue;

                    JsonObject playerData = new JsonObject();
                    playerData.addProperty("uuid", uuid.toString());

                    // Try to get username from profile cache
                    String username = getPlayerUsername(uuid);
                    playerData.addProperty("username", username != null ? username : uuidStr);

                    playerData.add("stats", stats);
                    players.add(playerData);
                } catch (Exception e) {
                    if (StatsSyncConfig.CONFIG.verboseLogging.get()) {
                        VonixCore.LOGGER.warn("[StatsSync] Failed to read stats file: {}", statsFile.getName(), e);
                    }
                }
            }
        } catch (Exception e) {
            VonixCore.LOGGER.error("[StatsSync] Error reading player data from world files", e);
        }

        return players;
    }

    /**
     * Reads the minecraft:custom stats from a player's stats JSON file.
     * Returns a flattened JsonObject with stat_key -> value mappings.
     */
    private JsonObject readStatsFromFile(UUID uuid) {
        try {
            Path worldPath = server.getWorldPath(LevelResource.ROOT);
            Path statsFile = worldPath.resolve("stats").resolve(uuid.toString() + ".json");

            if (!Files.exists(statsFile)) return null;

            String content = Files.readString(statsFile);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();

            if (!root.has("stats")) return null;
            JsonObject statsObj = root.getAsJsonObject("stats");

            JsonObject result = new JsonObject();

            // Extract minecraft:custom stats (the core stats we track)
            if (statsObj.has("minecraft:custom")) {
                JsonObject custom = statsObj.getAsJsonObject("minecraft:custom");
                for (Map.Entry<String, JsonElement> entry : custom.entrySet()) {
                    // Strip "minecraft:" prefix for cleaner keys
                    String key = entry.getKey().replace("minecraft:", "");
                    result.addProperty(key, entry.getValue().getAsInt());
                }
            }

            // Extract aggregate counts from category stats
            if (statsObj.has("minecraft:mined")) {
                int total = 0;
                for (Map.Entry<String, JsonElement> entry : statsObj.getAsJsonObject("minecraft:mined").entrySet()) {
                    total += entry.getValue().getAsInt();
                }
                result.addProperty("total_blocks_mined", total);
            }

            if (statsObj.has("minecraft:crafted")) {
                int total = 0;
                for (Map.Entry<String, JsonElement> entry : statsObj.getAsJsonObject("minecraft:crafted").entrySet()) {
                    total += entry.getValue().getAsInt();
                }
                result.addProperty("total_items_crafted", total);
            }

            if (statsObj.has("minecraft:used")) {
                int total = 0;
                for (Map.Entry<String, JsonElement> entry : statsObj.getAsJsonObject("minecraft:used").entrySet()) {
                    total += entry.getValue().getAsInt();
                }
                result.addProperty("total_items_used", total);
            }

            if (statsObj.has("minecraft:broken")) {
                int total = 0;
                for (Map.Entry<String, JsonElement> entry : statsObj.getAsJsonObject("minecraft:broken").entrySet()) {
                    total += entry.getValue().getAsInt();
                }
                result.addProperty("total_items_broken", total);
            }

            if (statsObj.has("minecraft:picked_up")) {
                int total = 0;
                for (Map.Entry<String, JsonElement> entry : statsObj.getAsJsonObject("minecraft:picked_up").entrySet()) {
                    total += entry.getValue().getAsInt();
                }
                result.addProperty("total_items_picked_up", total);
            }

            if (statsObj.has("minecraft:dropped")) {
                int total = 0;
                for (Map.Entry<String, JsonElement> entry : statsObj.getAsJsonObject("minecraft:dropped").entrySet()) {
                    total += entry.getValue().getAsInt();
                }
                result.addProperty("total_items_dropped", total);
            }

            if (statsObj.has("minecraft:killed")) {
                int total = 0;
                for (Map.Entry<String, JsonElement> entry : statsObj.getAsJsonObject("minecraft:killed").entrySet()) {
                    total += entry.getValue().getAsInt();
                }
                result.addProperty("total_entities_killed", total);
            }

            if (statsObj.has("minecraft:killed_by")) {
                int total = 0;
                for (Map.Entry<String, JsonElement> entry : statsObj.getAsJsonObject("minecraft:killed_by").entrySet()) {
                    total += entry.getValue().getAsInt();
                }
                result.addProperty("total_killed_by", total);
            }

            return result;
        } catch (Exception e) {
            if (StatsSyncConfig.CONFIG.verboseLogging.get()) {
                VonixCore.LOGGER.warn("[StatsSync] Failed to read stats file for {}", uuid, e);
            }
            return null;
        }
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
            // Ignore
        }
        return null;
    }

    // ═══════════════════════════════════════════
    // API Communication
    // ═══════════════════════════════════════════

    private void sendToAPI(JsonObject payload) {
        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            attempt++;
            if (trySendToAPI(payload, attempt)) {
                return;
            }
            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        VonixCore.LOGGER.error("[StatsSync] Failed to sync after {} attempts", MAX_RETRIES);
    }

    private boolean trySendToAPI(JsonObject payload, int attempt) {
        HttpURLConnection conn = null;
        try {
            String jsonPayload = gson.toJson(payload);

            if (StatsSyncConfig.CONFIG.verboseLogging.get()) {
                VonixCore.LOGGER.debug("[StatsSync] Sending payload to {} (attempt {})", apiEndpoint, attempt);
            }

            URL url = new URL(apiEndpoint);
            conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("User-Agent", "VonixCore-StatsSync/2.0.0");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int statusCode = conn.getResponseCode();
            String responseBody = readResponse(conn, statusCode);

            if (statusCode == 200) {
                int expectedCount = payload.has("_playerCount") ? payload.get("_playerCount").getAsInt()
                        : (payload.has("players") ? payload.getAsJsonArray("players").size() : 0);
                handleSuccessResponse(responseBody, expectedCount);
                return true;
            } else if (statusCode == 401 || statusCode == 403) {
                VonixCore.LOGGER.error("[StatsSync] Authentication failed (HTTP {})! Check your API key.", statusCode);
                return true; // Don't retry auth errors
            } else if (statusCode >= 500) {
                VonixCore.LOGGER.warn("[StatsSync] Server error {} (attempt {}/{}): {}",
                        statusCode, attempt, MAX_RETRIES, responseBody);
                return false; // Retry
            } else {
                VonixCore.LOGGER.error("[StatsSync] Failed to sync. HTTP {}: {}", statusCode, responseBody);
                return true; // Don't retry client errors
            }
        } catch (java.net.ConnectException e) {
            VonixCore.LOGGER.warn("[StatsSync] Cannot connect (attempt {}/{}): {}", attempt, MAX_RETRIES, apiEndpoint);
            return false;
        } catch (java.net.SocketTimeoutException e) {
            VonixCore.LOGGER.warn("[StatsSync] Timed out (attempt {}/{}): {}", attempt, MAX_RETRIES, apiEndpoint);
            return false;
        } catch (java.net.UnknownHostException e) {
            VonixCore.LOGGER.error("[StatsSync] Unknown host: {}", apiEndpoint);
            return true;
        } catch (Exception e) {
            VonixCore.LOGGER.warn("[StatsSync] Error (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readResponse(HttpURLConnection conn, int statusCode) throws java.io.IOException {
        var stream = statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) return "";

        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(256);
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private void handleSuccessResponse(String responseBody, int expectedCount) {
        if (responseBody == null || responseBody.isEmpty()) return;

        try {
            JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
            if (response.has("success") && response.get("success").getAsBoolean()) {
                int synced = 0;
                if (response.has("syncedCount")) synced = response.get("syncedCount").getAsInt();
                else if (response.has("synced")) synced = response.get("synced").getAsInt();

                if (synced == expectedCount) {
                    VonixCore.LOGGER.info("[StatsSync] Successfully synced {} players", synced);
                } else if (synced > 0) {
                    VonixCore.LOGGER.warn("[StatsSync] Partial sync: {} of {} players", synced, expectedCount);
                } else {
                    VonixCore.LOGGER.warn("[StatsSync] API returned success but syncedCount=0 (expected {})", expectedCount);
                }
            } else {
                String error = response.has("error") ? response.get("error").getAsString() : "Unknown";
                VonixCore.LOGGER.warn("[StatsSync] API response: {}", error);
            }
        } catch (Exception e) {
            VonixCore.LOGGER.error("[StatsSync] Failed to parse response: {}", responseBody);
        }
    }
}
