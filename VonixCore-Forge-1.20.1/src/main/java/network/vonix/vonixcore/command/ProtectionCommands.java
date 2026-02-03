package network.vonix.vonixcore.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.ProtectionConfig;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CoreProtect-style protection commands for Forge 1.20.1.
 * Provides lookup, rollback, restore, inspect, and purge functionality.
 */
public class ProtectionCommands {

    // Players currently in inspector mode
    private static final Set<UUID> inspectorMode = ConcurrentHashMap.newKeySet();

    // History of rollbacks for undo
    private static final Map<UUID, Deque<RollbackData>> rollbackHistory = new ConcurrentHashMap<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /co command (CoreProtect compatibility)
        dispatcher.register(
                Commands.literal("co")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("help").executes(ProtectionCommands::helpCommand))
                        .then(Commands.literal("inspect").executes(ProtectionCommands::inspectCommand))
                        .then(Commands.literal("i").executes(ProtectionCommands::inspectCommand))
                        .then(Commands.literal("lookup")
                                .then(Commands.argument("params", StringArgumentType.greedyString())
                                        .executes(ProtectionCommands::lookupCommand))
                                .executes(ctx -> lookupNearby(ctx)))
                        .then(Commands.literal("l")
                                .then(Commands.argument("params", StringArgumentType.greedyString())
                                        .executes(ProtectionCommands::lookupCommand))
                                .executes(ctx -> lookupNearby(ctx)))
                        .then(Commands.literal("rollback")
                                .then(Commands.argument("params", StringArgumentType.greedyString())
                                        .executes(ProtectionCommands::rollbackCommand)))
                        .then(Commands.literal("rb")
                                .then(Commands.argument("params", StringArgumentType.greedyString())
                                        .executes(ProtectionCommands::rollbackCommand)))
                        .then(Commands.literal("restore")
                                .then(Commands.argument("params", StringArgumentType.greedyString())
                                        .executes(ProtectionCommands::restoreCommand)))
                        .then(Commands.literal("rs")
                                .then(Commands.argument("params", StringArgumentType.greedyString())
                                        .executes(ProtectionCommands::restoreCommand)))
                        .then(Commands.literal("undo").executes(ProtectionCommands::undoCommand))
                        .then(Commands.literal("purge")
                                .then(Commands.argument("time", StringArgumentType.string())
                                        .executes(ProtectionCommands::purgeCommand)))
                        .then(Commands.literal("status").executes(ProtectionCommands::statusCommand))
                        .then(Commands.literal("near")
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 100))
                                        .executes(
                                                ctx -> nearCommand(ctx, IntegerArgumentType.getInteger(ctx, "radius"))))
                                .executes(ctx -> nearCommand(ctx, 5)))
                        .executes(ProtectionCommands::helpCommand));

        // /vp command (VonixProtect alias)
        dispatcher.register(
                Commands.literal("vp")
                        .requires(source -> source.hasPermission(2))
                        .redirect(dispatcher.getRoot().getChild("co")));
    }

    private static void sendSuccess(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> message, false);
    }

    private static int helpCommand(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        sendSuccess(source, Component.literal("§6§l=== VonixCore Protection (CoreProtect) ==="));
        sendSuccess(source, Component.literal("§b/co inspect §7- Toggle inspector mode"));
        sendSuccess(source, Component.literal("§b/co lookup u:<user> t:<time> r:<radius> §7- Search logs"));
        sendSuccess(source, Component.literal("§b/co rollback u:<user> t:<time> r:<radius> §7- Rollback changes"));
        sendSuccess(source, Component.literal("§b/co restore u:<user> t:<time> r:<radius> §7- Restore changes"));
        sendSuccess(source, Component.literal("§b/co undo §7- Undo last rollback/restore"));
        sendSuccess(source, Component.literal("§b/co purge t:<time> §7- Delete old data"));
        sendSuccess(source, Component.literal("§b/co near [radius] §7- Lookup nearby changes"));
        sendSuccess(source, Component.literal("§b/co status §7- Show database status"));
        sendSuccess(source, Component.literal(""));
        sendSuccess(source, Component.literal("§7Parameters:"));
        sendSuccess(source, Component.literal("§7  u:<user> §f- Player name"));
        sendSuccess(source, Component.literal("§7  t:<time> §f- Time (e.g., 1h, 3d, 1w)"));
        sendSuccess(source, Component.literal("§7  r:<radius> §f- Radius (default: 10)"));
        sendSuccess(source, Component.literal("§7  a:<action> §f- Action filter"));
        sendSuccess(source, Component.literal("§7  b:<block> §f- Block type filter"));

        return 1;
    }

    private static int inspectCommand(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cThis command requires a player."));
            return 0;
        }

        UUID uuid = player.getUUID();
        if (inspectorMode.contains(uuid)) {
            inspectorMode.remove(uuid);
            sendSuccess(ctx.getSource(), Component.literal("§6[VonixCore] §fInspector mode §cdisabled§f."));
        } else {
            inspectorMode.add(uuid);
            sendSuccess(ctx.getSource(),
                    Component.literal("§6[VonixCore] §fInspector mode §aenabled§f. Click on blocks to see history."));
        }
        return 1;
    }

    public static boolean isInspecting(UUID uuid) {
        return inspectorMode.contains(uuid);
    }

    public static void inspectBlock(ServerPlayer player, BlockPos pos) {
        String world = player.level().dimension().location().toString();

        CompletableFuture.runAsync(() -> {
            try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
                String sql = "SELECT time, user, type, old_type, new_type, action FROM vp_block " +
                        "WHERE world = ? AND x = ? AND y = ? AND z = ? " +
                        "ORDER BY time DESC LIMIT 10";

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, world);
                    stmt.setInt(2, pos.getX());
                    stmt.setInt(3, pos.getY());
                    stmt.setInt(4, pos.getZ());

                    try (ResultSet rs = stmt.executeQuery()) {
                        List<Component> results = new ArrayList<>();
                        results.add(Component.literal("§6§l=== Block History (" + pos.getX() + ", " + pos.getY() + ", "
                                + pos.getZ() + ") ==="));

                        boolean hasResults = false;
                        while (rs.next()) {
                            hasResults = true;
                            long time = rs.getLong("time");
                            String user = rs.getString("user");
                            String oldType = rs.getString("old_type");
                            String newType = rs.getString("new_type");
                            int action = rs.getInt("action");

                            String actionStr = getActionString(action);
                            String timeStr = formatTimeAgo(time);
                            String blockInfo = action == 0 ? oldType : newType;

                            results.add(Component.literal(String.format("§7%s §f- §b%s §7%s §e%s",
                                    timeStr, user, actionStr, formatBlockName(blockInfo))));
                        }

                        if (!hasResults) {
                            results.add(Component.literal("§7No changes recorded at this location."));
                        }

                        // Send results on main thread
                        player.getServer().execute(() -> {
                            for (Component c : results) {
                                player.sendSystemMessage(c);
                            }
                        });
                    }
                }
            } catch (SQLException e) {
                VonixCore.LOGGER.error("[Protection] Error inspecting block: {}", e.getMessage());
                player.getServer()
                        .execute(() -> player.sendSystemMessage(Component.literal("§cError querying block history.")));
            }
        });
    }

    private static int lookupNearby(CommandContext<CommandSourceStack> ctx) {
        return lookupWithParams(ctx, "r:5 t:3d");
    }

    private static int lookupCommand(CommandContext<CommandSourceStack> ctx) {
        String params = StringArgumentType.getString(ctx, "params");
        return lookupWithParams(ctx, params);
    }

    private static int lookupWithParams(CommandContext<CommandSourceStack> ctx, String params) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cThis command requires a player."));
            return 0;
        }

        LookupParams parsed = parseParams(params);
        if (parsed.radius <= 0)
            parsed.radius = ProtectionConfig.CONFIG.defaultRadius.get();
        if (parsed.time <= 0)
            parsed.time = ProtectionConfig.CONFIG.defaultTime.get();

        BlockPos playerPos = player.blockPosition();
        String world = player.level().dimension().location().toString();
        int maxResults = ProtectionConfig.CONFIG.maxLookupResults.get();

        // Execute query async
        CompletableFuture.runAsync(() -> {
            try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
                StringBuilder sql = new StringBuilder(
                        "SELECT time, user, x, y, z, type, old_type, new_type, action FROM vp_block WHERE world = ? ");

                List<Object> queryParams = new ArrayList<>();
                queryParams.add(world);

                // Time filter
                long minTime = (System.currentTimeMillis() / 1000L) - parsed.time;
                sql.append("AND time >= ? ");
                queryParams.add(minTime);

                // Radius filter
                sql.append("AND x >= ? AND x <= ? AND y >= ? AND y <= ? AND z >= ? AND z <= ? ");
                queryParams.add(playerPos.getX() - parsed.radius);
                queryParams.add(playerPos.getX() + parsed.radius);
                queryParams.add(playerPos.getY() - parsed.radius);
                queryParams.add(playerPos.getY() + parsed.radius);
                queryParams.add(playerPos.getZ() - parsed.radius);
                queryParams.add(playerPos.getZ() + parsed.radius);

                // User filter
                if (parsed.user != null && !parsed.user.isEmpty()) {
                    sql.append("AND user = ? ");
                    queryParams.add(parsed.user);
                }

                // Block filter
                if (parsed.block != null && !parsed.block.isEmpty()) {
                    sql.append("AND (type LIKE ? OR old_type LIKE ? OR new_type LIKE ?) ");
                    String blockPattern = "%" + parsed.block + "%";
                    queryParams.add(blockPattern);
                    queryParams.add(blockPattern);
                    queryParams.add(blockPattern);
                }

                // Action filter
                if (parsed.action != null) {
                    sql.append("AND action = ? ");
                    queryParams.add(parsed.action);
                }

                sql.append("ORDER BY time DESC LIMIT ?");
                queryParams.add(maxResults);

                try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                    for (int i = 0; i < queryParams.size(); i++) {
                        Object param = queryParams.get(i);
                        if (param instanceof String) {
                            stmt.setString(i + 1, (String) param);
                        } else if (param instanceof Long) {
                            stmt.setLong(i + 1, (Long) param);
                        } else if (param instanceof Integer) {
                            stmt.setInt(i + 1, (Integer) param);
                        }
                    }

                    try (ResultSet rs = stmt.executeQuery()) {
                        List<Component> results = new ArrayList<>();
                        results.add(Component.literal("§6§l=== VonixCore Lookup Results ==="));

                        int count = 0;
                        while (rs.next()) {
                            count++;
                            long time = rs.getLong("time");
                            String user = rs.getString("user");
                            int x = rs.getInt("x");
                            int y = rs.getInt("y");
                            int z = rs.getInt("z");
                            String oldType = rs.getString("old_type");
                            String newType = rs.getString("new_type");
                            int action = rs.getInt("action");

                            String actionStr = getActionString(action);
                            String timeStr = formatTimeAgo(time);
                            String blockInfo = action == 0 ? oldType : newType;

                            MutableComponent line = Component.literal(String.format(
                                    "§7%s §f- §b%s §7%s §e%s §7at (",
                                    timeStr, user, actionStr, formatBlockName(blockInfo)));

                            // Clickable coordinates
                            MutableComponent coords = Component.literal(String.format("§a%d, %d, %d", x, y, z))
                                    .withStyle(style -> style
                                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                                    "/tp " + x + " " + y + " " + z))
                                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                    Component.literal("Click to teleport"))));

                            line.append(coords);
                            line.append(Component.literal("§7)"));

                            results.add(line);
                        }

                        if (count == 0) {
                            results.add(Component.literal("§7No results found."));
                        } else {
                            results.add(Component.literal(String.format("§7Found §f%d §7results.", count)));
                        }

                        // Send results on main thread
                        player.getServer().execute(() -> {
                            for (Component c : results) {
                                player.sendSystemMessage(c);
                            }
                        });
                    }
                }
            } catch (SQLException e) {
                VonixCore.LOGGER.error("[Protection] Lookup error: {}", e.getMessage());
                player.getServer()
                        .execute(() -> player.sendSystemMessage(Component.literal("§cError executing lookup query.")));
            }
        });

        return 1;
    }

    private static int rollbackCommand(CommandContext<CommandSourceStack> ctx) {
        String params = StringArgumentType.getString(ctx, "params");
        return executeRollbackRestore(ctx, params, true);
    }

    private static int restoreCommand(CommandContext<CommandSourceStack> ctx) {
        String params = StringArgumentType.getString(ctx, "params");
        return executeRollbackRestore(ctx, params, false);
    }

    private static int executeRollbackRestore(CommandContext<CommandSourceStack> ctx, String params,
            boolean isRollback) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cThis command requires a player."));
            return 0;
        }

        LookupParams parsed = parseParams(params);
        if (parsed.radius <= 0)
            parsed.radius = ProtectionConfig.CONFIG.defaultRadius.get();
        if (parsed.time <= 0)
            parsed.time = ProtectionConfig.CONFIG.defaultTime.get();

        // Require user or confirmation for large operations
        if (parsed.user == null && parsed.radius > 20) {
            ctx.getSource()
                    .sendFailure(Component.literal("§cPlease specify a user (u:<name>) for large radius rollbacks."));
            return 0;
        }

        BlockPos playerPos = player.blockPosition();
        String world = player.level().dimension().location().toString();
        String operation = isRollback ? "rollback" : "restore";

        sendSuccess(ctx.getSource(), Component.literal(String.format("§6[VonixCore] §fStarting %s... (r:%d t:%s)",
                operation, parsed.radius, formatDuration(parsed.time))));

        // Execute async
        LookupParams finalParsed = parsed;
        CompletableFuture.runAsync(() -> {
            try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
                long minTime = (System.currentTimeMillis() / 1000L) - finalParsed.time;

                StringBuilder sql = new StringBuilder(
                        "SELECT id, x, y, z, old_type, old_data, new_type, new_data, action FROM vp_block WHERE world = ? AND time >= ? ");

                List<Object> queryParams = new ArrayList<>();
                queryParams.add(world);
                queryParams.add(minTime);

                // Radius filter
                sql.append("AND x >= ? AND x <= ? AND y >= ? AND y <= ? AND z >= ? AND z <= ? ");
                queryParams.add(playerPos.getX() - finalParsed.radius);
                queryParams.add(playerPos.getX() + finalParsed.radius);
                queryParams.add(playerPos.getY() - finalParsed.radius);
                queryParams.add(playerPos.getY() + finalParsed.radius);
                queryParams.add(playerPos.getZ() - finalParsed.radius);
                queryParams.add(playerPos.getZ() + finalParsed.radius);

                // User filter
                if (finalParsed.user != null && !finalParsed.user.isEmpty()) {
                    sql.append("AND user = ? ");
                    queryParams.add(finalParsed.user);
                }

                // Block filter
                if (finalParsed.block != null && !finalParsed.block.isEmpty()) {
                    sql.append("AND (type LIKE ? OR old_type LIKE ? OR new_type LIKE ?) ");
                    String blockPattern = "%" + finalParsed.block + "%";
                    queryParams.add(blockPattern);
                    queryParams.add(blockPattern);
                    queryParams.add(blockPattern);
                }

                sql.append("ORDER BY time ");
                sql.append(isRollback ? "DESC" : "ASC");

                try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                    for (int i = 0; i < queryParams.size(); i++) {
                        Object param = queryParams.get(i);
                        if (param instanceof String) {
                            stmt.setString(i + 1, (String) param);
                        } else if (param instanceof Long) {
                            stmt.setLong(i + 1, (Long) param);
                        } else if (param instanceof Integer) {
                            stmt.setInt(i + 1, (Integer) param);
                        }
                    }

                    try (ResultSet rs = stmt.executeQuery()) {
                        List<BlockChange> changes = new ArrayList<>();

                        while (rs.next()) {
                            BlockChange change = new BlockChange();
                            change.id = rs.getLong("id");
                            change.x = rs.getInt("x");
                            change.y = rs.getInt("y");
                            change.z = rs.getInt("z");
                            change.oldType = rs.getString("old_type");
                            change.oldData = rs.getString("old_data");
                            change.newType = rs.getString("new_type");
                            change.newData = rs.getString("new_data");
                            change.action = rs.getInt("action");
                            changes.add(change);
                        }

                        if (changes.isEmpty()) {
                            player.getServer().execute(() -> player.sendSystemMessage(
                                    Component.literal("§6[VonixCore] §7No changes to " + operation + ".")));
                            return;
                        }

                        // Apply changes on main thread
                        player.getServer().execute(() -> {
                            int modified = 0;
                            for (BlockChange change : changes) {
                                BlockPos pos = new BlockPos(change.x, change.y, change.z);
                                String targetBlock = isRollback ? change.oldType : change.newType;

                                if (targetBlock != null && !targetBlock.isEmpty()) {
                                    BlockState state = getBlockState(targetBlock);
                                    if (state != null) {
                                        player.level().setBlock(pos, state, 3);
                                        modified++;
                                    }
                                }
                            }

                            player.sendSystemMessage(Component.literal(String.format(
                                    "§6[VonixCore] §f%s complete. §a%d §fblocks modified.",
                                    isRollback ? "Rollback" : "Restore", modified)));

                            // Store for undo
                            RollbackData undoData = new RollbackData();
                            undoData.changes = changes;
                            undoData.wasRollback = isRollback;
                            undoData.world = world;

                            rollbackHistory.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>()).push(undoData);
                        });
                    }
                }
            } catch (SQLException e) {
                VonixCore.LOGGER.error("[Protection] Rollback error: {}", e.getMessage());
                player.getServer().execute(
                        () -> player.sendSystemMessage(Component.literal("§cError executing " + operation + ".")));
            }
        });

        return 1;
    }

    private static int undoCommand(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cThis command requires a player."));
            return 0;
        }

        Deque<RollbackData> history = rollbackHistory.get(player.getUUID());
        if (history == null || history.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§cNo rollback/restore to undo."));
            return 0;
        }

        RollbackData lastOp = history.pop();

        int modified = 0;
        for (BlockChange change : lastOp.changes) {
            BlockPos pos = new BlockPos(change.x, change.y, change.z);
            // Undo = reverse of what we did
            String targetBlock = lastOp.wasRollback ? change.newType : change.oldType;

            if (targetBlock != null && !targetBlock.isEmpty()) {
                BlockState state = getBlockState(targetBlock);
                if (state != null) {
                    player.level().setBlock(pos, state, 3);
                    modified++;
                }
            }
        }

        sendSuccess(ctx.getSource(), Component.literal(String.format(
                "§6[VonixCore] §fUndo complete. §a%d §fblocks restored.", modified)));

        return 1;
    }

    private static int nearCommand(CommandContext<CommandSourceStack> ctx, int radius) {
        return lookupWithParams(ctx, "r:" + radius + " t:3d");
    }

    private static int purgeCommand(CommandContext<CommandSourceStack> ctx) {
        String timeStr = StringArgumentType.getString(ctx, "time");
        long seconds = parseTime(timeStr);

        if (seconds < 86400) { // Less than 1 day
            ctx.getSource().sendFailure(Component.literal("§cMinimum purge time is 1 day (1d)."));
            return 0;
        }

        long cutoff = (System.currentTimeMillis() / 1000L) - seconds;

        sendSuccess(ctx.getSource(), Component.literal(
                String.format("§6[VonixCore] §fPurging data older than %s...", formatDuration(seconds))));

        CompletableFuture.runAsync(() -> {
            try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
                int deleted = 0;

                // Purge block logs
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM vp_block WHERE time < ?")) {
                    stmt.setLong(1, cutoff);
                    deleted += stmt.executeUpdate();
                }

                // Purge container logs
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM vp_container WHERE time < ?")) {
                    stmt.setLong(1, cutoff);
                    deleted += stmt.executeUpdate();
                }

                int finalDeleted = deleted;
                ctx.getSource().getServer().execute(() -> sendSuccess(ctx.getSource(), Component.literal(
                        String.format("§6[VonixCore] §fPurge complete. §a%d §fentries removed.", finalDeleted))));

            } catch (SQLException e) {
                VonixCore.LOGGER.error("[Protection] Purge error: {}", e.getMessage());
                ctx.getSource().getServer()
                        .execute(() -> ctx.getSource().sendFailure(Component.literal("§cError executing purge.")));
            }
        });

        return 1;
    }

    private static int statusCommand(CommandContext<CommandSourceStack> ctx) {
        CompletableFuture.runAsync(() -> {
            try (Connection conn = VonixCore.getInstance().getDatabase().getConnection()) {
                long blockCount = 0;
                long containerCount = 0;

                try (Statement stmt = conn.createStatement()) {
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM vp_block")) {
                        if (rs.next())
                            blockCount = rs.getLong(1);
                    }
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM vp_container")) {
                        if (rs.next())
                            containerCount = rs.getLong(1);
                    }
                }

                long finalBlockCount = blockCount;
                long finalContainerCount = containerCount;
                ctx.getSource().getServer().execute(() -> {
                    sendSuccess(ctx.getSource(), Component.literal("§6§l=== VonixCore Protection Status ==="));
                    sendSuccess(ctx.getSource(),
                            Component.literal(String.format("§7Block logs: §f%,d", finalBlockCount)));
                    sendSuccess(ctx.getSource(),
                            Component.literal(String.format("§7Container logs: §f%,d", finalContainerCount)));
                    sendSuccess(ctx.getSource(), Component.literal(String.format("§7Queue size: §f%d",
                            network.vonix.vonixcore.consumer.Consumer.getInstance().getQueueSize())));
                });

            } catch (SQLException e) {
                VonixCore.LOGGER.error("[Protection] Status error: {}", e.getMessage());
                ctx.getSource().getServer()
                        .execute(() -> ctx.getSource().sendFailure(Component.literal("§cError getting status.")));
            }
        });

        return 1;
    }

    // Helper methods

    private static LookupParams parseParams(String params) {
        LookupParams result = new LookupParams();
        String[] parts = params.split("\\s+");

        for (String part : parts) {
            if (part.startsWith("u:") || part.startsWith("user:")) {
                result.user = part.substring(part.indexOf(':') + 1);
            } else if (part.startsWith("t:") || part.startsWith("time:")) {
                result.time = parseTime(part.substring(part.indexOf(':') + 1));
            } else if (part.startsWith("r:") || part.startsWith("radius:")) {
                try {
                    result.radius = Integer.parseInt(part.substring(part.indexOf(':') + 1));
                } catch (NumberFormatException ignored) {
                }
            } else if (part.startsWith("b:") || part.startsWith("block:")) {
                result.block = part.substring(part.indexOf(':') + 1);
            } else if (part.startsWith("a:") || part.startsWith("action:")) {
                String action = part.substring(part.indexOf(':') + 1).toLowerCase();
                if (action.equals("break") || action.equals("-block")) {
                    result.action = 0;
                } else if (action.equals("place") || action.equals("+block")) {
                    result.action = 1;
                } else if (action.equals("explode")) {
                    result.action = 2;
                }
            } else if (part.startsWith("e:") || part.startsWith("exclude:")) {
                result.exclude = part.substring(part.indexOf(':') + 1);
            }
        }

        return result;
    }

    private static long parseTime(String timeStr) {
        if (timeStr == null || timeStr.isEmpty())
            return 0;

        long multiplier = 1;
        String numPart = timeStr;

        if (timeStr.endsWith("s")) {
            multiplier = 1;
            numPart = timeStr.substring(0, timeStr.length() - 1);
        } else if (timeStr.endsWith("m")) {
            multiplier = 60;
            numPart = timeStr.substring(0, timeStr.length() - 1);
        } else if (timeStr.endsWith("h")) {
            multiplier = 3600;
            numPart = timeStr.substring(0, timeStr.length() - 1);
        } else if (timeStr.endsWith("d")) {
            multiplier = 86400;
            numPart = timeStr.substring(0, timeStr.length() - 1);
        } else if (timeStr.endsWith("w")) {
            multiplier = 604800;
            numPart = timeStr.substring(0, timeStr.length() - 1);
        }

        try {
            return Long.parseLong(numPart) * multiplier;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String formatTimeAgo(long unixTime) {
        long now = System.currentTimeMillis() / 1000L;
        long diff = now - unixTime;

        if (diff < 60)
            return diff + "s ago";
        if (diff < 3600)
            return (diff / 60) + "m ago";
        if (diff < 86400)
            return (diff / 3600) + "h ago";
        return (diff / 86400) + "d ago";
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60)
            return seconds + "s";
        if (seconds < 3600)
            return (seconds / 60) + "m";
        if (seconds < 86400)
            return (seconds / 3600) + "h";
        return (seconds / 86400) + "d";
    }

    private static String getActionString(int action) {
        return switch (action) {
            case 0 -> "broke";
            case 1 -> "placed";
            case 2 -> "exploded";
            default -> "modified";
        };
    }

    private static String formatBlockName(String blockId) {
        if (blockId == null)
            return "unknown";
        String name = blockId.replace("minecraft:", "").replace("_", " ");
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    private static BlockState getBlockState(String blockId) {
        if (blockId == null || blockId.isEmpty())
            return Blocks.AIR.defaultBlockState();

        try {
            net.minecraft.resources.ResourceLocation loc = new net.minecraft.resources.ResourceLocation(blockId);
            return net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(loc).defaultBlockState();
        } catch (Exception e) {
            return Blocks.AIR.defaultBlockState();
        }
    }

    // Data classes

    private static class LookupParams {
        String user;
        long time;
        int radius;
        String block;
        Integer action;
        String exclude;
    }

    private static class BlockChange {
        long id;
        int x, y, z;
        String oldType, oldData, newType, newData;
        int action;
    }

    private static class RollbackData {
        List<BlockChange> changes;
        boolean wasRollback;
        String world;
    }
}
