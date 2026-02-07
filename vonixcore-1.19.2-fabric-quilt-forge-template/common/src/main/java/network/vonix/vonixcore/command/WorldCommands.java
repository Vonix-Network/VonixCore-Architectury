package network.vonix.vonixcore.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.vonixcore.VonixCore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * World and environment commands: weather, time, lightning, afk, etc.
 */
public class WorldCommands {

    private static final Map<UUID, Long> afkTime = new ConcurrentHashMap<>();
    private static final Map<UUID, String> afkMessage = new ConcurrentHashMap<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Weather commands
        dispatcher.register(Commands.literal("weather")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("clear")
                        .executes(ctx -> setWeather(ctx, "clear", 6000)))
                .then(Commands.literal("rain")
                        .executes(ctx -> setWeather(ctx, "rain", 6000)))
                .then(Commands.literal("storm")
                        .executes(ctx -> setWeather(ctx, "storm", 6000)))
                .then(Commands.literal("thunder")
                        .executes(ctx -> setWeather(ctx, "storm", 6000))));

        // Shortcut aliases
        dispatcher.register(Commands.literal("sun")
                .requires(s -> s.hasPermission(2))
                .executes(ctx -> setWeather(ctx, "clear", 24000)));

        dispatcher.register(Commands.literal("rain")
                .requires(s -> s.hasPermission(2))
                .executes(ctx -> setWeather(ctx, "rain", 6000)));

        dispatcher.register(Commands.literal("storm")
                .requires(s -> s.hasPermission(2))
                .executes(ctx -> setWeather(ctx, "storm", 6000)));

        // Time commands
        dispatcher.register(Commands.literal("time")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("set")
                        .then(Commands.literal("day").executes(ctx -> setTime(ctx, 1000)))
                        .then(Commands.literal("night").executes(ctx -> setTime(ctx, 13000)))
                        .then(Commands.literal("noon").executes(ctx -> setTime(ctx, 6000)))
                        .then(Commands.literal("midnight").executes(ctx -> setTime(ctx, 18000)))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(0))
                                .executes(ctx -> setTime(ctx, IntegerArgumentType.getInteger(ctx, "ticks")))))
                .then(Commands.literal("add")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer())
                                .executes(ctx -> addTime(ctx, IntegerArgumentType.getInteger(ctx, "ticks"))))));

        // Shortcut aliases
        dispatcher.register(Commands.literal("day")
                .requires(s -> s.hasPermission(2))
                .executes(ctx -> setTime(ctx, 1000)));

        dispatcher.register(Commands.literal("night")
                .requires(s -> s.hasPermission(2))
                .executes(ctx -> setTime(ctx, 13000)));

        // Lightning
        dispatcher.register(Commands.literal("lightning")
                .requires(s -> s.hasPermission(2))
                .executes(WorldCommands::lightningAtPlayer)
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> lightningAtTarget(ctx, EntityArgument.getPlayer(ctx, "target")))));

        dispatcher.register(Commands.literal("smite")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> lightningAtTarget(ctx, EntityArgument.getPlayer(ctx, "target")))));

        // Extinguish
        dispatcher.register(Commands.literal("ext")
                .executes(WorldCommands::extinguishSelf)
                .then(Commands.argument("target", EntityArgument.player())
                        .requires(s -> s.hasPermission(2))
                        .executes(ctx -> extinguishPlayer(ctx, EntityArgument.getPlayer(ctx, "target")))));

        // AFK
        dispatcher.register(Commands.literal("afk")
                .executes(ctx -> toggleAfk(ctx, null))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> toggleAfk(ctx, StringArgumentType.getString(ctx, "message")))));

        VonixCore.LOGGER.info("[VonixCore] World commands registered");
    }

    private static int setWeather(CommandContext<CommandSourceStack> ctx, String type, int duration) {
        ServerLevel level = ctx.getSource().getLevel();
        switch (type) {
            case "clear" -> {
                level.setWeatherParameters(duration, 0, false, false);
                ctx.getSource().sendSuccess(Component.literal("§aWeather set to clear"), true);
            }
            case "rain" -> {
                level.setWeatherParameters(0, duration, true, false);
                ctx.getSource().sendSuccess(Component.literal("§aWeather set to rain"), true);
            }
            case "storm" -> {
                level.setWeatherParameters(0, duration, true, true);
                ctx.getSource().sendSuccess(Component.literal("§aWeather set to storm"), true);
            }
        }
        return 1;
    }

    private static int setTime(CommandContext<CommandSourceStack> ctx, int ticks) {
        for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
            level.setDayTime(ticks);
        }
        ctx.getSource().sendSuccess(Component.literal("§aTime set to §e" + ticks), true);
        return 1;
    }

    private static int addTime(CommandContext<CommandSourceStack> ctx, int ticks) {
        for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
            level.setDayTime(level.getDayTime() + ticks);
        }
        ctx.getSource().sendSuccess(Component.literal("§aAdded §e" + ticks + "§a ticks to time"), true);
        return 1;
    }

    private static int lightningAtPlayer(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§cPlayers only"));
            return 0;
        }
        var lightning = net.minecraft.world.entity.EntityType.LIGHTNING_BOLT.create(player.getLevel());
        if (lightning != null) {
            lightning.moveTo(player.getX(), player.getY(), player.getZ());
            player.getLevel().addFreshEntity(lightning);
        }
        player.sendSystemMessage(Component.literal("§eLightning struck at your location!"));
        return 1;
    }

    private static int lightningAtTarget(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        var lightning = net.minecraft.world.entity.EntityType.LIGHTNING_BOLT.create(target.getLevel());
        if (lightning != null) {
            lightning.moveTo(target.getX(), target.getY(), target.getZ());
            target.getLevel().addFreshEntity(lightning);
        }
        ctx.getSource().sendSuccess(
                Component.literal("§eStruck §6" + target.getName().getString() + "§e with lightning!"), true);
        target.sendSystemMessage(Component.literal("§cYou were struck by lightning!"));
        return 1;
    }

    private static int extinguishSelf(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
            return 0;
        player.clearFire();
        player.sendSystemMessage(Component.literal("§aYou have been extinguished"));
        return 1;
    }

    private static int extinguishPlayer(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        target.clearFire();
        ctx.getSource().sendSuccess(Component.literal("§aExtinguished §e" + target.getName().getString()), true);
        target.sendSystemMessage(Component.literal("§aYou have been extinguished"));
        return 1;
    }

    private static int toggleAfk(CommandContext<CommandSourceStack> ctx, String message) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player))
            return 0;
        UUID uuid = player.getUUID();

        if (afkTime.containsKey(uuid)) {
            // Return from AFK
            afkTime.remove(uuid);
            afkMessage.remove(uuid);
            broadcastToAll(player.server, "§7" + player.getName().getString() + " is no longer AFK");
            player.sendSystemMessage(Component.literal("§aYou are no longer AFK"));
        } else {
            // Go AFK
            afkTime.put(uuid, System.currentTimeMillis());
            if (message != null)
                afkMessage.put(uuid, message);
            String broadcast = message != null
                    ? "§7" + player.getName().getString() + " is now AFK: " + message
                    : "§7" + player.getName().getString() + " is now AFK";
            broadcastToAll(player.server, broadcast);
            player.sendSystemMessage(Component.literal("§eYou are now AFK" + (message != null ? ": " + message : "")));
        }
        return 1;
    }

    public static boolean isAfk(UUID uuid) {
        return afkTime.containsKey(uuid);
    }

    public static String getAfkMessage(UUID uuid) {
        return afkMessage.get(uuid);
    }

    public static void clearAfk(UUID uuid) {
        afkTime.remove(uuid);
        afkMessage.remove(uuid);
    }

    private static void broadcastToAll(net.minecraft.server.MinecraftServer server, String message) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal(message));
        }
    }
}
