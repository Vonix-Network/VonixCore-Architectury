package network.vonix.vonixcore.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.EssentialsConfig;
import network.vonix.vonixcore.economy.EconomyManager;
import network.vonix.vonixcore.homes.HomeManager;
import network.vonix.vonixcore.teleport.TeleportManager;
import network.vonix.vonixcore.warps.WarpManager;

import java.util.List;

/**
 * Comprehensive command registration for all VonixCore features.
 */
public class VonixCoreCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Core VonixCore command
        registerCoreCommand(dispatcher);

        // Essentials commands
        if (EssentialsConfig.getInstance().isEnabled()) {
            if (EssentialsConfig.getInstance().isHomesEnabled()) {
                registerHomeCommands(dispatcher);
            }
            if (EssentialsConfig.getInstance().isWarpsEnabled()) {
                registerWarpCommands(dispatcher);
            }
            if (EssentialsConfig.getInstance().isTpaEnabled()) {
                registerTeleportCommands(dispatcher);
            }
            if (EssentialsConfig.getInstance().isEconomyEnabled()) {
                registerEconomyCommands(dispatcher);
            }
        }
    }

    private static void registerCoreCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vonixcore")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("§6VonixCore v" + VonixCore.VERSION), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("§7Use /vonixcore help for commands"), false);
                    return 1;
                })
                .then(Commands.literal("version")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("§6VonixCore§f Version: §a" + VonixCore.VERSION), false);
                            ctx.getSource().sendSuccess(() -> Component.literal("§7Platform: Fabric 1.20.1"), false);
                            return 1;
                        }))
                .then(Commands.literal("help")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal("§6=== VonixCore Commands ==="), false);
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("§e/vonixcore version §7- Show version"), false);
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("§e/vonixcore reload §7- Reload configs"), false);
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("§e/vonixcore status §7- Show module status"), false);
                            return 1;
                        }))
                .then(Commands.literal("status")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> {
                            VonixCore mod = VonixCore.getInstance();
                            ctx.getSource().sendSuccess(() -> Component.literal("§6=== VonixCore Status ==="), false);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§7Essentials: " + (mod.isEssentialsEnabled() ? "§aEnabled" : "§cDisabled")),
                                    false);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§7Protection: " + (mod.isProtectionEnabled() ? "§aEnabled" : "§cDisabled")),
                                    false);
                            ctx.getSource()
                                    .sendSuccess(() -> Component.literal(
                                            "§7Discord: " + (mod.isDiscordEnabled() ? "§aEnabled" : "§cDisabled")),
                                            false);
                            ctx.getSource()
                                    .sendSuccess(() -> Component.literal(
                                            "§7Claims: " + (mod.isClaimsEnabled() ? "§aEnabled" : "§cDisabled")),
                                            false);
                            return 1;
                        }))
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(3))
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("§aConfiguration reload not yet implemented"), false);
                            return 1;
                        })));
    }

    private static void registerHomeCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /sethome [name]
        dispatcher.register(Commands.literal("sethome")
                .executes(ctx -> setHome(ctx.getSource(), "home"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> setHome(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));

        // /home [name]
        dispatcher.register(Commands.literal("home")
                .executes(ctx -> teleportHome(ctx.getSource(), "home"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> teleportHome(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));

        // /delhome <name>
        dispatcher.register(Commands.literal("delhome")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> deleteHome(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));

        // /homes
        dispatcher.register(Commands.literal("homes")
                .executes(ctx -> listHomes(ctx.getSource())));
    }

    private static int setHome(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cThis command can only be used by players"));
            return 0;
        }

        if (HomeManager.getInstance().setHome(player, name)) {
            source.sendSuccess(() -> Component.literal("§aHome '" + name + "' set!"), false);
            return 1;
        } else {
            source.sendFailure(Component.literal("§cCouldn't set home. You may have reached your limit."));
            return 0;
        }
    }

    private static int teleportHome(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cThis command can only be used by players"));
            return 0;
        }

        HomeManager.Home home = HomeManager.getInstance().getHome(player.getUUID(), name);
        if (home == null) {
            source.sendFailure(Component.literal("§cHome '" + name + "' not found"));
            return 0;
        }

        // Get the world
        ResourceLocation worldId = ResourceLocation.tryParse(home.world());
        if (worldId == null) {
            source.sendFailure(Component.literal("§cInvalid world"));
            return 0;
        }

        ServerLevel level = source.getServer().getLevel(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        worldId));
        if (level == null) {
            source.sendFailure(Component.literal("§cWorld not found"));
            return 0;
        }

        TeleportManager.getInstance().teleportPlayer(player, level, home.x(), home.y(), home.z(), home.yaw(),
                home.pitch());
        source.sendSuccess(() -> Component.literal("§aTeleported to home '" + name + "'"), false);
        return 1;
    }

    private static int deleteHome(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cThis command can only be used by players"));
            return 0;
        }

        if (HomeManager.getInstance().deleteHome(player.getUUID(), name)) {
            source.sendSuccess(() -> Component.literal("§aHome '" + name + "' deleted"), false);
            return 1;
        } else {
            source.sendFailure(Component.literal("§cHome '" + name + "' not found"));
            return 0;
        }
    }

    private static int listHomes(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cThis command can only be used by players"));
            return 0;
        }

        List<HomeManager.Home> homes = HomeManager.getInstance().getHomes(player.getUUID());
        if (homes.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7You have no homes set. Use /sethome to create one."), false);
        } else {
            source.sendSuccess(
                    () -> Component.literal(
                            "§6Your Homes (" + homes.size() + "/" + VonixCore.getInstance().getMaxHomes() + "):"),
                    false);
            for (HomeManager.Home home : homes) {
                source.sendSuccess(() -> Component.literal("§e- " + home.name() + " §7(" + home.world() + ")"), false);
            }
        }
        return 1;
    }

    private static void registerWarpCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /warp <name>
        dispatcher.register(Commands.literal("warp")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> teleportWarp(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));

        // /warps
        dispatcher.register(Commands.literal("warps")
                .executes(ctx -> listWarps(ctx.getSource())));

        // /setwarp <name> (op only)
        dispatcher.register(Commands.literal("setwarp")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> setWarp(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));

        // /delwarp <name> (op only)
        dispatcher.register(Commands.literal("delwarp")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> deleteWarp(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));
    }

    private static int teleportWarp(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cThis command can only be used by players"));
            return 0;
        }

        WarpManager.Warp warp = WarpManager.getInstance().getWarp(name);
        if (warp == null) {
            source.sendFailure(Component.literal("§cWarp '" + name + "' not found"));
            return 0;
        }

        ResourceLocation worldId = ResourceLocation.tryParse(warp.world());
        if (worldId == null) {
            source.sendFailure(Component.literal("§cInvalid world"));
            return 0;
        }

        ServerLevel level = source.getServer().getLevel(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        worldId));
        if (level == null) {
            source.sendFailure(Component.literal("§cWorld not found"));
            return 0;
        }

        TeleportManager.getInstance().teleportPlayer(player, level, warp.x(), warp.y(), warp.z(), warp.yaw(),
                warp.pitch());
        source.sendSuccess(() -> Component.literal("§aTeleported to warp '" + name + "'"), false);
        return 1;
    }

    private static int setWarp(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cThis command can only be used by players"));
            return 0;
        }

        if (WarpManager.getInstance().setWarp(name, player)) {
            source.sendSuccess(() -> Component.literal("§aWarp '" + name + "' created!"), false);
            return 1;
        } else {
            source.sendFailure(Component.literal("§cFailed to create warp"));
            return 0;
        }
    }

    private static int deleteWarp(CommandSourceStack source, String name) {
        if (WarpManager.getInstance().deleteWarp(name)) {
            source.sendSuccess(() -> Component.literal("§aWarp '" + name + "' deleted"), false);
            return 1;
        } else {
            source.sendFailure(Component.literal("§cWarp '" + name + "' not found"));
            return 0;
        }
    }

    private static int listWarps(CommandSourceStack source) {
        List<WarpManager.Warp> warps = WarpManager.getInstance().getWarps();
        if (warps.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7No warps available"), false);
        } else {
            source.sendSuccess(() -> Component.literal("§6Available Warps:"), false);
            for (WarpManager.Warp warp : warps) {
                source.sendSuccess(() -> Component.literal("§e- " + warp.name()), false);
            }
        }
        return 1;
    }

    private static void registerTeleportCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /tpa <player>
        dispatcher.register(Commands.literal("tpa")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer sender = ctx.getSource().getPlayerOrException();
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");

                            if (TeleportManager.getInstance().sendTpaRequest(sender, target, false)) {
                                ctx.getSource().sendSuccess(
                                        () -> Component
                                                .literal("§aTPA request sent to " + target.getName().getString()),
                                        false);
                                target.sendSystemMessage(Component.literal("§e" + sender.getName().getString()
                                        + " §7wants to teleport to you. Use §e/tpaccept §7or §e/tpdeny"));
                                return 1;
                            } else {
                                ctx.getSource()
                                        .sendFailure(Component.literal("§cPlayer already has a pending request"));
                                return 0;
                            }
                        })));

        // /tpahere <player>
        dispatcher.register(Commands.literal("tpahere")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer sender = ctx.getSource().getPlayerOrException();
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");

                            if (TeleportManager.getInstance().sendTpaRequest(sender, target, true)) {
                                ctx.getSource().sendSuccess(
                                        () -> Component
                                                .literal("§aTPA request sent to " + target.getName().getString()),
                                        false);
                                target.sendSystemMessage(Component.literal("§e" + sender.getName().getString()
                                        + " §7wants you to teleport to them. Use §e/tpaccept §7or §e/tpdeny"));
                                return 1;
                            } else {
                                ctx.getSource()
                                        .sendFailure(Component.literal("§cPlayer already has a pending request"));
                                return 0;
                            }
                        })));

        // /tpaccept
        dispatcher.register(Commands.literal("tpaccept")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    if (TeleportManager.getInstance().acceptTpaRequest(player, ctx.getSource().getServer())) {
                        ctx.getSource().sendSuccess(() -> Component.literal("§aTeleport request accepted"), false);
                        return 1;
                    } else {
                        ctx.getSource().sendFailure(Component.literal("§cNo pending teleport request"));
                        return 0;
                    }
                }));

        // /tpdeny
        dispatcher.register(Commands.literal("tpdeny")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    if (TeleportManager.getInstance().denyTpaRequest(player)) {
                        ctx.getSource().sendSuccess(() -> Component.literal("§aTeleport request denied"), false);
                        return 1;
                    } else {
                        ctx.getSource().sendFailure(Component.literal("§cNo pending teleport request"));
                        return 0;
                    }
                }));

        // /back
        dispatcher.register(Commands.literal("back")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    TeleportManager.TeleportLocation loc = TeleportManager.getInstance()
                            .getLastLocation(player.getUUID());

                    if (loc == null) {
                        ctx.getSource().sendFailure(Component.literal("§c[VC] No location to return to."));
                        return 0;
                    }

                    // Check death back delay (cooldown ONLY applies to deaths)
                    if (loc.isDeath()) {
                        int delaySeconds = EssentialsConfig.getInstance().getDeathBackDelay();
                        if (delaySeconds > 0) {
                            long elapsed = (System.currentTimeMillis() - loc.timestamp()) / 1000;
                            if (elapsed < delaySeconds) {
                                ctx.getSource().sendFailure(Component.literal("§c[VC] You must wait " +
                                        formatTime((int) (delaySeconds - elapsed)) + " before returning to your death location."));
                                return 0;
                            }
                        }
                    }

                    ResourceLocation worldId = ResourceLocation.tryParse(loc.world());
                    if (worldId == null) {
                        ctx.getSource().sendFailure(Component.literal("§c[VC] Invalid world"));
                        return 0;
                    }

                    ServerLevel level = ctx.getSource().getServer().getLevel(
                            net.minecraft.resources.ResourceKey
                                    .create(net.minecraft.core.registries.Registries.DIMENSION, worldId));
                    if (level == null) {
                        ctx.getSource().sendFailure(Component.literal("§c[VC] World no longer exists"));
                        return 0;
                    }

                    TeleportManager.getInstance().teleportPlayer(player, level, loc.x(), loc.y(), loc.z(), loc.yaw(),
                            loc.pitch());
                    ctx.getSource().sendSuccess(() -> Component.literal("§a[VC] Returned to previous location."), false);
                    return 1;
                }));
    }

    private static void registerEconomyCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /balance or /bal
        dispatcher.register(Commands.literal("balance")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    EconomyManager.getInstance().getBalance(player.getUUID()).thenAccept(balance -> {
                        ctx.getSource().sendSuccess(
                                () -> Component.literal("§6Balance: §a" + EconomyManager.getInstance().format(balance)),
                                false);
                    });
                    return 1;
                }));

        dispatcher.register(Commands.literal("bal")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    EconomyManager.getInstance().getBalance(player.getUUID()).thenAccept(balance -> {
                        ctx.getSource().sendSuccess(
                                () -> Component.literal("§6Balance: §a" + EconomyManager.getInstance().format(balance)),
                                false);
                    });
                    return 1;
                }));

        // /pay <player> <amount>
        dispatcher.register(Commands.literal("pay")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands
                                .argument("amount", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.01))
                                .executes(ctx -> {
                                    ServerPlayer sender = ctx.getSource().getPlayerOrException();
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    double amount = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx,
                                            "amount");

                                    if (sender.getUUID().equals(target.getUUID())) {
                                        ctx.getSource().sendFailure(Component.literal("§cYou can't pay yourself"));
                                        return 0;
                                    }

                                    EconomyManager.getInstance().transfer(sender.getUUID(), target.getUUID(), amount)
                                        .thenAccept(success -> {
                                            if (success) {
                                                String formatted = EconomyManager.getInstance().format(amount);
                                                ctx.getSource()
                                                        .sendSuccess(() -> Component.literal(
                                                                "§aPaid " + formatted + " to " + target.getName().getString()),
                                                                false);
                                                target.sendSystemMessage(Component.literal(
                                                        "§aReceived " + formatted + " from " + sender.getName().getString()));
                                            } else {
                                                ctx.getSource().sendFailure(Component.literal("§cInsufficient funds"));
                                            }
                                        });
                                    return 1;
                                }))));

        // /baltop
        dispatcher.register(Commands.literal("baltop")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("§6=== Balance Leaderboard ==="), false);
                    EconomyManager.getInstance().getTopBalances(10).thenAccept(top -> {
                        int rank = 1;
                        for (var entry : top) {
                            String formatted = EconomyManager.getInstance().format(entry.balance());
                            final int finalRank = rank;
                            ctx.getSource().sendSuccess(
                                    () -> Component
                                            .literal("§e#" + finalRank + " §7" + entry.uuid() + " §f- §a" + formatted),
                                    false);
                            rank++;
                        }
                    });
                    return 1;
                }));
    }

    private static String formatTime(int seconds) {
        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        } else {
            return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        }
    }
}
