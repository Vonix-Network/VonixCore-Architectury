package network.vonix.vonixcore.auth;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.vonixcore.auth.api.VonixNetworkAPI;
import network.vonix.vonixcore.auth.integrations.LuckPermsIntegration;
import network.vonix.vonixcore.config.AuthConfig;

/**
 * Authentication commands: /login, /register
 */
public class AuthCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /register <username> <email> <display_name> <password>
        dispatcher.register(Commands.literal("register")
                .then(Commands.argument("username", StringArgumentType.word())
                .then(Commands.argument("email", StringArgumentType.word())
                .then(Commands.argument("display_name", StringArgumentType.word())
                .then(Commands.argument("password", StringArgumentType.greedyString())
                        .executes(AuthCommands::registerWithDetails))))));

        // /link
        dispatcher.register(Commands.literal("link")
                .executes(AuthCommands::registerCode));

        // /login <password>
        dispatcher.register(Commands.literal("login")
                .then(Commands.argument("password", StringArgumentType.greedyString())
                        .executes(AuthCommands::login)));
    }

    private static int registerCode(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(new TextComponent("§cPlayers only"));
            return 0;
        }

        if (AuthenticationManager.isAuthenticated(player.getUUID())) {
            player.sendMessage(new TextComponent(AuthConfig.CONFIG.ALREADY_AUTHENTICATED_MESSAGE.get()), player.getUUID());
            return 0;
        }

        String username = player.getName().getString();
        String uuid = player.getUUID().toString();

        player.sendMessage(new TextComponent(AuthConfig.CONFIG.GENERATING_CODE_MESSAGE.get()), player.getUUID());
        AuthenticationManager.setPendingRegistration(player.getUUID());

        VonixNetworkAPI.generateRegistrationCode(username, uuid)
                .thenAccept(response -> {
                    if (response.code != null) {
                        String msg = AuthConfig.CONFIG.REGISTRATION_CODE_MESSAGE.get().replace("{code}", response.code);
                        player.sendMessage(new TextComponent(msg), player.getUUID());

                        Component link = new TextComponent("§a§l[CLICK HERE] §6Open Registration")
                                .setStyle(Style.EMPTY
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL,
                                                AuthConfig.CONFIG.REGISTRATION_URL.get()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                new TextComponent("Open website"))));
                        player.sendMessage(link, player.getUUID());
                        player.sendMessage(new TextComponent("§7Or use: §e/link §7or §e/register <Username> <Email> <DisplayName> <Password>"), player.getUUID());
                    } else if (response.already_registered) {
                        player.sendMessage(new TextComponent("§eAlready registered! Use §a/login <password>"), player.getUUID());
                    } else {
                        player.sendMessage(new TextComponent("§cRegistration failed: "
                                + (response.error != null ? response.error : "Unknown error")), player.getUUID());
                    }
                });

        return 1;
    }

    private static int registerWithDetails(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(new TextComponent("§cPlayers only"));
            return 0;
        }

        if (AuthenticationManager.isAuthenticated(player.getUUID())) {
            player.sendMessage(new TextComponent(AuthConfig.CONFIG.ALREADY_AUTHENTICATED_MESSAGE.get()), player.getUUID());
            return 0;
        }

        String platformUsername = StringArgumentType.getString(ctx, "username");
        String email = StringArgumentType.getString(ctx, "email");
        String displayName = StringArgumentType.getString(ctx, "display_name");
        String password = StringArgumentType.getString(ctx, "password");
        
        String mcUsername = player.getName().getString();
        String uuid = player.getUUID().toString();

        player.sendMessage(new TextComponent("§6⏳ §7Registering account..."), player.getUUID());
        AuthenticationManager.setPendingRegistration(player.getUUID());

        VonixNetworkAPI.registerPlayerWithDetails(mcUsername, uuid, platformUsername, email, displayName, password)
                .thenAccept(response -> {
                    if (response.success) {
                        AuthenticationManager.setAuthenticated(player.getUUID(), response.token);
                        player.sendMessage(new TextComponent("§a§l✓ §7Account created! Welcome, §e" + platformUsername), player.getUUID());

                        if (response.user != null) {
                            LuckPermsIntegration.synchronizeRank(player.getUUID(), response.user);
                        }
                    } else {
                        String error = response.error != null ? response.error : "Unknown error";
                        if (error.toLowerCase().contains("already registered")) {
                            player.sendMessage(
                                    new TextComponent("§eAlready registered! Use §a/login <password>"), player.getUUID());
                        } else {
                            player.sendMessage(new TextComponent("§c§l✗ §7Registration failed: §c" + error), player.getUUID());
                        }
                    }
                });

        return 1;
    }

    private static int login(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(new TextComponent("§cPlayers only"));
            return 0;
        }

        if (AuthenticationManager.isAuthenticated(player.getUUID())) {
            player.sendMessage(new TextComponent(AuthConfig.CONFIG.ALREADY_AUTHENTICATED_MESSAGE.get()), player.getUUID());
            return 0;
        }

        String password = StringArgumentType.getString(ctx, "password");
        String username = player.getName().getString();
        String uuid = player.getUUID().toString();

        player.sendMessage(new TextComponent(AuthConfig.CONFIG.AUTHENTICATING_MESSAGE.get()), player.getUUID());

        VonixNetworkAPI.loginPlayer(username, uuid, password)
                .thenAccept(response -> {
                    if (response.success) {
                        AuthenticationManager.setAuthenticated(player.getUUID(), response.token);
                        String msg = AuthConfig.CONFIG.AUTHENTICATION_SUCCESS_MESSAGE.get().replace("{username}", username);
                        player.sendMessage(new TextComponent(msg), player.getUUID());

                        if (response.user != null) {
                            LuckPermsIntegration.synchronizeRank(player.getUUID(), response.user)
                                    .thenRun(() -> {
                                        if (response.user.donation_rank != null) {
                                            player.sendMessage(new TextComponent(
                                                    "§6★ §7Rank synced: §e" + response.user.donation_rank.name), player.getUUID());
                                        }
                                    });
                        }
                    } else {
                        String error = response.error != null ? response.error : "Unknown error";
                        String msg = AuthConfig.CONFIG.LOGIN_FAILED_MESSAGE.get().replace("{error}", error);
                        player.sendMessage(new TextComponent(msg), player.getUUID());
                    }
                });

        return 1;
    }
}