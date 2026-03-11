package network.vonix.vonixcore.auth.integrations;

import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.AuthConfig;
import network.vonix.vonixcore.auth.api.VonixNetworkAPI;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * LuckPerms integration for rank synchronization from Vonix Network.
 * Handles both login-based rank sync and API-based rank checks.
 */
public class LuckPermsIntegration {
    private static boolean available = false;
    private static Object luckPermsApi = null;

    public static boolean initialize() {
        if (!AuthConfig.CONFIG.ENABLE_LUCKPERMS_SYNC.get()) {
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

    public static boolean isAvailable() {
        return available && luckPermsApi != null;
    }

    /**
     * Synchronize rank from login response (when player authenticates).
     */
    public static CompletableFuture<Void> synchronizeRank(UUID uuid, VonixNetworkAPI.LoginResponse.User user) {
        if (!available || luckPermsApi == null || user == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                if (user.donation_rank != null && user.donation_rank.name != null) {
                    String rankName = user.donation_rank.name.toLowerCase().replace(" ", "_");
                    applyGroup(uuid, rankName);
                }
            } catch (Exception e) {
                VonixCore.LOGGER.error("[Auth] Error syncing LuckPerms rank for {}: {}", uuid, e.getMessage());
            }
        });
    }

    /**
     * Synchronize rank by directly specifying a LuckPerms group name.
     * Used by the rank-check API flow (no login required).
     */
    public static CompletableFuture<Void> synchronizeRankByGroup(UUID uuid, String groupName) {
        if (!available || luckPermsApi == null || groupName == null || groupName.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                applyGroup(uuid, groupName.toLowerCase().replace(" ", "_"));
            } catch (Exception e) {
                VonixCore.LOGGER.error("[Auth] Error applying LuckPerms group for {}: {}", uuid, e.getMessage());
            }
        });
    }

    /**
     * Apply a LuckPerms group to a player via reflection.
     * Removes previous donation groups before applying the new one.
     */
    private static void applyGroup(UUID uuid, String groupName) {
        try {
            // Get protected admin ranks that should never be overwritten
            Set<String> protectedRanks = Arrays.stream(AuthConfig.CONFIG.ADMIN_RANK_IDS.get().split(","))
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());

            // Known donation group names (lowercase)
            Set<String> donationGroups = Set.of("supporter", "patron", "omega", "legend");

            Class<?> luckPermsClass = luckPermsApi.getClass();
            Object userManager = luckPermsClass.getMethod("getUserManager").invoke(luckPermsApi);

            // Load the LuckPerms user
            Object lpUserFuture = userManager.getClass().getMethod("loadUser", UUID.class).invoke(userManager, uuid);
            Object lpUser = ((CompletableFuture<?>) lpUserFuture).get(5, java.util.concurrent.TimeUnit.SECONDS);

            if (lpUser == null) {
                VonixCore.LOGGER.warn("[Auth] Could not load LuckPerms user for {}", uuid);
                return;
            }

            // Check if user has a protected rank — skip if so
            Object primaryGroup = lpUser.getClass().getMethod("getPrimaryGroup").invoke(lpUser);
            if (protectedRanks.contains(primaryGroup.toString().toLowerCase())) {
                VonixCore.LOGGER.debug("[Auth] User {} has protected rank '{}', skipping sync", uuid, primaryGroup);
                return;
            }

            // Get the Node builder class via reflection
            Class<?> nodeClass = Class.forName("net.luckperms.api.node.Node");
            Class<?> nodeBuilderClass = Class.forName("net.luckperms.api.node.NodeType");

            // Use InheritanceNode.builder(groupName) via reflection
            Class<?> inheritanceNodeClass = Class.forName("net.luckperms.api.node.types.InheritanceNode");
            Object inheritanceNodeBuilder = inheritanceNodeClass.getMethod("builder", String.class).invoke(null, groupName);
            Object newNode = inheritanceNodeBuilder.getClass().getMethod("build").invoke(inheritanceNodeBuilder);

            // Get the user's data (for modifications)
            Object userData = lpUser.getClass().getMethod("data").invoke(lpUser);

            // Remove all existing donation groups first
            for (String donGroup : donationGroups) {
                try {
                    Object removeBuilder = inheritanceNodeClass.getMethod("builder", String.class).invoke(null, donGroup);
                    Object removeNode = removeBuilder.getClass().getMethod("build").invoke(removeBuilder);
                    userData.getClass().getMethod("remove", nodeClass).invoke(userData, removeNode);
                } catch (Exception ignored) {
                    // Group may not exist, that's fine
                }
            }

            // Add the new donation group
            userData.getClass().getMethod("add", nodeClass).invoke(userData, newNode);

            // Save the user
            Object saveFuture = userManager.getClass().getMethod("saveUser", lpUser.getClass().getInterfaces()[0]).invoke(userManager, lpUser);
            ((CompletableFuture<?>) saveFuture).get(5, java.util.concurrent.TimeUnit.SECONDS);

            VonixCore.LOGGER.info("[Auth] Applied LuckPerms group '{}' to player {}", groupName, uuid);

        } catch (java.util.concurrent.TimeoutException e) {
            VonixCore.LOGGER.warn("[Auth] LuckPerms operation timed out for {}, skipping sync", uuid);
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Auth] Error applying LuckPerms group for {}: {}", uuid, e.getMessage());
        }
    }
}
