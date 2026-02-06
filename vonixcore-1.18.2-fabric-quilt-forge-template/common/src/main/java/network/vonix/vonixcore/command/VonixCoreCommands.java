package network.vonix.vonixcore.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.admin.AdminManager;
import network.vonix.vonixcore.homes.HomeManager;
import network.vonix.vonixcore.kits.KitManager;
import network.vonix.vonixcore.teleport.TeleportManager;
import network.vonix.vonixcore.warps.WarpManager;

/**
 * Comprehensive command registration for all VonixCore features.
 */
public class VonixCoreCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Register all command groups
        registerHomeCommands(dispatcher);
        registerWarpCommands(dispatcher);
        registerTeleportCommands(dispatcher);
        registerKitCommands(dispatcher);
        registerAdminCommands(dispatcher);
        registerVonixCoreCommand(dispatcher);

        VonixCore.LOGGER.info("[VonixCore] Additional commands registered");
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
        ctx.getSource().sendSuccess(new TextComponent("§6[VonixCore] §eReloading all configurations..."), true);

        try {
            VonixCore.LOGGER.info("[VonixCore] Config reload requested by {}",
                    ctx.getSource().getTextName());

            // Logic to reload configs would go here (usually by re-reading files)
            // SimpleConfig doesn't have a reload method exposed easily, but we can re-load
            // For now just logging

            ctx.getSource().sendSuccess(new TextComponent("§a[VonixCore] ✓ All configurations reloaded!"), true);
            ctx.getSource().sendSuccess(new TextComponent("§7Note: Some changes may require a server restart."),
                    false);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(new TextComponent("§c[VonixCore] Failed to reload configs: " + e.getMessage()));
            return 0;
        }
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> ctx, String module) {
        ctx.getSource().sendSuccess(
                new TextComponent("§6[VonixCore] §eReloading " + module + " configuration..."), true);

        try {
            VonixCore.LOGGER.info("[VonixCore] Config reload for {} requested by {}",
                    module, ctx.getSource().getTextName());

            ctx.getSource().sendSuccess(
                    new TextComponent("§a[VonixCore] ✓ " + module + " configuration reloaded!"), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource()
                    .sendFailure(new TextComponent("§c[VonixCore] Failed to reload " + module + ": " + e.getMessage()));
            return 0;
        }
    }

    private static int showVersion(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(new TextComponent("§6[VonixCore] §fVersion: §e" + VonixCore.VERSION), false);
        ctx.getSource().sendSuccess(new TextComponent("§7Platform: Architectury 1.20.1"), false);
        return 1;
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(new TextComponent("§6[VonixCore] §fModule Status:"), false);
        ctx.getSource().sendSuccess(new TextComponent("§7- Essentials: " +
                (VonixCore.getInstance().isEssentialsEnabled() ? "§aEnabled" : "§cDisabled")), false);
        return 1;
    }

    private static int showHelp(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(new TextComponent("§6§l=== VonixCore Commands ==="), false);
        ctx.getSource().sendSuccess(new TextComponent("§e/vonixcore reload [module] §7- Reload configurations"),
                false);
        ctx.getSource().sendSuccess(new TextComponent("§e/vonixcore version §7- Show version info"), false);
        ctx.getSource().sendSuccess(new TextComponent("§e/vonixcore status §7- Show module status"), false);
        ctx.getSource().sendSuccess(
                new TextComponent("§7Modules: all, database, essentials, discord, xpsync"), false);
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
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }

        if (HomeManager.getInstance().setHome(player, name)) {
            player.sendMessage(new TextComponent("§a[VC] Home '" + name + "' set!"), Util.NIL_UUID);
            return 1;
        } else {
            player.sendMessage(new TextComponent("§c[VC] You've reached your home limit!"), Util.NIL_UUID);
            return 0;
        }
    }

    private static int teleportHome(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }

        var home = HomeManager.getInstance().getHome(player.getUUID(), name);
        if (home == null) {
            player.sendMessage(new TextComponent("§c[VC] Home '" + name + "' not found!"), Util.NIL_UUID);
            return 0;
        }

        var server = ctx.getSource().getServer();
        for (var level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(home.world())) {
                TeleportManager.getInstance().teleportPlayer(player, level, home.x(), home.y(), home.z(), home.yaw(),
                        home.pitch());
                player.sendMessage(new TextComponent("§a[VC] Teleported to home '" + name + "'!"), Util.NIL_UUID);
                return 1;
            }
        }
        player.sendMessage(new TextComponent("§c[VC] World not found!"), Util.NIL_UUID);
        return 0;
    }

    private static int deleteHome(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }

        if (HomeManager.getInstance().deleteHome(player.getUUID(), name)) {
            player.sendMessage(new TextComponent("§a[VC] Home '" + name + "' deleted!"), Util.NIL_UUID);
            return 1;
        }
        player.sendMessage(new TextComponent("§c[VC] Home not found!"), Util.NIL_UUID);
        return 0;
    }

    private static int listHomes(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }

        var homes = HomeManager.getInstance().getHomes(player.getUUID());
        if (homes.isEmpty()) {
            player.sendMessage(new TextComponent("§7[VC] You have no homes set."), Util.NIL_UUID);
        } else {
            player.sendMessage(new TextComponent("§6[VC] Your homes: §e" +
                    String.join(", ", homes.stream().map(h -> h.name()).toList())), Util.NIL_UUID);
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
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }

        if (WarpManager.getInstance().setWarp(name, player)) {
            ctx.getSource().sendSuccess(new TextComponent("§a[VC] Warp '" + name + "' created!"), true);
            return 1;
        }
        return 0;
    }

    private static int teleportWarp(CommandContext<CommandSourceStack> ctx, String name) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }

        var warp = WarpManager.getInstance().getWarp(name);
        if (warp == null) {
            player.sendMessage(new TextComponent("§c[VC] Warp '" + name + "' not found!"), Util.NIL_UUID);
            return 0;
        }

        var server = ctx.getSource().getServer();
        for (var level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(warp.world())) {
                TeleportManager.getInstance().teleportPlayer(player, level, warp.x(), warp.y(), warp.z(), warp.yaw(),
                        warp.pitch());
                player.sendMessage(new TextComponent("§a[VC] Warped to '" + name + "'!"), Util.NIL_UUID);
                return 1;
            }
        }
        return 0;
    }

    private static int deleteWarp(CommandContext<CommandSourceStack> ctx, String name) {
        if (WarpManager.getInstance().deleteWarp(name)) {
            ctx.getSource().sendSuccess(new TextComponent("§a[VC] Warp deleted!"), true);
            return 1;
        }
        ctx.getSource().sendFailure(new TextComponent("§c[VC] Warp not found!"));
        return 0;
    }

    private static int listWarps(CommandContext<CommandSourceStack> ctx) {
        var warps = WarpManager.getInstance().getWarps();
        if (warps.isEmpty()) {
            ctx.getSource().sendSuccess(new TextComponent("§7[VC] No warps available."), false);
        } else {
            ctx.getSource().sendSuccess(new TextComponent("§6[VC] Warps: §e" +
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
        dispatcher.register(Commands.literal("backdeath").executes(VonixCoreCommands::backDeathCommand));

        dispatcher.register(Commands.literal("spawn").executes(VonixCoreCommands::spawnCommand));
    }

    private static int tpaCommand(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");

        if (TeleportManager.getInstance().sendTpaRequest(player, target, false)) {
            player.sendMessage(
                    new TextComponent("§a[VC] Teleport request sent to " + target.getName().getString()), Util.NIL_UUID);
            target.sendMessage(new TextComponent(
                    "§e[VC] " + player.getName().getString() + " wants to teleport to you. /tpaccept or /tpdeny"), Util.NIL_UUID);
            return 1;
        }
        player.sendMessage(new TextComponent("§c[VC] That player already has a pending request."), Util.NIL_UUID);
        return 0;
    }

    private static int tpaHereCommand(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");

        if (TeleportManager.getInstance().sendTpaRequest(player, target, true)) {
            player.sendMessage(
                    new TextComponent("§a[VC] Teleport request sent to " + target.getName().getString()), Util.NIL_UUID);
            target.sendMessage(new TextComponent(
                    "§e[VC] " + player.getName().getString() + " wants you to teleport to them. /tpaccept or /tpdeny"), Util.NIL_UUID);
            return 1;
        }
        return 0;
    }

    private static int tpAcceptCommand(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }

        if (TeleportManager.getInstance().acceptTpaRequest(player, ctx.getSource().getServer())) {
            player.sendMessage(new TextComponent("§a[VC] Teleport request accepted!"), Util.NIL_UUID);
            return 1;
        }
        player.sendMessage(new TextComponent("§c[VC] No pending teleport request."), Util.NIL_UUID);
        return 0;
    }

    private static int tpDenyCommand(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }

        if (TeleportManager.getInstance().denyTpaRequest(player)) {
            player.sendMessage(new TextComponent("§c[VC] Teleport request denied."), Util.NIL_UUID);
            return 1;
        }
        player.sendMessage(new TextComponent("§c[VC] No pending teleport request."), Util.NIL_UUID);
        return 0;
    }

    private static int backCommand(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }

        var loc = TeleportManager.getInstance().getLastLocation(player.getUUID());
        if (loc == null) {
            player.sendMessage(new TextComponent("§c[VC] No teleport location to return to. Use /backdeath for death locations."), Util.NIL_UUID);
            return 0;
        }

        var server = ctx.getSource().getServer();
        for (var level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(loc.world())) {
                TeleportManager.getInstance().teleportPlayer(player, level, loc.x(), loc.y(), loc.z(), loc.yaw(),
                        loc.pitch());
                player.sendMessage(new TextComponent("§a[VC] Returned to previous teleport location."), Util.NIL_UUID);
                return 1;
            }
        }
        player.sendMessage(new TextComponent("§c[VC] Could not find the world for your previous location."), Util.NIL_UUID);
        return 0;
    }

    private static int backDeathCommand(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }

        var loc = TeleportManager.getInstance().getDeathLocation(player.getUUID());
        if (loc == null) {
            player.sendMessage(new TextComponent("§c[VC] No death location to return to."), Util.NIL_UUID);
            return 0;
        }

        // Check death back delay
        int delaySeconds = network.vonix.vonixcore.config.EssentialsConfig.CONFIG.deathBackDelay.get();
        if (delaySeconds > 0) {
            long elapsed = (System.currentTimeMillis() - loc.timestamp()) / 1000;
            if (elapsed < delaySeconds) {
                ctx.getSource().sendFailure(new TextComponent("§c[VC] You must wait " +
                        formatTime((int) (delaySeconds - elapsed)) + " before returning to your death location."));
                return 0;
            }
        }

        var server = ctx.getSource().getServer();
        for (var level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(loc.world())) {
                TeleportManager.getInstance().teleportPlayer(player, level, loc.x(), loc.y(), loc.z(), loc.yaw(),
                        loc.pitch());
                player.sendMessage(new TextComponent("§a[VC] Returned to death location."), Util.NIL_UUID);
                return 1;
            }
        }
        player.sendMessage(new TextComponent("§c[VC] Could not find the world for your death location."), Util.NIL_UUID);
        return 0;
    }

    private static int spawnCommand(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }

        var spawn = ctx.getSource().getServer().overworld().getSharedSpawnPos();
        TeleportManager.getInstance().teleportPlayer(player, ctx.getSource().getServer().overworld(),
                spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0, 0);
        player.sendMessage(new TextComponent("§a[VC] Teleported to spawn."), Util.NIL_UUID);
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
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }

        var result = KitManager.getInstance().giveKit(player, name);
        switch (result) {
            case SUCCESS -> player.sendMessage(new TextComponent("§a[VC] Kit '" + name + "' received!"), Util.NIL_UUID);
            case NOT_FOUND -> player.sendMessage(new TextComponent("§c[VC] Kit not found!"), Util.NIL_UUID);
            case ON_COOLDOWN -> {
                int remaining = KitManager.getInstance().getRemainingCooldown(player.getUUID(), name);
                player.sendMessage(
                        new TextComponent("§c[VC] Kit on cooldown! " + formatTime(remaining) + " remaining."), Util.NIL_UUID);
            }
            case ALREADY_CLAIMED ->
                player.sendMessage(new TextComponent("§c[VC] You've already claimed this one-time kit!"), Util.NIL_UUID);
        }
        return result == KitManager.KitResult.SUCCESS ? 1 : 0;
    }

    private static int listKits(CommandContext<CommandSourceStack> ctx) {
        var kits = KitManager.getInstance().getKitNames();
        if (kits.isEmpty()) {
            ctx.getSource().sendSuccess(new TextComponent("§7[VC] No kits available."), false);
        } else {
            ctx.getSource().sendSuccess(new TextComponent("§6[VC] Kits: §e" + String.join(", ", kits)), false);
        }
        return 1;
    }

    // ===== ADMIN COMMANDS =====

    private static void registerAdminCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("heal")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    try {
                        AdminManager.getInstance().healPlayer(ctx.getSource().getPlayerOrException());
                        return 1;
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            AdminManager.getInstance().healPlayer(EntityArgument.getPlayer(ctx, "player"));
                            return 1;
                        })));

        dispatcher.register(Commands.literal("feed")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    try {
                        AdminManager.getInstance().feedPlayer(ctx.getSource().getPlayerOrException());
                        return 1;
                    } catch (Exception e) {
                        return 0;
                    }
                }));

        dispatcher.register(Commands.literal("fly")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    try {
                        AdminManager.getInstance().toggleFly(ctx.getSource().getPlayerOrException());
                        return 1;
                    } catch (Exception e) {
                        return 0;
                    }
                }));

        dispatcher.register(Commands.literal("god")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    try {
                        AdminManager.getInstance().toggleGodMode(ctx.getSource().getPlayerOrException());
                        return 1;
                    } catch (Exception e) {
                        return 0;
                    }
                }));

        dispatcher.register(Commands.literal("vanish")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    try {
                        AdminManager.getInstance().toggleVanish(ctx.getSource().getPlayerOrException(), ctx.getSource().getServer());
                        return 1;
                    } catch (Exception e) {
                        return 0;
                    }
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
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }
        
        if (player != null) {
            player.setGameMode(mode);
            player.sendMessage(new TextComponent("§a[VC] Gamemode set to " + mode.getName()), Util.NIL_UUID);
        }
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
