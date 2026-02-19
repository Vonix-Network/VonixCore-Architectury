package network.vonix.vonixcore.auth;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.Util;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.auth.api.VonixNetworkAPI;
import network.vonix.vonixcore.config.AuthConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages player authentication state, freeze status, and session timeouts.
 */
public class AuthenticationManager {
    private static final Map<UUID, PlayerAuthState> playerStates = new ConcurrentHashMap<>();
    private static final Map<UUID, String> playerTokens = new ConcurrentHashMap<>();
    private static final Map<UUID, ScheduledFuture<?>> timeoutTasks = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VonixCore-Auth-Scheduler");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean freezeEnabled = false;

    public enum PlayerAuthState {
        UNAUTHENTICATED, AUTHENTICATED, PENDING_REGISTRATION
    }

    public static void updateFreezeCache() {
        freezeEnabled = AuthConfig.CONFIG.REQUIRE_AUTHENTICATION.get()
                && AuthConfig.CONFIG.FREEZE_UNAUTHENTICATED.get();
    }

    public static void onPlayerJoin(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String username = player.getName().getString();
        playerStates.put(uuid, PlayerAuthState.UNAUTHENTICATED);

        if (AuthConfig.CONFIG.REQUIRE_AUTHENTICATION.get()) {
            VonixCore.LOGGER.info("[Auth] Player {} joined - checking registration", username);

            VonixNetworkAPI.checkPlayerRegistration(username, uuid.toString())
                    .thenAccept(response -> {
                        if (response.registered) {
                            player.sendMessage(new TextComponent(AuthConfig.CONFIG.LOGIN_REQUIRED_MESSAGE.get()),
                                    Util.NIL_UUID);
                        } else {
                            runAutoRegister(player, username, uuid);
                        }
                    })
                    .exceptionally(e -> {
                        runAutoRegister(player, username, uuid);
                        return null;
                    });

            int timeout = AuthConfig.CONFIG.LOGIN_TIMEOUT.get();
            if (timeout > 0) {
                ScheduledFuture<?> task = scheduler.schedule(() -> {
                    timeoutTasks.remove(uuid);
                    if (!isAuthenticated(uuid) && player.connection != null) {
                        player.connection.disconnect(new TextComponent("§c[VonixCore] Authentication timeout"));
                    }
                }, timeout, TimeUnit.SECONDS);
                timeoutTasks.put(uuid, task);
            }
        } else if (AuthConfig.CONFIG.WARN_OF_AUTH.get()) {
            player.sendMessage(new TextComponent(AuthConfig.CONFIG.AUTH_WARNING_MESSAGE.get()), Util.NIL_UUID);
        }
    }

    private static void runAutoRegister(ServerPlayer player, String username, UUID uuid) {
        player.sendMessage(new TextComponent(AuthConfig.CONFIG.GENERATING_CODE_MESSAGE.get()), Util.NIL_UUID);
        setPendingRegistration(uuid);

        VonixNetworkAPI.generateRegistrationCode(username, uuid.toString())
                .thenAccept(response -> {
                    if (response.code != null) {
                        String msg = AuthConfig.CONFIG.REGISTRATION_CODE_MESSAGE.get().replace("{code}", response.code);
                        player.sendMessage(new TextComponent(msg), Util.NIL_UUID);

                        Component link = new TextComponent("§a§l[CLICK HERE] §6Open Registration")
                                .setStyle(Style.EMPTY
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL,
                                                AuthConfig.CONFIG.REGISTRATION_URL.get()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                new TextComponent("Open registration page"))));
                        player.sendMessage(link, Util.NIL_UUID);
                        player.sendMessage(new TextComponent("§7Or use: §e/register <password>"), Util.NIL_UUID);
                    } else if (response.already_registered) {
                        player.sendMessage(new TextComponent("§aAlready registered! Use §e/login <password>"),
                                Util.NIL_UUID);
                        playerStates.put(uuid, PlayerAuthState.UNAUTHENTICATED);
                    } else {
                        player.sendMessage(new TextComponent("§cRegistration failed. Try §e/register"), Util.NIL_UUID);
                        playerStates.put(uuid, PlayerAuthState.UNAUTHENTICATED);
                    }
                });
    }

    public static void onPlayerLeave(UUID uuid) {
        playerStates.remove(uuid);
        playerTokens.remove(uuid);
        ScheduledFuture<?> task = timeoutTasks.remove(uuid);
        if (task != null)
            task.cancel(false);
    }

    public static void setAuthenticated(UUID uuid, String token) {
        playerStates.put(uuid, PlayerAuthState.AUTHENTICATED);
        if (token != null)
            playerTokens.put(uuid, token);
        ScheduledFuture<?> task = timeoutTasks.remove(uuid);
        if (task != null)
            task.cancel(false);
        VonixCore.LOGGER.info("[Auth] Player {} authenticated", uuid);
    }

    public static void setPendingRegistration(UUID uuid) {
        playerStates.put(uuid, PlayerAuthState.PENDING_REGISTRATION);
    }

    public static boolean isAuthenticated(UUID uuid) {
        return playerStates.getOrDefault(uuid, PlayerAuthState.UNAUTHENTICATED) == PlayerAuthState.AUTHENTICATED;
    }

    public static boolean shouldFreeze(UUID uuid) {
        return freezeEnabled && !isAuthenticated(uuid);
    }

    public static void clearAll() {
        playerStates.clear();
        playerTokens.clear();
        timeoutTasks.values().forEach(t -> t.cancel(false));
        timeoutTasks.clear();
    }

    public static void shutdown() {
        try {
            // Cancel all pending timeout tasks first
            timeoutTasks.values().forEach(t -> t.cancel(false));
            timeoutTasks.clear();

            scheduler.shutdown();
            // Use shorter timeout to prevent blocking server shutdown
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                VonixCore.LOGGER.warn("[Auth] Scheduler did not terminate in time, forcing shutdown...");
                scheduler.shutdownNow();
                // Brief wait for forced shutdown
                if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    VonixCore.LOGGER.error("[Auth] Scheduler could not be terminated!");
                }
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
