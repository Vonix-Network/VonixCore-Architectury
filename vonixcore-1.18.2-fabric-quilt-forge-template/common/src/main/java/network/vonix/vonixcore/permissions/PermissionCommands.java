package network.vonix.vonixcore.permissions;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.vonixcore.VonixCore;

import java.util.UUID;

/**
 * Permission management commands - LuckPerms-style.
 */
public class PermissionCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("perm")
                .requires(s -> s.hasPermission(3))
                .then(Commands.literal("user")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.literal("info")
                                        .executes(PermissionCommands::userInfo))
                                .then(Commands.literal("group")
                                        .then(Commands.literal("set")
                                                .then(Commands.argument(
                                                        "group",
                                                        StringArgumentType
                                                                .word())
                                                        .executes(PermissionCommands::userSetGroup)))
                                        .then(Commands.literal("add")
                                                .then(Commands.argument(
                                                        "group",
                                                        StringArgumentType
                                                                .word())
                                                        .executes(PermissionCommands::userAddGroup)))
                                        .then(Commands.literal("remove")
                                                .then(Commands.argument(
                                                        "group",
                                                        StringArgumentType
                                                                .word())
                                                        .executes(PermissionCommands::userRemoveGroup))))
                                .then(Commands.literal("permission")
                                        .then(Commands.literal("set")
                                                .then(Commands.argument(
                                                        "permission",
                                                        StringArgumentType
                                                                .string())
                                                        .then(Commands.argument(
                                                                "value",
                                                                BoolArgumentType.bool())
                                                                .executes(PermissionCommands::userSetPermission))))
                                        .then(Commands.literal("unset")
                                                .then(Commands.argument(
                                                        "permission",
                                                        StringArgumentType
                                                                .string())
                                                        .executes(PermissionCommands::userUnsetPermission)))
                                        .then(Commands.literal("check")
                                                .then(Commands.argument(
                                                        "permission",
                                                        StringArgumentType
                                                                .string())
                                                        .executes(PermissionCommands::userCheckPermission))))
                                .then(Commands.literal("meta")
                                        .then(Commands.literal("setprefix")
                                                .then(Commands.argument(
                                                        "prefix",
                                                        StringArgumentType
                                                                .greedyString())
                                                        .executes(PermissionCommands::userSetPrefix)))
                                        .then(Commands.literal("setsuffix")
                                                .then(Commands.argument(
                                                        "suffix",
                                                        StringArgumentType
                                                                .greedyString())
                                                        .executes(PermissionCommands::userSetSuffix)))
                                        .then(Commands.literal("clearprefix")
                                                .executes(PermissionCommands::userClearPrefix))
                                        .then(Commands.literal("clearsuffix")
                                                .executes(PermissionCommands::userClearSuffix)))))
                .then(Commands.literal("group")
                        .then(Commands.argument("group", StringArgumentType.word())
                                .then(Commands.literal("info").executes(
                                        PermissionCommands::groupInfo))
                                .then(Commands.literal("create").executes(
                                        PermissionCommands::groupCreate))
                                .then(Commands.literal("delete").executes(
                                        PermissionCommands::groupDelete))
                                .then(Commands.literal("permission")
                                        .then(Commands.literal("set")
                                                .then(Commands.argument(
                                                        "permission",
                                                        StringArgumentType
                                                                .string())
                                                        .then(Commands.argument(
                                                                "value",
                                                                BoolArgumentType.bool())
                                                                .executes(PermissionCommands::groupSetPermission))))
                                        .then(Commands.literal("unset")
                                                .then(Commands.argument(
                                                        "permission",
                                                        StringArgumentType
                                                                .string())
                                                        .executes(PermissionCommands::groupUnsetPermission))))
                                .then(Commands.literal("meta")
                                        .then(Commands.literal("setprefix")
                                                .then(Commands.argument(
                                                        "prefix",
                                                        StringArgumentType
                                                                .greedyString())
                                                        .executes(PermissionCommands::groupSetPrefix)))
                                        .then(Commands.literal("setsuffix")
                                                .then(Commands.argument(
                                                        "suffix",
                                                        StringArgumentType
                                                                .greedyString())
                                                        .executes(PermissionCommands::groupSetSuffix)))
                                        .then(Commands.literal("setweight")
                                                .then(Commands.argument(
                                                        "weight",
                                                        IntegerArgumentType
                                                                .integer())
                                                        .executes(PermissionCommands::groupSetWeight)))
                                        .then(Commands.literal("setdisplayname")
                                                .then(Commands.argument(
                                                        "name",
                                                        StringArgumentType
                                                                .greedyString())
                                                        .executes(PermissionCommands::groupSetDisplayName))))
                                .then(Commands.literal("parent")
                                        .then(Commands.literal("set")
                                                .then(Commands.argument(
                                                        "parent",
                                                        StringArgumentType
                                                                .word())
                                                        .executes(PermissionCommands::groupSetParent)))
                                        .then(Commands.literal("clear")
                                                .executes(PermissionCommands::groupClearParent)))))
                .then(Commands.literal("listgroups").executes(PermissionCommands::listGroups)));

        dispatcher.register(Commands.literal("lp")
                .requires(s -> s.hasPermission(3))
                .redirect(dispatcher.getRoot().getChild("perm")));

        VonixCore.LOGGER.info("[VonixCore] Permission commands registered");
    }

    private static int userInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        PermissionManager pm = PermissionManager.getInstance();
        UUID uuid = target.getUUID();

        ctx.getSource().sendSuccess(
                new TextComponent("§6=== User: §e" + target.getName().getString() + " §6==="),
                false);
        ctx.getSource().sendSuccess(new TextComponent("§7UUID: §f" + uuid), false);
        ctx.getSource().sendSuccess(new TextComponent("§7Primary Group: §e" + pm.getPrimaryGroup(uuid)),
                false);
        ctx.getSource().sendSuccess(
                new TextComponent("§7Prefix: §f" + pm.getPrefix(uuid).replace("§", "&")), false);
        ctx.getSource().sendSuccess(
                new TextComponent("§7Suffix: §f" + pm.getSuffix(uuid).replace("§", "&")), false);

        if (!pm.isUsingLuckPerms()) {
            PermissionUser user = pm.getUser(uuid);
            if (!user.getGroups().isEmpty()) {
                ctx.getSource().sendSuccess(new TextComponent(
                        "§7Additional Groups: §e" + String.join(", ", user.getGroups())),
                        false);
            }
        }
        return 1;
    }

    private static int userSetGroup(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String groupName = StringArgumentType.getString(ctx, "group");
        PermissionManager pm = PermissionManager.getInstance();

        if (pm.isUsingLuckPerms()) {
            ctx.getSource().sendFailure(new TextComponent("§cLuckPerms is active - use /lp command"));
            return 0;
        }

        if (pm.getGroup(groupName) == null) {
            ctx.getSource().sendFailure(new TextComponent("§cGroup '" + groupName + "' does not exist"));
            return 0;
        }

        PermissionUser user = pm.getUser(target.getUUID());
        user.setUsername(target.getName().getString());
        user.setPrimaryGroup(groupName);
        pm.saveUser(user);

        ctx.getSource().sendSuccess(new TextComponent(
                "§aSet §e" + target.getName().getString() + "§a's primary group to §e" + groupName),
                true);
        return 1;
    }

    private static int userAddGroup(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String groupName = StringArgumentType.getString(ctx, "group");
        PermissionManager pm = PermissionManager.getInstance();

        if (pm.isUsingLuckPerms()) {
            ctx.getSource().sendFailure(new TextComponent("§cLuckPerms is active"));
            return 0;
        }

        PermissionUser user = pm.getUser(target.getUUID());
        user.setUsername(target.getName().getString());
        user.addGroup(groupName);
        pm.saveUser(user);

        ctx.getSource().sendSuccess(new TextComponent("§aAdded §e" + target.getName().getString() + "§a to group §e" + groupName),
                true);
        return 1;
    }

    private static int userRemoveGroup(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String groupName = StringArgumentType.getString(ctx, "group");
        PermissionManager pm = PermissionManager.getInstance();

        PermissionUser user = pm.getUser(target.getUUID());
        user.removeGroup(groupName);
        pm.saveUser(user);

        ctx.getSource().sendSuccess(new TextComponent(
                "§aRemoved §e" + target.getName().getString() + "§a from group §e" + groupName), true);
        return 1;
    }

    private static int userSetPermission(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String permission = StringArgumentType.getString(ctx, "permission");
        boolean value = BoolArgumentType.getBool(ctx, "value");
        PermissionManager pm = PermissionManager.getInstance();

        PermissionUser user = pm.getUser(target.getUUID());
        user.setUsername(target.getName().getString());
        user.setPermission(permission, value);
        pm.saveUser(user);

        String valStr = value ? "§atrue" : "§cfalse";
        ctx.getSource().sendSuccess(new TextComponent("§aSet §e" + permission + "§a = " + valStr
                + "§a for §e" + target.getName().getString()), true);
        return 1;
    }

    private static int userUnsetPermission(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String permission = StringArgumentType.getString(ctx, "permission");
        PermissionManager pm = PermissionManager.getInstance();

        PermissionUser user = pm.getUser(target.getUUID());
        user.unsetPermission(permission);
        pm.saveUser(user);

        ctx.getSource().sendSuccess(
                new TextComponent(
                        "§aUnset §e" + permission + "§a for §e" + target.getName().getString()),
                true);
        return 1;
    }

    private static int userCheckPermission(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String permission = StringArgumentType.getString(ctx, "permission");
        PermissionManager pm = PermissionManager.getInstance();

        boolean has = pm.hasPermission(target, permission);
        String result = has ? "§a✓ TRUE" : "§c✗ FALSE";
        ctx.getSource().sendSuccess(new TextComponent(
                "§e" + target.getName().getString() + "§7 has §e" + permission + "§7: " + result),
                false);
        return 1;
    }

    private static int userSetPrefix(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String prefix = StringArgumentType.getString(ctx, "prefix").replace("&", "§");
        PermissionManager pm = PermissionManager.getInstance();
        if (pm.isUsingLuckPerms()) {
            ctx.getSource().sendFailure(new TextComponent("§cLuckPerms is active - use /lp user <user> meta setprefix ..."));
            return 0;
        }

        PermissionUser user = pm.getUser(target.getUUID());
        user.setUsername(target.getName().getString());
        user.setPrefix(prefix);
        pm.saveUser(user);

        ctx.getSource().sendSuccess(
                new TextComponent(
                        "§aSet prefix for §e" + target.getName().getString() + "§a: " + prefix),
                true);
        return 1;
    }

    private static int userSetSuffix(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String suffix = StringArgumentType.getString(ctx, "suffix").replace("&", "§");
        PermissionManager pm = PermissionManager.getInstance();
        if (pm.isUsingLuckPerms()) {
            ctx.getSource().sendFailure(new TextComponent("§cLuckPerms is active - use /lp user <user> meta setsuffix ..."));
            return 0;
        }

        PermissionUser user = pm.getUser(target.getUUID());
        user.setUsername(target.getName().getString());
        user.setSuffix(suffix);
        pm.saveUser(user);

        ctx.getSource().sendSuccess(
                new TextComponent(
                        "§aSet suffix for §e" + target.getName().getString() + "§a: " + suffix),
                true);
        return 1;
    }

    private static int userClearPrefix(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        PermissionManager pm = PermissionManager.getInstance();
        if (pm.isUsingLuckPerms()) {
            ctx.getSource().sendFailure(new TextComponent("§cLuckPerms is active - use /lp user <user> meta clear ..."));
            return 0;
        }
        PermissionUser user = pm.getUser(target.getUUID());
        user.setPrefix("");
        pm.saveUser(user);
        ctx.getSource().sendSuccess(
                new TextComponent("§aCleared prefix for §e" + target.getName().getString()),
                true);
        return 1;
    }

    private static int userClearSuffix(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        PermissionManager pm = PermissionManager.getInstance();
        if (pm.isUsingLuckPerms()) {
            ctx.getSource().sendFailure(new TextComponent("§cLuckPerms is active - use /lp user <user> meta clear ..."));
            return 0;
        }
        PermissionUser user = pm.getUser(target.getUUID());
        user.setSuffix("");
        pm.saveUser(user);
        ctx.getSource().sendSuccess(
                new TextComponent("§aCleared suffix for §e" + target.getName().getString()),
                true);
        return 1;
    }

    // === GROUP COMMANDS ===

    private static int groupInfo(CommandContext<CommandSourceStack> ctx) {
        String groupName = StringArgumentType.getString(ctx, "group");
        PermissionGroup group = PermissionManager.getInstance().getGroup(groupName);

        if (group == null) {
            ctx.getSource().sendFailure(new TextComponent("§cGroup '" + groupName + "' does not exist"));
            return 0;
        }

        ctx.getSource().sendSuccess(new TextComponent("§6=== Group: §e" + group.getName() + " §6==="),
                false);
        ctx.getSource().sendSuccess(new TextComponent("§7Display: §f" + group.getDisplayName()), false);
        ctx.getSource().sendSuccess(new TextComponent("§7Weight: §e" + group.getWeight()), false);
        ctx.getSource().sendSuccess(
                new TextComponent("§7Prefix: §f" + group.getPrefix().replace("§", "&")), false);
        ctx.getSource().sendSuccess(new TextComponent(
                "§7Parent: §e" + (group.getParent() != null ? group.getParent() : "none")),
                false);
        ctx.getSource().sendSuccess(
                new TextComponent("§7Permissions: §f" + group.getPermissions().size()), false);
        return 1;
    }

    private static int groupCreate(CommandContext<CommandSourceStack> ctx) {
        String groupName = StringArgumentType.getString(ctx, "group");
        PermissionManager pm = PermissionManager.getInstance();

        if (pm.isUsingLuckPerms()) {
            ctx.getSource().sendFailure(
                    new TextComponent("§cLuckPerms is active - use /lp creategroup ..."));
            return 0;
        }

        if (pm.getGroup(groupName) != null) {
            ctx.getSource().sendFailure(new TextComponent("§cGroup already exists"));
            return 0;
        }

        pm.createGroup(groupName);
        ctx.getSource().sendSuccess(new TextComponent("§aCreated group §e" + groupName), true);
        return 1;
    }

    private static int groupDelete(CommandContext<CommandSourceStack> ctx) {
        String groupName = StringArgumentType.getString(ctx, "group");
        PermissionManager pm = PermissionManager.getInstance();

        if (pm.isUsingLuckPerms()) {
            ctx.getSource().sendFailure(
                    new TextComponent("§cLuckPerms is active - use /lp deletegroup ..."));
            return 0;
        }

        if (groupName.equalsIgnoreCase("default")) {
            ctx.getSource().sendFailure(new TextComponent("§cCannot delete default group"));
            return 0;
        }
        pm.deleteGroup(groupName);
        ctx.getSource().sendSuccess(new TextComponent("§aDeleted group §e" + groupName), true);
        return 1;
    }

    private static int groupSetPermission(CommandContext<CommandSourceStack> ctx) {
        String groupName = StringArgumentType.getString(ctx, "group");
        String permission = StringArgumentType.getString(ctx, "permission");
        boolean value = BoolArgumentType.getBool(ctx, "value");
        PermissionManager pm = PermissionManager.getInstance();

        if (pm.isUsingLuckPerms()) {
            ctx.getSource().sendFailure(new TextComponent("§cLuckPerms is active - use /lp group ..."));
            return 0;
        }

        PermissionGroup group = pm.getGroup(groupName);
        if (group == null) {
            ctx.getSource().sendFailure(new TextComponent("§cGroup does not exist"));
            return 0;
        }

        group.setPermission(permission, value);
        saveGroupAsync(group);
        ctx.getSource().sendSuccess(new TextComponent("§aSet §e" + permission + "§a = "
                + (value ? "§atrue" : "§cfalse") + "§a for §e" + groupName), true);
        return 1;
    }

    private static int groupUnsetPermission(CommandContext<CommandSourceStack> ctx) {
        String groupName = StringArgumentType.getString(ctx, "group");
        String permission = StringArgumentType.getString(ctx, "permission");
        PermissionManager pm = PermissionManager.getInstance();

        if (pm.isUsingLuckPerms()) {
            ctx.getSource().sendFailure(new TextComponent("§cLuckPerms is active - use /lp group ..."));
            return 0;
        }

        PermissionGroup group = pm.getGroup(groupName);
        if (group == null) {
            ctx.getSource().sendFailure(new TextComponent("§cGroup does not exist"));
            return 0;
        }

        group.unsetPermission(permission);
        saveGroupAsync(group);
        ctx.getSource().sendSuccess(
                new TextComponent("§aUnset §e" + permission + "§a for §e" + groupName), true);
        return 1;
    }

    private static int groupSetPrefix(CommandContext<CommandSourceStack> ctx) {
        String groupName = StringArgumentType.getString(ctx, "group");
        String prefix = StringArgumentType.getString(ctx, "prefix").replace("&", "§");
        PermissionManager pm = PermissionManager.getInstance();

        if (pm.isUsingLuckPerms()) {
            ctx.getSource().sendFailure(new TextComponent("§cLuckPerms is active - use /lp group <group> meta setprefix ..."));
            return 0;
        }

        PermissionGroup group = pm.getGroup(groupName);
        if (group == null)
            return 0;
        group.setPrefix(prefix);
        saveGroupAsync(group);
        ctx.getSource().sendSuccess(
                new TextComponent("§aSet prefix for §e" + groupName + "§a: " + prefix), true);
        return 1;
    }

    private static int groupSetSuffix(CommandContext<CommandSourceStack> ctx) {
        String groupName = StringArgumentType.getString(ctx, "group");
        String suffix = StringArgumentType.getString(ctx, "suffix").replace("&", "§");
        PermissionManager pm = PermissionManager.getInstance();

        if (pm.isUsingLuckPerms()) {
            ctx.getSource().sendFailure(new TextComponent("§cLuckPerms is active - use /lp group <group> meta setsuffix ..."));
            return 0;
        }

        PermissionGroup group = pm.getGroup(groupName);
        if (group == null)
            return 0;
        group.setSuffix(suffix);
        saveGroupAsync(group);
        ctx.getSource().sendSuccess(
                new TextComponent("§aSet suffix for §e" + groupName + "§a: " + suffix), true);
        return 1;
    }

    private static int groupSetWeight(CommandContext<CommandSourceStack> ctx) {
        String groupName = StringArgumentType.getString(ctx, "group");
        int weight = IntegerArgumentType.getInteger(ctx, "weight");
        PermissionManager pm = PermissionManager.getInstance();

        if (pm.isUsingLuckPerms()) {
            ctx.getSource().sendFailure(new TextComponent("§cLuckPerms is active - use /lp group ..."));
            return 0;
        }

        PermissionGroup group = pm.getGroup(groupName);
        if (group == null)
            return 0;
        group.setWeight(weight);
        saveGroupAsync(group);
        ctx.getSource().sendSuccess(
                new TextComponent("§aSet weight for §e" + groupName + "§a: §e" + weight), true);
        return 1;
    }

    private static int groupSetDisplayName(CommandContext<CommandSourceStack> ctx) {
        String groupName = StringArgumentType.getString(ctx, "group");
        String name = StringArgumentType.getString(ctx, "name").replace("&", "§");
        PermissionManager pm = PermissionManager.getInstance();

        if (pm.isUsingLuckPerms()) {
            ctx.getSource().sendFailure(new TextComponent("§cLuckPerms is active - use /lp group ..."));
            return 0;
        }

        PermissionGroup group = pm.getGroup(groupName);
        if (group == null)
            return 0;
        group.setDisplayName(name);
        saveGroupAsync(group);
        ctx.getSource().sendSuccess(
                new TextComponent("§aSet display name for §e" + groupName + "§a: " + name), true);
        return 1;
    }

    private static int groupSetParent(CommandContext<CommandSourceStack> ctx) {
        String groupName = StringArgumentType.getString(ctx, "group");
        String parentName = StringArgumentType.getString(ctx, "parent");
        PermissionManager pm = PermissionManager.getInstance();
        PermissionGroup group = pm.getGroup(groupName);
        if (group == null || pm.getGroup(parentName) == null) {
            ctx.getSource().sendFailure(new TextComponent("§cGroup not found"));
            return 0;
        }
        group.setParent(parentName);
        saveGroupAsync(group);
        ctx.getSource().sendSuccess(
                new TextComponent("§aSet parent §e" + parentName + "§a for §e" + groupName),
                true);
        return 1;
    }

    private static int groupClearParent(CommandContext<CommandSourceStack> ctx) {
        String groupName = StringArgumentType.getString(ctx, "group");
        PermissionGroup group = PermissionManager.getInstance().getGroup(groupName);
        if (group == null)
            return 0;
        group.setParent(null);
        saveGroupAsync(group);
        ctx.getSource().sendSuccess(new TextComponent("§aCleared parent for §e" + groupName), true);
        return 1;
    }

    private static int listGroups(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(new TextComponent("§6=== Permission Groups ==="), false);
        for (PermissionGroup group : PermissionManager.getInstance().getGroups()) {
            String info = String.format("§e%s §7(weight: %d)", group.getName(), group.getWeight());
            ctx.getSource().sendSuccess(new TextComponent(info), false);
        }
        return 1;
    }

    private static void saveGroupAsync(PermissionGroup group) {
        new Thread(() -> {
            try (var conn = VonixCore.getInstance().getDatabase().getConnection()) {
                PermissionManager.getInstance().saveGroup(conn, group);
            } catch (Exception e) {
                VonixCore.LOGGER.error("[Permissions] Error saving group", e);
            }
        }).start();
    }
}
