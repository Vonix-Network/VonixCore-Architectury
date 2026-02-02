package network.vonix.vonixcore.discord;

import network.vonix.vonixcore.VonixCore;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages linked Discord and Minecraft accounts.
 */
public class LinkedAccountsManager {

    private final Path dataFile;
    private final Map<String, LinkedAccount> byDiscordId = new ConcurrentHashMap<>();
    private final Map<UUID, LinkedAccount> byMinecraftUuid = new ConcurrentHashMap<>();
    private final Map<String, PendingLink> pendingLinks = new ConcurrentHashMap<>();

    public LinkedAccountsManager(Path configDir) throws IOException {
        this.dataFile = configDir.resolve("vonixcore-linked-accounts.yml");
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
            List<Map<String, Object>> accounts = (List<Map<String, Object>>) data.get("accounts");
            if (accounts != null) {
                for (Map<String, Object> account : accounts) {
                    String discordId = (String) account.get("discordId");
                    String minecraftUuid = (String) account.get("minecraftUuid");
                    String minecraftName = (String) account.get("minecraftName");
                    String discordName = (String) account.get("discordName");

                    LinkedAccount linked = new LinkedAccount(discordId, UUID.fromString(minecraftUuid), minecraftName, discordName);
                    byDiscordId.put(discordId, linked);
                    byMinecraftUuid.put(linked.minecraftUuid, linked);
                }
            }
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Failed to load linked accounts: {}", e.getMessage());
        }
    }

    private void save() {
        try {
            Map<String, Object> data = new HashMap<>();
            List<Map<String, Object>> accounts = new ArrayList<>();

            for (LinkedAccount account : byDiscordId.values()) {
                Map<String, Object> acc = new HashMap<>();
                acc.put("discordId", account.discordId);
                acc.put("minecraftUuid", account.minecraftUuid.toString());
                acc.put("minecraftName", account.minecraftName);
                acc.put("discordName", account.discordName);
                accounts.add(acc);
            }

            data.put("accounts", accounts);

            Yaml yaml = new Yaml();
            Files.writeString(dataFile, yaml.dump(data));
        } catch (Exception e) {
            VonixCore.LOGGER.error("[Discord] Failed to save linked accounts: {}", e.getMessage());
        }
    }

    public String generateLinkCode(UUID minecraftUuid, String minecraftName) {
        String code = String.format("%06d", new Random().nextInt(1000000));
        pendingLinks.put(code, new PendingLink(minecraftUuid, minecraftName, System.currentTimeMillis()));
        return code;
    }

    public LinkResult verifyAndLink(String code, String discordId, String discordName) {
        PendingLink pending = pendingLinks.remove(code);
        if (pending == null) {
            return new LinkResult(false, "Invalid or expired code.");
        }

        // Check expiry (5 minutes)
        if (System.currentTimeMillis() - pending.timestamp > 300000) {
            return new LinkResult(false, "Code has expired. Please generate a new one.");
        }

        // Check if already linked
        if (byDiscordId.containsKey(discordId)) {
            return new LinkResult(false, "Your Discord account is already linked.");
        }
        if (byMinecraftUuid.containsKey(pending.minecraftUuid)) {
            return new LinkResult(false, "This Minecraft account is already linked.");
        }

        LinkedAccount account = new LinkedAccount(discordId, pending.minecraftUuid, pending.minecraftName, discordName);
        byDiscordId.put(discordId, account);
        byMinecraftUuid.put(pending.minecraftUuid, account);
        save();

        return new LinkResult(true, "Successfully linked " + pending.minecraftName + " to Discord!");
    }

    public boolean unlinkDiscord(String discordId) {
        LinkedAccount account = byDiscordId.remove(discordId);
        if (account != null) {
            byMinecraftUuid.remove(account.minecraftUuid);
            save();
            return true;
        }
        return false;
    }

    public boolean unlinkMinecraft(UUID minecraftUuid) {
        LinkedAccount account = byMinecraftUuid.remove(minecraftUuid);
        if (account != null) {
            byDiscordId.remove(account.discordId);
            save();
            return true;
        }
        return false;
    }

    public int getLinkedCount() {
        return byDiscordId.size();
    }

    public record LinkedAccount(String discordId, UUID minecraftUuid, String minecraftName, String discordName) {}
    public record PendingLink(UUID minecraftUuid, String minecraftName, long timestamp) {}
    public record LinkResult(boolean success, String message) {}
}
