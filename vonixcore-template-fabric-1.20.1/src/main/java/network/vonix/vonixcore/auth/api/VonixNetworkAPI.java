package network.vonix.vonixcore.auth.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.AuthConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Vonix Network API client for authentication endpoints.
 * Ported from Forge for 1:1 parity with Fabric-aware config handling.
 */
public class VonixNetworkAPI {
    private static final ExecutorService API_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "VonixCore-API");
        t.setDaemon(true);
        return t;
    });

    private static final Gson GSON = new GsonBuilder().setLenient().create();

    public static class LoginResponse {
        public boolean success;
        public String message;
        public String token;
        public User user;
        public String error;

        public static class User {
            public int id;
            public String username;
            public String minecraft_username;
            public String minecraft_uuid;
            public String role;
            public double total_donated;
            public String donation_rank_id;
            public DonationRank donation_rank;
        }

        public static class DonationRank {
            public String id;
            public String name;
            public String color;
            public String expires_at;
        }
    }

    public static class RegistrationResponse {
        public boolean success;
        public String code;
        public int expires_in;
        public String error;
        public boolean already_registered;
    }

    public static class RegistrationCheckResponse {
        public boolean registered;
        public String message;
        public LoginResponse.User user;
        public String error;
    }

    private static String buildUrl(String endpoint) {
        String baseUrl = AuthConfig.getInstance().getApiBaseUrl();
        if (baseUrl.endsWith("/"))
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        if (!endpoint.startsWith("/"))
            endpoint = "/" + endpoint;
        return baseUrl + endpoint;
    }

    private static String readResponse(HttpURLConnection conn, int statusCode) throws IOException {
        var stream = statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null)
            return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null)
                sb.append(line);
            return sb.toString();
        }
    }

    private static void configureConnection(HttpURLConnection conn, String method, boolean hasBody) throws IOException {
        AuthConfig config = AuthConfig.getInstance();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-API-Key", config.getRegistrationApiKey());
        conn.setRequestProperty("User-Agent", "VonixCore/" + VonixCore.VERSION);
        conn.setConnectTimeout(config.getApiTimeout());
        conn.setReadTimeout(config.getApiTimeout());
        if (hasBody) {
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
        }
    }

    public static CompletableFuture<LoginResponse> loginPlayer(String username, String uuid, String password) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(buildUrl("/minecraft/login")).openConnection();
                configureConnection(conn, "POST", true);

                JsonObject body = new JsonObject();
                body.addProperty("minecraft_username", username);
                body.addProperty("minecraft_uuid", uuid);
                body.addProperty("password", password);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
                }

                int status = conn.getResponseCode();
                String responseBody = readResponse(conn, status);
                conn.disconnect();

                if (status == 200)
                    return GSON.fromJson(responseBody, LoginResponse.class);

                LoginResponse err = new LoginResponse();
                err.success = false;
                err.error = parseError(responseBody, status);
                return err;
            } catch (IOException e) {
                LoginResponse err = new LoginResponse();
                err.success = false;
                err.error = "Connection failed: " + e.getMessage();
                return err;
            }
        }, API_EXECUTOR);
    }

    public static CompletableFuture<RegistrationResponse> generateRegistrationCode(String username, String uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(buildUrl("/minecraft/register")).openConnection();
                configureConnection(conn, "POST", true);

                JsonObject body = new JsonObject();
                body.addProperty("minecraft_username", username);
                body.addProperty("minecraft_uuid", uuid);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
                }

                int status = conn.getResponseCode();
                String responseBody = readResponse(conn, status);
                conn.disconnect();

                if (status == 200)
                    return GSON.fromJson(responseBody, RegistrationResponse.class);

                RegistrationResponse err = new RegistrationResponse();
                err.error = parseError(responseBody, status);
                return err;
            } catch (IOException e) {
                RegistrationResponse err = new RegistrationResponse();
                err.error = "Connection failed: " + e.getMessage();
                return err;
            }
        }, API_EXECUTOR);
    }

    public static CompletableFuture<LoginResponse> registerPlayerWithPassword(String username, String uuid,
            String password) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(buildUrl("/minecraft/register-direct"))
                        .openConnection();
                configureConnection(conn, "POST", true);

                JsonObject body = new JsonObject();
                body.addProperty("minecraft_username", username);
                body.addProperty("minecraft_uuid", uuid);
                body.addProperty("password", password);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
                }

                int status = conn.getResponseCode();
                String responseBody = readResponse(conn, status);
                conn.disconnect();

                if (status == 200 || status == 201)
                    return GSON.fromJson(responseBody, LoginResponse.class);

                LoginResponse err = new LoginResponse();
                err.success = false;
                err.error = parseError(responseBody, status);
                return err;
            } catch (IOException e) {
                LoginResponse err = new LoginResponse();
                err.success = false;
                err.error = "Connection failed: " + e.getMessage();
                return err;
            }
        }, API_EXECUTOR);
    }

    public static CompletableFuture<RegistrationCheckResponse> checkPlayerRegistration(String username, String uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = buildUrl("/minecraft/verify") + "?uuid=" + uuid + "&username=" + username;
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                configureConnection(conn, "GET", false);

                int status = conn.getResponseCode();
                String responseBody = readResponse(conn, status);
                conn.disconnect();

                RegistrationCheckResponse response = new RegistrationCheckResponse();
                if (status == 200) {
                    JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
                    response.registered = json.has("verified") ? json.get("verified").getAsBoolean()
                            : json.has("registered") && json.get("registered").getAsBoolean();
                    if (json.has("user"))
                        response.user = GSON.fromJson(json.get("user"), LoginResponse.User.class);
                } else {
                    response.registered = false;
                    response.error = parseError(responseBody, status);
                }
                return response;
            } catch (IOException e) {
                RegistrationCheckResponse err = new RegistrationCheckResponse();
                err.registered = false;
                err.error = "Connection failed: " + e.getMessage();
                return err;
            }
        }, API_EXECUTOR);
    }

    private static String parseError(String body, int status) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return json.has("error") ? json.get("error").getAsString() : "Unknown error";
        } catch (Exception e) {
            return "API error: " + status;
        }
    }

    public static void shutdown() {
        API_EXECUTOR.shutdown();
        try {
            if (!API_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS))
                API_EXECUTOR.shutdownNow();
        } catch (InterruptedException e) {
            API_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
