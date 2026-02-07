package network.vonix.vonixcore.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.teleport.AsyncRtpManager;
import network.vonix.vonixcore.teleport.TeleportManager;
import network.vonix.vonixcore.config.EssentialsConfig;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility commands: tp, tphere, tppos, tpall, rtp, nick, seen, whois, ping,
 * near, msg, r, etc.
 */
public class UtilityCommands {

    private static final Random RANDOM = new Random();
    private static final Map<UUID, String> nicknames = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastSeen = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> firstJoin = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> lastMessaged = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<UUID>> ignoreList = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> rtpCooldowns = new ConcurrentHashMap<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Admin teleport commands
        registerTeleportCommands(dispatcher);

        // Player utility commands
        registerPlayerUtilityCommands(dispatcher);

        // Messaging commands
        registerMessagingCommands(dispatcher);

        // Item commands
        registerItemCommands(dispatcher);

        // Server management
        registerServerCommands(dispatcher);

        VonixCore.LOGGER.info("[VonixCore] Utility commands registered");
    }

    private static void registerTeleportCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /tp <player> - teleport to player
        dispatcher.register(Commands.literal("tp")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> teleportTo(ctx, EntityArgument.getPlayer(ctx, "target")))
                        .then(Commands.argument("destination", EntityArgument.player())
                                .executes(ctx -> teleportPlayerTo(ctx,
                                        EntityArgument.getPlayer(ctx, "target"),
                                        EntityArgument.getPlayer(ctx, "destination"))))));

        // /tphere <player> - teleport player to you
        dispatcher.register(Commands.literal("tphere")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> teleportHere(ctx, EntityArgument.getPlayer(ctx, "target")))));

        // /tpall - teleport all to you
        dispatcher.register(Commands.literal("tpall")
                .requires(s -> s.hasPermission(2))
                .executes(UtilityCommands::teleportAll));

        // /tppos <x> <y> <z> - teleport to coordinates
        dispatcher.register(Commands.literal("tppos")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                        .executes(UtilityCommands::teleportPos)))));

        // /rtp - random teleport
        dispatcher.register(Commands.literal("rtp")
                .executes(UtilityCommands::randomTeleport));

        // /setspawn - set world spawn
        dispatcher.register(Commands.literal("setspawn")
                .requires(s -> s.hasPermission(2))
                .executes(UtilityCommands::setSpawn));
    }

    private static void registerPlayerUtilityCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /nick <name> - set nickname
        dispatcher.register(Commands.literal("nick")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> setNickname(ctx, StringArgumentType.getString(ctx, "name"))))
                .executes(ctx -> clearNickname(ctx)));

        // /seen <player> - last seen
        dispatcher.register(Commands.literal("seen")
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> showSeen(ctx, StringArgumentType.getString(ctx, "player")))));

        // /whois <player> - player info
        dispatcher.register(Commands.literal("whois")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> showWhois(ctx, EntityArgument.getPlayer(ctx, "target")))));

        // /ping - show latency
        dispatcher.register(Commands.literal("ping")
                .executes(UtilityCommands::showPing));

        // /near [radius] - nearby players
        dispatcher.register(Commands.literal("near")
                .executes(ctx -> showNear(ctx, 100))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 500))
                        .executes(ctx -> showNear(ctx, IntegerArgumentType.getInteger(ctx, "radius")))));

        // /getpos - get coordinates
        dispatcher.register(Commands.literal("getpos")
                .executes(UtilityCommands::getPos));

        // /playtime - show playtime
        dispatcher.register(Commands.literal("playtime")
                .executes(UtilityCommands::showPlaytime));

        // /suicide - kill self
        dispatcher.register(Commands.literal("suicide")
                .executes(UtilityCommands::suicide));

        // /list - enhanced player list
        dispatcher.register(Commands.literal("list")
                .executes(UtilityCommands::showPlayerList));
    }

    private static void registerMessagingCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /msg <player> <message>
        dispatcher.register(Commands.literal("msg")
                .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> sendMessage(ctx,
                                        EntityArgument.getPlayer(ctx, "target"),
                                        StringArgumentType.getString(ctx, "message"))))));

        // /tell alias
        dispatcher.register(Commands.literal("tell")
                .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> sendMessage(ctx,
                                        EntityArgument.getPlayer(ctx, "target"),
                                        StringArgumentType.getString(ctx, "message"))))));

        // /r <message> - reply
        dispatcher.register(Commands.literal("r")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> replyMessage(ctx, StringArgumentType.getString(ctx, "message")))));

        // /reply alias
        dispatcher.register(Commands.literal("reply")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> replyMessage(ctx, StringArgumentType.getString(ctx, "message")))));

        // /ignore <player>
        dispatcher.register(Commands.literal("ignore")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> toggleIgnore(ctx, EntityArgument.getPlayer(ctx, "target")))));
    }

    private static void registerItemCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /hat - wear item as hat
        dispatcher.register(Commands.literal("hat")
                .executes(UtilityCommands::wearHat));

        // /more - fill stack
        dispatcher.register(Commands.literal("more")
                .requires(s -> s.hasPermission(2))
                .executes(UtilityCommands::moreItems));

        // /clear [player] - clear inventory
        dispatcher.register(Commands.literal("clear")
                .requires(s -> s.hasPermission(2))
                .executes(ctx -> clearInventory(ctx, null))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> clearInventory(ctx, EntityArgument.getPlayer(ctx, "target")))));

        // /repair - repair held item
        dispatcher.register(Commands.literal("repair")
                .requires(s -> s.hasPermission(2))
                .executes(UtilityCommands::repairItem));
    }

    private static void registerServerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /broadcast <message>
        dispatcher.register(Commands.literal("broadcast")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> broadcast(ctx, StringArgumentType.getString(ctx, "message")))));

        // /bc alias
        dispatcher.register(Commands.literal("bc")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> broadcast(ctx, StringArgumentType.getString(ctx, "message")))));

        // /gc - garbage collection / server stats
        dispatcher.register(Commands.literal("gc")
                .requires(s -> s.hasPermission(2))
                .executes(UtilityCommands::showServerStats));

        // /lag - show TPS
        dispatcher.register(Commands.literal("lag")
                .executes(UtilityCommands::showLag));

        // /invsee <player>
        dispatcher.register(Commands.literal("invsee")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> openInventory(ctx, EntityArgument.getPlayer(ctx, "target")))));

        // /enderchest [player]
        dispatcher.register(Commands.literal("enderchest")
                .requires(s -> s.hasPermission(2))
                .executes(ctx -> openEnderChest(ctx, null))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> openEnderChest(ctx, EntityArgument.getPlayer(ctx, "target")))));

        // /workbench
        dispatcher.register(Commands.literal("workbench")
                .executes(UtilityCommands::openWorkbench));

        // /anvil
        dispatcher.register(Commands.literal("anvil")
                .requires(s -> s.hasPermission(2))
                .executes(UtilityCommands::openAnvil));
    }

    // === TELEPORT IMPLEMENTATIONS ===

    private static int teleportTo(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§cPlayers only"));
            return 0;
        }
        // Save location for /back before teleporting
        TeleportManager.getInstance().saveLastLocation(player, false);
        player.teleportTo(target.getLevel(), target.getX(), target.getY(), target.getZ(),
                target.getYRot(), target.getXRot());
        player.sendSystemMessage(Component.literal("§aTeleported to §e" + target.getName().getString()));
        return 1;
    }

    private static int teleportPlayerTo(CommandContext<CommandSourceStack> ctx, ServerPlayer target,
            ServerPlayer dest) {
        // Save location for /back before teleporting
        TeleportManager.getInstance().saveLastLocation(target, false);
        target.teleportTo(dest.getLevel(), dest.getX(), dest.getY(), dest.getZ(),
                dest.getYRot(), dest.getXRot());
        ctx.getSource().sendSuccess(Component.literal("§aTeleported §e" + target.getName().getString() +
                "§a to §e" + dest.getName().getString()), true);
        target.sendSystemMessage(Component.literal("§aYou were teleported to §e" + dest.getName().getString()));
        return 1;
    }

    private static int teleportHere(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§cPlayers only"));
            return 0;
        }
        // Save location for /back before teleporting
        TeleportManager.getInstance().saveLastLocation(target, false);
        target.teleportTo(player.getLevel(), player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot());
        player.sendSystemMessage(Component.literal("§aTeleported §e" + target.getName().getString() + "§a to you"));
        target.sendSystemMessage(Component.literal("§aYou were teleported to §e" + player.getName().getString()));
        return 1;
    }

    private static int teleportAll(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§cPlayers only"));
            return 0;
        }
        int count = 0;
        for (ServerPlayer target : player.server.getPlayerList().getPlayers()) {
            if (target != player) {
                // Save location for /back before teleporting
                TeleportManager.getInstance().saveLastLocation(target, false);
                target.teleportTo(player.getLevel(), player.getX(), player.getY(), player.getZ(),
                        player.getYRot(), player.getXRot());
                target.sendSystemMessage(
                        Component.literal("§aYou were teleported to §e" + player.getName().getString()));
                count++;
            }
        }
        player.sendSystemMessage(Component.literal("§aTeleported §e" + count + "§a players to you"));
        return count;
    }

    private static int teleportPos(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§cPlayers only"));
            return 0;
        }
        double x = DoubleArgumentType.getDouble(ctx, "x");
        double y = DoubleArgumentType.getDouble(ctx, "y");
        double z = DoubleArgumentType.getDouble(ctx, "z");
        // Save location for /back before teleporting
        TeleportManager.getInstance().saveLastLocation(player, false);
        player.teleportTo(player.getLevel(), x, y, z, player.getYRot(), player.getXRot());
        player.sendSystemMessage(Component.literal(String.format("§aTeleported to §e%.1f, %.1f, %.1f", x, y, z)));
        return 1;
    }

    private static int randomTeleport(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§cPlayers only"));
            return 0;
        }

        // Check if RTP is enabled
        if (!EssentialsConfig.CONFIG.rtpEnabled.get()) {
            player.sendSystemMessage(Component.literal("§cRandom teleport is disabled on this server."));
            return 0;
        }

        // Check cooldown
        int cooldownSeconds = EssentialsConfig.CONFIG.rtpCooldown.get();
        if (cooldownSeconds > 0) {
            UUID uuid = player.getUUID();
            Long lastUse = rtpCooldowns.get(uuid);
            long now = System.currentTimeMillis();
            if (lastUse != null) {
                long elapsed = (now - lastUse) / 1000;
                long remaining = cooldownSeconds - elapsed;
                if (remaining > 0) {
                    player.sendSystemMessage(Component
                            .literal("§cYou must wait §e" + remaining + "§c seconds before using /rtp again."));
                    return 0;
                }
            }
            rtpCooldowns.put(uuid, now);
        }

        // Use async RTP manager to prevent server freezes
        AsyncRtpManager.randomTeleport(player);
        return 1;
    }

    private static int setSpawn(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§cPlayers only"));
            return 0;
        }
        BlockPos pos = player.blockPosition();
        player.getLevel().setDefaultSpawnPos(pos, 0);
        player.sendSystemMessage(
                Component.literal(String.format("§aSpawn set to §e%d, %d, %d", pos.getX(), pos.getY(), pos.getZ())));
        return 1;
    }

    // === PLAYER UTILITY IMPLEMENTATIONS ===

    private static int setNickname(CommandContext<CommandSourceStack> ctx, String name) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
            return 0;
        String colored = name.replace("&", "§");
        nicknames.put(player.getUUID(), colored);
        player.sendSystemMessage(Component.literal("§aNickname set to: " + colored));
        return 1;
    }

    private static int clearNickname(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
            return 0;
        nicknames.remove(player.getUUID());
        player.sendSystemMessage(Component.literal("§aNickname cleared"));
        return 1;
    }

    public static String getNickname(UUID uuid) {
        return nicknames.get(uuid);
    }

    private static int showSeen(CommandContext<CommandSourceStack> ctx, String playerName) {
        // Check online players first
        ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayerByName(playerName);
        if (online != null) {
            ctx.getSource().sendSuccess(Component.literal("§e" + playerName + " §7is currently §aonline"), false);
            return 1;
        }
        // Check last seen map
        ctx.getSource().sendSuccess(Component.literal("§e" + playerName + " §7is §coffline"), false);
        return 1;
    }

    private static int showWhois(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        String name = target.getName().getString();
        String display = nicknames.getOrDefault(target.getUUID(), name);
        int ping = getPlayerPing(target);
        BlockPos pos = target.blockPosition();
        String dim = target.getLevel().dimension().location().toString();

        ctx.getSource().sendSuccess(Component.literal("§6=== Player Info: §e" + name + " §6==="), false);
        ctx.getSource().sendSuccess(Component.literal("§7Display: " + display), false);
        ctx.getSource().sendSuccess(Component.literal("§7UUID: §f" + target.getUUID()), false);
        ctx.getSource().sendSuccess(Component.literal("§7Ping: §f" + ping + "ms"), false);
        ctx.getSource().sendSuccess(Component.literal(String.format("§7Location: §f%d, %d, %d §7in §f%s",
                pos.getX(), pos.getY(), pos.getZ(), dim)), false);
        ctx.getSource().sendSuccess(Component.literal("§7Health: §c" + (int) target.getHealth() + "§7/§c20"),
                false);
        ctx.getSource().sendSuccess(
                Component.literal("§7Food: §e" + target.getFoodData().getFoodLevel() + "§7/§e20"), false);
        return 1;
    }

    private static int showPing(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
            return 0;
        int ping = getPlayerPing(player);
        String color = ping < 50 ? "§a" : ping < 150 ? "§e" : "§c";
        player.sendSystemMessage(Component.literal("§7Your ping: " + color + ping + "ms"));
        return 1;
    }

    private static int showNear(CommandContext<CommandSourceStack> ctx, int radius) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
            return 0;
        List<String> nearby = new ArrayList<>();
        for (ServerPlayer other : player.server.getPlayerList().getPlayers()) {
            if (other != player && other.getLevel() == player.getLevel()) {
                double dist = player.distanceTo(other);
                if (dist <= radius) {
                    nearby.add(String.format("§e%s §7(%.0fm)", other.getName().getString(), dist));
                }
            }
        }
        if (nearby.isEmpty()) {
            player.sendSystemMessage(Component.literal("§7No players within " + radius + " blocks"));
        } else {
            player.sendSystemMessage(Component.literal("§6Nearby players: " + String.join(", ", nearby)));
        }
        return 1;
    }

    private static int getPos(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
            return 0;
        BlockPos pos = player.blockPosition();
        player.sendSystemMessage(Component.literal(String.format("§7Position: §eX: %d, Y: %d, Z: %d",
                pos.getX(), pos.getY(), pos.getZ())));
        return 1;
    }

    private static int showPlaytime(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
            return 0;
        int ticks = player.getStats().getValue(net.minecraft.stats.Stats.CUSTOM,
                net.minecraft.stats.Stats.PLAY_TIME);
        long seconds = ticks / 20;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        player.sendSystemMessage(Component.literal(String.format("§7Playtime: §e%dh %dm", hours, minutes)));
        return 1;
    }

    private static int suicide(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
            return 0;
        player.kill();
        return 1;
    }

    private static int showPlayerList(CommandContext<CommandSourceStack> ctx) {
        var players = ctx.getSource().getServer().getPlayerList().getPlayers();
        int max = ctx.getSource().getServer().getMaxPlayers();
        ctx.getSource().sendSuccess(Component.literal("§6Players Online: §e" + players.size() + "/" + max),
                false);
        StringBuilder sb = new StringBuilder();
        for (ServerPlayer p : players) {
            if (sb.length() > 0)
                sb.append("§7, ");
            String nick = nicknames.get(p.getUUID());
            sb.append(nick != null ? nick : "§e" + p.getName().getString());
        }
        ctx.getSource().sendSuccess(Component.literal(sb.toString()), false);
        return 1;
    }

    // === MESSAGING ===

    private static int sendMessage(CommandContext<CommandSourceStack> ctx, ServerPlayer target, String message) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sender))
            return 0;

        Set<UUID> ignored = ignoreList.getOrDefault(target.getUUID(), Set.of());
        if (ignored.contains(sender.getUUID())) {
            sender.sendSystemMessage(Component.literal("§cThis player is ignoring you"));
            return 0;
        }

        sender.sendSystemMessage(
                Component.literal("§7[§6me §7-> §e" + target.getName().getString() + "§7] §f" + message));
        target.sendSystemMessage(
                Component.literal("§7[§e" + sender.getName().getString() + " §7-> §6me§7] §f" + message));

        lastMessaged.put(sender.getUUID(), target.getUUID());
        lastMessaged.put(target.getUUID(), sender.getUUID());
        return 1;
    }

    private static int replyMessage(CommandContext<CommandSourceStack> ctx, String message) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sender))
            return 0;

        UUID lastUuid = lastMessaged.get(sender.getUUID());
        if (lastUuid == null) {
            sender.sendSystemMessage(Component.literal("§cNo one to reply to"));
            return 0;
        }

        ServerPlayer target = sender.server.getPlayerList().getPlayer(lastUuid);
        if (target == null) {
            sender.sendSystemMessage(Component.literal("§cPlayer is offline"));
            return 0;
        }

        return sendMessage(ctx, target, message);
    }

    private static int toggleIgnore(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
            return 0;

        Set<UUID> ignored = ignoreList.computeIfAbsent(player.getUUID(), k -> ConcurrentHashMap.newKeySet());
        if (ignored.contains(target.getUUID())) {
            ignored.remove(target.getUUID());
            player.sendSystemMessage(Component.literal("§aNo longer ignoring §e" + target.getName().getString()));
        } else {
            ignored.add(target.getUUID());
            player.sendSystemMessage(Component.literal("§cNow ignoring §e" + target.getName().getString()));
        }
        return 1;
    }

    // === ITEM COMMANDS ===

    private static int wearHat(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
            return 0;
        var hand = player.getMainHandItem();
        if (hand.isEmpty()) {
            player.sendSystemMessage(Component.literal("§cHold an item to wear as a hat"));
            return 0;
        }
        var helmet = player.getInventory().armor.get(3);
        player.getInventory().armor.set(3, hand.copy());
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, helmet);
        player.sendSystemMessage(Component.literal("§aYou are now wearing " + hand.getHoverName().getString()));
        return 1;
    }

    private static int moreItems(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
            return 0;
        var hand = player.getMainHandItem();
        if (hand.isEmpty())
            return 0;
        hand.setCount(hand.getMaxStackSize());
        player.sendSystemMessage(Component.literal("§aStack filled to " + hand.getCount()));
        return 1;
    }

    private static int clearInventory(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        if (target == null && ctx.getSource().getEntity() instanceof ServerPlayer p)
            target = p;
        if (target == null)
            return 0;
        target.getInventory().clearContent();
        target.sendSystemMessage(Component.literal("§aInventory cleared"));
        return 1;
    }

    private static int repairItem(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
            return 0;
        var hand = player.getMainHandItem();
        if (hand.isEmpty() || !hand.isDamageableItem()) {
            player.sendSystemMessage(Component.literal("§cHold a repairable item"));
            return 0;
        }
        hand.setDamageValue(0);
        player.sendSystemMessage(Component.literal("§aItem repaired"));
        return 1;
    }

    // === SERVER COMMANDS ===

    private static int broadcast(CommandContext<CommandSourceStack> ctx, String message) {
        String colored = message.replace("&", "§");
        for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal("§4[Broadcast] §f" + colored));
        }
        return 1;
    }

    private static int showServerStats(CommandContext<CommandSourceStack> ctx) {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        long max = rt.maxMemory() / 1024 / 1024;
        System.gc();
        ctx.getSource().sendSuccess(Component.literal("§6=== Server Stats ==="), false);
        ctx.getSource().sendSuccess(Component.literal("§7Memory: §e" + used + "MB§7/§e" + max + "MB"), false);
        ctx.getSource().sendSuccess(Component.literal("§7Threads: §e" + Thread.activeCount()), false);
        return 1;
    }

    private static int showLag(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(Component.literal("§7TPS: §acheck F3 debug screen"), false);
        return 1;
    }

    private static int openInventory(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
            return 0;
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (id, inv, p) -> net.minecraft.world.inventory.ChestMenu.threeRows(id, inv, target.getInventory()),
                Component.literal(target.getName().getString() + "'s Inventory")));
        return 1;
    }

    private static int openEnderChest(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
            return 0;
        final ServerPlayer enderTarget = target != null ? target : player;
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (id, inv, p) -> net.minecraft.world.inventory.ChestMenu.threeRows(id, inv,
                        enderTarget.getEnderChestInventory()),
                Component.literal("Ender Chest")));
        return 1;
    }

    private static int openWorkbench(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
            return 0;
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (id, inv, p) -> new net.minecraft.world.inventory.CraftingMenu(id, inv,
                        net.minecraft.world.inventory.ContainerLevelAccess.create(player.getLevel(),
                                player.blockPosition())),
                Component.literal("Crafting")));
        return 1;
    }

    private static int openAnvil(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
            return 0;
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (id, inv, p) -> new net.minecraft.world.inventory.AnvilMenu(id, inv,
                        net.minecraft.world.inventory.ContainerLevelAccess.create(player.getLevel(),
                                player.blockPosition())),
                Component.literal("Anvil")));
        return 1;
    }

    // Player tracking for /seen
    public static void onPlayerJoin(UUID uuid) {
        firstJoin.putIfAbsent(uuid, System.currentTimeMillis());
    }

    public static void onPlayerLeave(UUID uuid) {
        lastSeen.put(uuid, System.currentTimeMillis());
    }

    /**
     * Get player ping in milliseconds
     */
    private static int getPlayerPing(ServerPlayer player) {
        return player.latency;
    }
}
