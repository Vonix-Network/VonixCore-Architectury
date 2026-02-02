package network.vonix.vonixcore.auth.integrations;

import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.auth.api.VonixNetworkAPI;
import network.vonix.vonixcore.config.AuthConfig;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * LuckPerms integration for rank synchronization from Vonix Network.
 * Ported from Forge for 1:1 parity with reflective loading.
 */
public class LuckPermsIntegration {
    private static boolean available = false;
    private static Object luckPermsApi = null;

    public static boolean initialize() {
        if (!AuthConfig.getInstance().isLuckPermsSyncEnabled()) {
            VonixCore.LOGGER.info("[Auth] LuckPerms sync disabled in config");
            return false;
        }

        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            luckPermsApi = providerClass.getMethod("get").invoke(null);
            available = true;
            VonixCore.LOGGER.info("[Auth] LuckPerms integration enabled");
            return true;
        } catch (Exception e) {
            VonixCore.LOGGER.info("[Auth] LuckPerms not found - rank sync disabled");
            return false;
        }
    }

    public static CompletableFuture<Void> synchronizeRank(UUID uuid, VonixNetworkAPI.LoginResponse.User user) {
        if (!available || luckPermsApi == null || user == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                // Get protected admin ranks
                Set<String> protectedRanks = Arrays.stream(AuthConfig.getInstance().getAdminRankIds().split(","))
                        .map(String::trim)
                        .map(String::toLowerCase)
                        .collect(Collectors.toSet());

                // Use reflection to interact with LuckPerms API
                Class<?> luckPermsClass = luckPermsApi.getClass();
                Object userManager = luckPermsClass.getMethod("getUserManager").invoke(luckPermsApi);

                // Load user
                Object lpUserFuture = userManager.getClass().getMethod("loadUser", UUID.class).invoke(userManager,
                        uuid);
                Object lpUser = lpUserFuture.getClass().getMethod("join").invoke(lpUserFuture);

                if (lpUser == null) {
                    VonixCore.LOGGER.warn("[Auth] Could not load LuckPerms user for {}", uuid);
                    return;
                }

                // Check if user has protected ranks
                Object primaryGroup = lpUser.getClass().getMethod("getPrimaryGroup").invoke(lpUser);
                if (protectedRanks.contains(primaryGroup.toString().toLowerCase())) {
                    VonixCore.LOGGER.debug("[Auth] User {} has protected rank, skipping sync", uuid);
                    return;
                }

                // Apply donation rank if present
                if (user.donation_rank != null && user.donation_rank.name != null) {
                    String rankName = user.donation_rank.name.toLowerCase().replace(" ", "_");
                    VonixCore.LOGGER.info("[Auth] Syncing rank {} for player {}", rankName, uuid);

                    // This would need more complex LuckPerms API calls to actually set the group
                    // For now, just log the intent
                    VonixCore.LOGGER.info("[Auth] Would set group '{}' for {}", rankName, uuid);
                }
            } catch (Exception e) {
                VonixCore.LOGGER.error("[Auth] Error syncing LuckPerms rank for {}: {}", uuid, e.getMessage());
            }
        });
    }
}
