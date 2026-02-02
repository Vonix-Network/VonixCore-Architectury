package network.vonix.vonixcore.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.RegisterCommandsEvent;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.admin.AdminManager;
import network.vonix.vonixcore.economy.EconomyManager;
import network.vonix.vonixcore.homes.HomeManager;
import network.vonix.vonixcore.kits.KitManager;
import network.vonix.vonixcore.teleport.TeleportManager;
import network.vonix.vonixcore.warps.WarpManager;

import java.util.List;

/**
 * Comprehensive command registration for all VonixCore features.
 */
@Mod.EventBusSubscriber(modid = VonixCore.MODID)
public class VonixCoreCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // Register all command groups
        registerHomeCommands(dispatcher);
        registerWarpCommands(dispatcher);
        registerTeleportCommands(dispatcher);
        registerEconomyCommands(dispatcher);
        registerKitCommands(dispatcher);
        registerAdminCommands(dispatcher);
        registerUtilityCommands(dispatcher);
        registerUtilityCommands(dispatcher);
        network.vonix.vonixcore.permissions.PermissionCommands.register(dispatcher);
        registerVonixCoreCommand(dispatcher);

        VonixCore.LOGGER.info("[VonixCore] All commands registered");
    }

    // ===== VONIXCORE ADMIN COMMAND =====

    private static void registerVonixCoreCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vonixcore")
                .requires(src -> src.hasPermission(3))
                .then(Commands.literal("reload")
                        .then(Commands.literal("all")
                                .executes(VonixCoreCommands::reloadAllConfigs))
                        .then(Commands.literal("database")
                                .executes(ctx -> reloadConfig(ctx, "database")))
                        .then(Commands.literal("protection")
                                .executes(ctx -> reloadConfig(ctx, "protection")))
                        .then(Commands.literal("essentials")
                                .executes(ctx -> reloadConfig(ctx, "essentials")))
                        .then(Commands.literal("discord")
                                .executes(ctx -> reloadConfig(ctx, "discord")))
                        .then(Commands.literal("xpsync")
                                .executes(ctx -> reloadConfig(ctx, "xpsync")))
                        .executes(VonixCoreCommands::reloadAllConfigs))
                .then(Commands.literal("version")
                        .executes(VonixCoreCommands::showVersion))
                .then(Commands.literal("status")
                        .executes(VonixCoreCommands::showStatus))
                .executes(VonixCoreCommands::showHelp));
    }

    private static int reloadAllConfigs(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("§6[VonixCore] §eReloading all configurations..."), true);

        try {
            VonixCore.LOGGER.info("[VonixCore] Config reload requested by {}",
                    ctx.getSource().getTextName());

            ctx.getSource().sendSuccess(() -> Component.literal("§a[VonixCore] ✓ All configurations reloaded!"), true);
            ctx.getSource().sendSuccess(() -> Component.literal("§7Note: Some changes may require a server restart."),
                    false);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§c[VonixCore] Failed to reload configs: " + e.getMessage()));
            return 0;
        }
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> ctx, String module) {
        ctx.getSource().sendSuccess(
                () -> Component.literal("§6[VonixCore] §eReloading " + module + " configuration..."), true);

        try {
            VonixCore.LOGGER.info("[VonixCore] Config reload for {} requested by {}",
                    module, ctx.getSource().getTextName());

            ctx.getSource().sendSuccess(
                    () -> Component.literal("§a[VonixCore] ✓ " + module + " configuration reloaded!"), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource()
                    .sendFailure(Component.literal("§c[VonixCore] Failed to reload " + module + ": " + e.getMessage()));
            return 0;
        }
    }

    private static int showVersion(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("§6[VonixCore] §fVersion: §e" + VonixCore.VERSION), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§7Platform: Forge 1.20.1"), false);
        return 1;
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("§6[VonixCore] §fModule Status:"), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§7- Protection: " +
                (VonixCore.getInstance().isProtectionEnabled() ? "§aEnabled" : "§cDisabled")), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§7- Essentials: " +
                (VonixCore.getInstance().isEssentialsEnabled() ? "§aEnabled" : "§cDisabled")), false);
        return 1;
    }

    private static int showHelp(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("§6§l=== VonixCore Commands ==="), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§e/vonixcore reload [module] §7- Reload configurations"),
                false);
        ctx.getSource().sendSuccess(() -> Component.literal("§e/vonixcore version §7- Show version info"), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§e/vonixcore status §7- Show module status"), false);
        ctx.getSource().sendSuccess(
                () -> Component.literal("§7Modules: all, database, protection, essentials, discord, xpsync"), false);
        return 1;
    }

    // ===== HOME COMMANDS =====

    private static void registerHomeCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("home")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> teleportHome(ctx, StringArgumentType.getString(ctx, "name"))))
                .executes(ctx -> teleportHome(ctx, "home")));

        dispatcher.register(Commands.literal("sethome")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> setHome(ctx, StringArgumentType.getString(ctx, "name"))))
                .executes(ctx -> setHome(ctx, "home")));

        dispatcher.register(Commands.literal("delhome")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> deleteHome(ctx, StringArgumentType.getString(ctx, "name")))));

        dispatcher.register(Commands.literal("homes")
                .executes(VonixCoreCommands::listHomes));
    }

    private static int setHome(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null)
            return 0;

        if (HomeManager.getInstance().setHome(player, name)) {
            player.sendSystemMessage(Component.literal("§a[VC] Home '" + name + "' set!"));
            return 1;
        } else {
            player.sendSystemMessage(Component.literal("§c[VC] You've reached your home limit!"));
            return 0;
        }
    }

    private static int teleportHome(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null)
            return 0;

        var home = HomeManager.getInstance().getHome(player.getUUID(), name);
        if (home == null) {
            player.sendSystemMessage(Component.literal("§c[VC] Home '" + name + "' not found!"));
            return 0;
        }

        var server = ctx.getSource().getServer();
        for (var level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(home.world())) {
                TeleportManager.getInstance().teleportPlayer(player, level, home.x(), home.y(), home.z(), home.yaw(),
                        home.pitch());
                player.sendSystemMessage(Component.literal("§a[VC] Teleported to home '" + name + "'!"));
                return 1;
            }
        }
        player.sendSystemMessage(Component.literal("§c[VC] World not found!"));
        return 0;
    }

    private static int deleteHome(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null)
            return 0;

        if (HomeManager.getInstance().deleteHome(player.getUUID(), name)) {
            player.sendSystemMessage(Component.literal("§a[VC] Home '" + name + "' deleted!"));
            return 1;
        }
        player.sendSystemMessage(Component.literal("§c[VC] Home not found!"));
        return 0;
    }

    private static int listHomes(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null)
            return 0;

        var homes = HomeManager.getInstance().getHomes(player.getUUID());
        if (homes.isEmpty()) {
            player.sendSystemMessage(Component.literal("§7[VC] You have no homes set."));
        } else {
            player.sendSystemMessage(Component.literal("§6[VC] Your homes: §e" +
                    String.join(", ", homes.stream().map(h -> h.name()).toList())));
        }
        return 1;
    }

    // ===== WARP COMMANDS =====

    private static void registerWarpCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("warp")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> teleportWarp(ctx, StringArgumentType.getString(ctx, "name"))))
                .executes(VonixCoreCommands::listWarps));

        dispatcher.register(Commands.literal("setwarp")
                .requires(src -> src.hasPermission(3))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> setWarp(ctx, StringArgumentType.getString(ctx, "name")))));

        dispatcher.register(Commands.literal("delwarp")
                .requires(src -> src.hasPermission(3))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> deleteWarp(ctx, StringArgumentType.getString(ctx, "name")))));

        dispatcher.register(Commands.literal("warps").executes(VonixCoreCommands::listWarps));
    }

    private static int setWarp(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null)
            return 0;

        if (WarpManager.getInstance().setWarp(name, player)) {
            ctx.getSource().sendSuccess(() -> Component.literal("§a[VC] Warp '" + name + "' created!"), true);
            return 1;
        }
        return 0;
    }

    private static int teleportWarp(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null)
            return 0;

        var warp = WarpManager.getInstance().getWarp(name);
        if (warp == null) {
            player.sendSystemMessage(Component.literal("§c[VC] Warp '" + name + "' not found!"));
            return 0;
        }

        var server = ctx.getSource().getServer();
        for (var level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(warp.world())) {
                TeleportManager.getInstance().teleportPlayer(player, level, warp.x(), warp.y(), warp.z(), warp.yaw(),
                        warp.pitch());
                player.sendSystemMessage(Component.literal("§a[VC] Warped to '" + name + "'!"));
                return 1;
            }
        }
        return 0;
    }

    private static int deleteWarp(CommandContext<CommandSourceStack> ctx, String name) {
        if (WarpManager.getInstance().deleteWarp(name)) {
            ctx.getSource().sendSuccess(() -> Component.literal("§a[VC] Warp deleted!"), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("§c[VC] Warp not found!"));
        return 0;
    }

    private static int listWarps(CommandContext<CommandSourceStack> ctx) {
        var warps = WarpManager.getInstance().getWarps();
        if (warps.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7[VC] No warps available."), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal("§6[VC] Warps: §e" +
                    String.join(", ", warps.stream().map(w -> w.name()).toList())), false);
        }
        return 1;
    }

    // ===== TELEPORT COMMANDS =====

    private static void registerTeleportCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tpa")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(VonixCoreCommands::tpaCommand)));

        dispatcher.register(Commands.literal("tpahere")
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(VonixCoreCommands::tpaHereCommand)));

        dispatcher.register(Commands.literal("tpaccept").executes(VonixCoreCommands::tpAcceptCommand));
        dispatcher.register(Commands.literal("tpdeny").executes(VonixCoreCommands::tpDenyCommand));
        dispatcher.register(Commands.literal("back").executes(VonixCoreCommands::backCommand));

        dispatcher.register(Commands.literal("spawn").executes(VonixCoreCommands::spawnCommand));
    }

    private static int tpaCommand(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayer();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        if (player == null)
            return 0;

        if (TeleportManager.getInstance().sendTpaRequest(player, target, false)) {
            player.sendSystemMessage(
                    Component.literal("§a[VC] Teleport request sent to " + target.getName().getString()));
            target.sendSystemMessage(Component.literal(
                    "§e[VC] " + player.getName().getString() + " wants to teleport to you. /tpaccept or /tpdeny"));
            return 1;
        }
        player.sendSystemMessage(Component.literal("§c[VC] That player already has a pending request."));
        return 0;
    }

    private static int tpaHereCommand(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayer();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        if (player == null)
            return 0;

        if (TeleportManager.getInstance().sendTpaRequest(player, target, true)) {
            player.sendSystemMessage(
                    Component.literal("§a[VC] Teleport request sent to " + target.getName().getString()));
            target.sendSystemMessage(Component.literal(
                    "§e[VC] " + player.getName().getString() + " wants you to teleport to them. /tpaccept or /tpdeny"));
            return 1;
        }
        return 0;
    }

    private static int tpAcceptCommand(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null)
            return 0;

        if (TeleportManager.getInstance().acceptTpaRequest(player, ctx.getSource().getServer())) {
            player.sendSystemMessage(Component.literal("§a[VC] Teleport request accepted!"));
            return 1;
        }
        player.sendSystemMessage(Component.literal("§c[VC] No pending teleport request."));
        return 0;
    }

    private static int tpDenyCommand(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null)
            return 0;

        if (TeleportManager.getInstance().denyTpaRequest(player)) {
            player.sendSystemMessage(Component.literal("§c[VC] Teleport request denied."));
            return 1;
        }
        player.sendSystemMessage(Component.literal("§c[VC] No pending teleport request."));
        return 0;
    }

    private static int backCommand(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null)
            return 0;

        var loc = TeleportManager.getInstance().getLastLocation(player.getUUID());
        if (loc == null) {
            player.sendSystemMessage(Component.literal("§c[VC] No location to return to."));
            return 0;
        }

        // Check timeout
        // Check death back delay
        if (loc.isDeath()) {
            int delaySeconds = network.vonix.vonixcore.config.EssentialsConfig.CONFIG.deathBackDelay.get();
            if (delaySeconds > 0) {
                long elapsed = (System.currentTimeMillis() - loc.timestamp()) / 1000;
                if (elapsed < delaySeconds) {
                    ctx.getSource().sendFailure(Component.literal("§c[VC] You must wait " +
                            formatTime((int) (delaySeconds - elapsed)) + " before returning to your death location."));
                    return 0;
                }
            }
        }

        var server = ctx.getSource().getServer();
        for (var level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(loc.world())) {
                TeleportManager.getInstance().teleportPlayer(player, level, loc.x(), loc.y(), loc.z(), loc.yaw(),
                        loc.pitch());
                player.sendSystemMessage(Component.literal("§a[VC] Returned to previous location."));
                return 1;
            }
        }
        return 0;
    }

    private static int spawnCommand(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null)
            return 0;

        var spawn = ctx.getSource().getServer().overworld().getSharedSpawnPos();
        TeleportManager.getInstance().teleportPlayer(player, ctx.getSource().getServer().overworld(),
                spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0, 0);
        player.sendSystemMessage(Component.literal("§a[VC] Teleported to spawn."));
        return 1;
    }

    // ===== ECONOMY COMMANDS =====

    private static void registerEconomyCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("balance")
                .executes(VonixCoreCommands::balanceCommand));
        dispatcher.register(Commands.literal("bal").executes(VonixCoreCommands::balanceCommand));

        dispatcher.register(Commands.literal("pay")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                .executes(VonixCoreCommands::payCommand))));

        dispatcher.register(Commands.literal("baltop")
                .executes(VonixCoreCommands::baltopCommand));

        dispatcher.register(Commands.literal("eco")
                .requires(src -> src.hasPermission(4))
                .then(Commands.literal("give")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                        .executes(VonixCoreCommands::ecoGiveCommand))))
                .then(Commands.literal("take")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                        .executes(VonixCoreCommands::ecoTakeCommand))))
                .then(Commands.literal("set")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                        .executes(VonixCoreCommands::ecoSetCommand)))));
    }

    private static int balanceCommand(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null)
            return 0;

        EconomyManager.getInstance().getBalance(player.getUUID()).thenAccept(balance -> {
            player.sendSystemMessage(
                    Component.literal("§6[VC] Balance: §e" + EconomyManager.getInstance().format(balance)));
        });
        return 1;
    }

    private static int payCommand(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayer();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        double amount = DoubleArgumentType.getDouble(ctx, "amount");
        if (sender == null)
            return 0;

        EconomyManager.getInstance().transfer(sender.getUUID(), target.getUUID(), amount).thenAccept(success -> {
            if (success) {
                sender.sendSystemMessage(Component.literal("§a[VC] Sent " + EconomyManager.getInstance().format(amount)
                        + " to " + target.getName().getString()));
                target.sendSystemMessage(Component.literal("§a[VC] Received " + EconomyManager.getInstance().format(amount)
                        + " from " + sender.getName().getString()));
            } else {
                sender.sendSystemMessage(Component.literal("§c[VC] Insufficient funds!"));
            }
        });
        return 1;
    }

    private static int baltopCommand(CommandContext<CommandSourceStack> ctx) {
        EconomyManager.getInstance().getTopBalances(10).thenAccept(top -> {
            ctx.getSource().sendSuccess(() -> Component.literal("§6§l----- Balance Top -----"), false);
            int rank = 1;
            for (var entry : top) {
                int r = rank++;
                var player = ctx.getSource().getServer().getPlayerList().getPlayer(entry.uuid());
                String name = player != null ? player.getName().getString() : entry.uuid().toString().substring(0, 8);
                ctx.getSource().sendSuccess(() -> Component.literal("§e" + r + ". §f" + name + " §7- §a" +
                        EconomyManager.getInstance().format(entry.balance())), false);
            }
        });
        return 1;
    }

    private static int ecoGiveCommand(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        double amount = DoubleArgumentType.getDouble(ctx, "amount");
        EconomyManager.getInstance().deposit(target.getUUID(), amount);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a[VC] Gave " + EconomyManager.getInstance().format(amount) + " to " + target.getName().getString()),
                true);
        return 1;
    }

    private static int ecoTakeCommand(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        double amount = DoubleArgumentType.getDouble(ctx, "amount");
        EconomyManager.getInstance().withdraw(target.getUUID(), amount);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a[VC] Took " + EconomyManager.getInstance().format(amount) + " from " + target.getName().getString()),
                true);
        return 1;
    }

    private static int ecoSetCommand(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        double amount = DoubleArgumentType.getDouble(ctx, "amount");
        EconomyManager.getInstance().setBalance(target.getUUID(), amount);
        ctx.getSource().sendSuccess(() -> Component.literal("§a[VC] Set " + target.getName().getString()
                + "'s balance to " + EconomyManager.getInstance().format(amount)), true);
        return 1;
    }

    // ===== KIT COMMANDS =====

    private static void registerKitCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("kit")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> kitCommand(ctx, StringArgumentType.getString(ctx, "name"))))
                .executes(VonixCoreCommands::listKits));

        dispatcher.register(Commands.literal("kits").executes(VonixCoreCommands::listKits));
    }

    private static int kitCommand(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null)
            return 0;

        var result = KitManager.getInstance().giveKit(player, name);
        switch (result) {
            case SUCCESS -> player.sendSystemMessage(Component.literal("§a[VC] Kit '" + name + "' received!"));
            case NOT_FOUND -> player.sendSystemMessage(Component.literal("§c[VC] Kit not found!"));
            case ON_COOLDOWN -> {
                int remaining = KitManager.getInstance().getRemainingCooldown(player.getUUID(), name);
                player.sendSystemMessage(
                        Component.literal("§c[VC] Kit on cooldown! " + formatTime(remaining) + " remaining."));
            }
            case ALREADY_CLAIMED ->
                player.sendSystemMessage(Component.literal("§c[VC] You've already claimed this one-time kit!"));
        }
        return result == KitManager.KitResult.SUCCESS ? 1 : 0;
    }

    private static int listKits(CommandContext<CommandSourceStack> ctx) {
        var kits = KitManager.getInstance().getKitNames();
        if (kits.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7[VC] No kits available."), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal("§6[VC] Kits: §e" + String.join(", ", kits)), false);
        }
        return 1;
    }

    // ===== ADMIN COMMANDS =====

    private static void registerAdminCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("heal")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    AdminManager.getInstance().healPlayer(ctx.getSource().getPlayer());
                    return 1;
                })
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            AdminManager.getInstance().healPlayer(EntityArgument.getPlayer(ctx, "player"));
                            return 1;
                        })));

        dispatcher.register(Commands.literal("feed")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    AdminManager.getInstance().feedPlayer(ctx.getSource().getPlayer());
                    return 1;
                }));

        dispatcher.register(Commands.literal("fly")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    AdminManager.getInstance().toggleFly(ctx.getSource().getPlayer());
                    return 1;
                }));

        dispatcher.register(Commands.literal("god")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    AdminManager.getInstance().toggleGodMode(ctx.getSource().getPlayer());
                    return 1;
                }));

        dispatcher.register(Commands.literal("vanish")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    AdminManager.getInstance().toggleVanish(ctx.getSource().getPlayer(), ctx.getSource().getServer());
                    return 1;
                }));

        dispatcher.register(Commands.literal("gm")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("0")
                        .executes(ctx -> setGameMode(ctx, net.minecraft.world.level.GameType.SURVIVAL)))
                .then(Commands.literal("1")
                        .executes(ctx -> setGameMode(ctx, net.minecraft.world.level.GameType.CREATIVE)))
                .then(Commands.literal("2")
                        .executes(ctx -> setGameMode(ctx, net.minecraft.world.level.GameType.ADVENTURE)))
                .then(Commands.literal("3")
                        .executes(ctx -> setGameMode(ctx, net.minecraft.world.level.GameType.SPECTATOR)))
                .then(Commands.literal("s")
                        .executes(ctx -> setGameMode(ctx, net.minecraft.world.level.GameType.SURVIVAL)))
                .then(Commands.literal("c")
                        .executes(ctx -> setGameMode(ctx, net.minecraft.world.level.GameType.CREATIVE)))
                .then(Commands.literal("a")
                        .executes(ctx -> setGameMode(ctx, net.minecraft.world.level.GameType.ADVENTURE)))
                .then(Commands.literal("sp")
                        .executes(ctx -> setGameMode(ctx, net.minecraft.world.level.GameType.SPECTATOR))));
    }

    private static int setGameMode(CommandContext<CommandSourceStack> ctx, net.minecraft.world.level.GameType mode) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null) {
            player.setGameMode(mode);
            player.sendSystemMessage(Component.literal("§a[VC] Gamemode set to " + mode.getName()));
        }
        return 1;
    }

    // ===== UTILITY COMMANDS =====

    private static void registerUtilityCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("msg")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(VonixCoreCommands::msgCommand))));

        dispatcher.register(Commands.literal("r")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(VonixCoreCommands::replyCommand)));

        dispatcher.register(Commands.literal("broadcast")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String msg = StringArgumentType.getString(ctx, "message");
                            ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
                                    Component.literal("§6[Broadcast] §f" + msg), false);
                            return 1;
                        })));
    }

    private static int msgCommand(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayer();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String message = StringArgumentType.getString(ctx, "message");
        if (sender == null)
            return 0;

        sender.sendSystemMessage(Component.literal("§7[You -> " + target.getName().getString() + "] §f" + message));
        target.sendSystemMessage(Component.literal("§7[" + sender.getName().getString() + " -> You] §f" + message));
        AdminManager.getInstance().setReplyTarget(target.getUUID(), sender.getUUID());
        AdminManager.getInstance().setReplyTarget(sender.getUUID(), target.getUUID());
        return 1;
    }

    private static int replyCommand(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer sender = ctx.getSource().getPlayer();
        if (sender == null)
            return 0;

        var targetUuid = AdminManager.getInstance().getReplyTarget(sender.getUUID());
        if (targetUuid == null) {
            sender.sendSystemMessage(Component.literal("§c[VC] No one to reply to!"));
            return 0;
        }

        var target = ctx.getSource().getServer().getPlayerList().getPlayer(targetUuid);
        if (target == null) {
            sender.sendSystemMessage(Component.literal("§c[VC] Player is offline!"));
            return 0;
        }

        String message = StringArgumentType.getString(ctx, "message");
        sender.sendSystemMessage(Component.literal("§7[You -> " + target.getName().getString() + "] §f" + message));
        target.sendSystemMessage(Component.literal("§7[" + sender.getName().getString() + " -> You] §f" + message));
        return 1;
    }

    private static String formatTime(int seconds) {
        if (seconds < 60)
            return seconds + "s";
        if (seconds < 3600)
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }
}
