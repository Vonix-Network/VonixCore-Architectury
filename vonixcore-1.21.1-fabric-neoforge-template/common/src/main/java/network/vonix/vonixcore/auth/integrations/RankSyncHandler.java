package network.vonix.vonixcore.auth.integrations;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.AuthConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Handles automatic rank synchronization on player join.
 * Queries the donations rank-check API to get the player's active rank
 * and applies it via LuckPerms — works even without login.
 */
public class RankSyncHandler {

    /**
     * Called on player join to sync their donation rank from the website.
     * Uses the XP sync API key (same server key) to authenticate.
     */
    public static void syncOnJoin(UUID uuid) {
        if (!LuckPermsIntegration.isAvailable()) return;

        CompletableFuture.runAsync(() -> {
            try {
                String apiKey = getApiKey();
                if (apiKey == null || apiKey.equals("YOUR_API_KEY_HERE")) return;

                String baseUrl = AuthConfig.CONFIG.API_BASE_URL.get();
                if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

                String url = baseUrl + "/ext/donations/rank-check?uuid=" + uuid.toString();

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("X-API-Key", apiKey);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "VonixCore/" + VonixCore.VERSION);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int status = conn.getResponseCode();
                if (status != 200) {
                    conn.disconnect();
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                conn.disconnect();

                JsonObject response = JsonParser.parseString(sb.toString()).getAsJsonObject();
                boolean hasRank = response.has("has_rank") && response.get("has_rank").getAsBoolean();

                if (hasRank && response.has("luckperms_group")) {
                    String group = response.get("luckperms_group").getAsString();
                    String rankName = response.has("rank") ? response.get("rank").getAsString() : group;

                    VonixCore.LOGGER.info("[RankSync] Player {} has donation rank '{}', applying group '{}'",
                            uuid, rankName, group);

                    LuckPermsIntegration.synchronizeRankByGroup(uuid, group);
                } else {
                    VonixCore.LOGGER.debug("[RankSync] Player {} has no active donation rank", uuid);
                }
            } catch (Exception e) {
                VonixCore.LOGGER.debug("[RankSync] Could not check rank for {}: {}", uuid, e.getMessage());
            }
        });
    }

    /**
     * Try to get an API key — first from XP sync config, then from auth config.
     */
    private static String getApiKey() {
        try {
            // Try XP sync API key first
            Class<?> xpConfig = Class.forName("network.vonix.vonixcore.config.XPSyncConfig");
            Object configInstance = xpConfig.getField("CONFIG").get(null);
            Object apiKeyField = configInstance.getClass().getField("apiKey").get(configInstance);
            Object value = apiKeyField.getClass().getMethod("get").invoke(apiKeyField);
            if (value != null && !value.toString().equals("YOUR_API_KEY_HERE")) {
                return value.toString();
            }
        } catch (Exception ignored) {}

        // Fall back to registration API key
        return AuthConfig.CONFIG.REGISTRATION_API_KEY.get();
    }
}
