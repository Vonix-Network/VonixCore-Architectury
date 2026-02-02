package network.vonix.vonixcore.discord;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.advancements.Advancement;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.DiscordConfig;

/**
 * Minecraft event handler for Discord integration.
 * Handles chat relay, player join/leave, death, and advancement events.
 */
@Mod.EventBusSubscriber(modid = VonixCore.MODID)
public class DiscordEventHandler {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // /discord command - show invite link
        dispatcher.register(
                Commands.literal("discord")
                        .requires(source -> source.hasPermission(0))
                        .executes(context -> {
                            String invite = DiscordConfig.CONFIG.inviteUrl.get();
                            CommandSourceStack source = context.getSource();

                            if (invite == null || invite.isEmpty()) {
                                source.sendSuccess(() -> Component.literal(
                                        "§cDiscord invite URL is not configured."), false);
                            } else {
                                MutableComponent clickable = Component
                                        .literal("Click Here to join the Discord!")
                                        .withStyle(style -> style
                                                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, invite))
                                                .withUnderlined(true)
                                                .withColor(ChatFormatting.AQUA));
                                source.sendSuccess(() -> clickable, false);
                            }
                            return 1;
                        }));

        // /vonix discord commands
        dispatcher.register(
                Commands.literal("vonix")
                        .then(Commands.literal("discord")
                                .then(Commands.literal("link")
                                        .executes(context -> {
                                            if (!DiscordConfig.CONFIG.enableAccountLinking.get()) {
                                                context.getSource().sendFailure(
                                                        Component.literal("§cAccount linking is disabled."));
                                                return 0;
                                            }

                                            ServerPlayer player = context.getSource().getPlayerOrException();
                                            String code = DiscordManager.getInstance().generateLinkCode(player);

                                            if (code != null) {
                                                int expiryMinutes = DiscordConfig.CONFIG.linkCodeExpiry.get() / 60;
                                                context.getSource().sendSuccess(() -> Component.literal(
                                                        "§aYour link code is: §e" + code + "\n" +
                                                                "§7Use §b/link " + code
                                                                + "§7 in Discord to link your account.\n" +
                                                                "§7Code expires in " + expiryMinutes + " minutes."),
                                                        false);
                                                return 1;
                                            } else {
                                                context.getSource().sendFailure(
                                                        Component.literal("§cFailed to generate link code."));
                                                return 0;
                                            }
                                        }))
                                .then(Commands.literal("unlink")
                                        .executes(context -> {
                                            if (!DiscordConfig.CONFIG.enableAccountLinking.get()) {
                                                context.getSource().sendFailure(
                                                        Component.literal("§cAccount linking is disabled."));
                                                return 0;
                                            }

                                            ServerPlayer player = context.getSource().getPlayerOrException();
                                            boolean success = DiscordManager.getInstance()
                                                    .unlinkAccount(player.getUUID());

                                            if (success) {
                                                context.getSource().sendSuccess(() -> Component.literal(
                                                        "§aYour Discord account has been unlinked."), false);
                                                return 1;
                                            } else {
                                                context.getSource().sendFailure(
                                                        Component
                                                                .literal("§cYou don't have a linked Discord account."));
                                                return 0;
                                            }
                                        }))
                                .then(Commands.literal("messages")
                                        .then(Commands.literal("enable")
                                                .executes(context -> {
                                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                                    DiscordManager.getInstance()
                                                            .setServerMessagesFiltered(player.getUUID(), false);
                                                    context.getSource().sendSuccess(() -> Component.literal(
                                                            "§aServer messages enabled!"), false);
                                                    return 1;
                                                }))
                                        .then(Commands.literal("disable")
                                                .executes(context -> {
                                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                                    DiscordManager.getInstance()
                                                            .setServerMessagesFiltered(player.getUUID(), true);
                                                    context.getSource().sendSuccess(() -> Component.literal(
                                                            "§cServer messages disabled!"), false);
                                                    return 1;
                                                }))
                                        .executes(context -> {
                                            ServerPlayer player = context.getSource().getPlayerOrException();
                                            boolean isFiltered = DiscordManager.getInstance()
                                                    .hasServerMessagesFiltered(player.getUUID());
                                            context.getSource().sendSuccess(() -> Component.literal(
                                                    "§7Server messages: " + (isFiltered ? "§cDisabled" : "§aEnabled")),
                                                    false);
                                            return 1;
                                        }))
                                .then(Commands.literal("events")
                                        .then(Commands.literal("enable")
                                                .executes(context -> {
                                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                                    DiscordManager.getInstance()
                                                            .setEventsFiltered(player.getUUID(), false);
                                                    context.getSource().sendSuccess(() -> Component.literal(
                                                            "§aEvent messages enabled!"), false);
                                                    return 1;
                                                }))
                                        .then(Commands.literal("disable")
                                                .executes(context -> {
                                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                                    DiscordManager.getInstance()
                                                            .setEventsFiltered(player.getUUID(), true);
                                                    context.getSource().sendSuccess(() -> Component.literal(
                                                            "§cEvent messages disabled!"), false);
                                                    return 1;
                                                }))
                                        .executes(context -> {
                                            ServerPlayer player = context.getSource().getPlayerOrException();
                                            boolean isFiltered = DiscordManager.getInstance()
                                                    .hasEventsFiltered(player.getUUID());
                                            context.getSource().sendSuccess(() -> Component.literal(
                                                    "§7Event messages: " + (isFiltered ? "§cDisabled" : "§aEnabled")),
                                                    false);
                                            return 1;
                                        }))
                                .then(Commands.literal("help")
                                        .executes(context -> {
                                            context.getSource().sendSuccess(() -> Component.literal(
                                                    "§6§l=== VonixCore Discord Commands ===\n" +
                                                            "§b/discord§7 - Show Discord invite link\n" +
                                                            "§b/vonix discord link§7 - Generate account link code\n" +
                                                            "§b/vonix discord unlink§7 - Unlink your Discord\n" +
                                                            "§b/vonix discord messages§7 - Toggle server messages\n" +
                                                            "§b/vonix discord events§7 - Toggle event messages\n" +
                                                            "§7Discord: §b/list§7 - Show online players"),
                                                    false);
                                            return 1;
                                        }))));
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onServerChat(ServerChatEvent event) {
        if (!DiscordManager.getInstance().isRunning()) {
            return;
        }

        ServerPlayer player = event.getPlayer();
        String username = player.getName().getString();
        String message = event.getRawText();

        DiscordManager.getInstance().sendMinecraftMessage(username, message);
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!DiscordManager.getInstance().isRunning()) {
            return;
        }

        ServerPlayer player = (ServerPlayer) event.getEntity();
        String username = player.getName().getString();

        DiscordManager.getInstance().sendJoinEmbed(username);
        scheduleStatusUpdate(player.getServer());
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!DiscordManager.getInstance().isRunning()) {
            return;
        }

        ServerPlayer player = (ServerPlayer) event.getEntity();
        String username = player.getName().getString();

        DiscordManager.getInstance().sendLeaveEmbed(username);
        scheduleStatusUpdate(player.getServer());
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (!DiscordManager.getInstance().isRunning()) {
            return;
        }

        if (!DiscordConfig.CONFIG.sendDeath.get()) {
            return;
        }

        String deathMessage = event.getSource().getLocalizedDeathMessage(player).getString();
        DiscordManager.getInstance().sendSystemMessage("💀 " + deathMessage);
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!DiscordManager.getInstance().isRunning()) {
            return;
        }

        ServerPlayer player = (ServerPlayer) event.getEntity();
        Advancement advancement = event.getAdvancement();

        if (advancement.getDisplay() == null) {
            return;
        }

        var display = advancement.getDisplay();
        if (!display.shouldAnnounceChat()) {
            return;
        }

        String username = player.getName().getString();
        String advancementTitle = display.getTitle().getString();
        String advancementDescription = display.getDescription().getString();

        DiscordManager.getInstance().sendAdvancementEmbed(
                username,
                advancementTitle,
                advancementDescription,
                display.getFrame().name());
    }

    private static void scheduleStatusUpdate(net.minecraft.server.MinecraftServer server) {
        if (server != null) {
            server.executeBlocking(() -> {
                try {
                    Thread.sleep(100);
                    DiscordManager.getInstance().updateBotStatus();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }
}
