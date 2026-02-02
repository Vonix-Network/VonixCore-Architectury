package network.vonix.vonixcore.permissions;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;

/**
 * Permission management commands.
 */
public class PermissionCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("perm")
            .requires(src -> src.hasPermission(3))
            .executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal("§6Permission Commands:"), false);
                ctx.getSource().sendSuccess(() -> Component.literal("§e/perm user <player> info §7- View player info"), false);
                ctx.getSource().sendSuccess(() -> Component.literal("§e/perm group list §7- List all groups"), false);
                ctx.getSource().sendSuccess(() -> Component.literal("§e/perm group <name> info §7- View group info"), false);
                return 1;
            })
            .then(Commands.literal("user")
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.literal("info")
                        .executes(ctx -> {
                            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                            String group = PermissionManager.getInstance().getPlayerGroup(player.getUUID());
                            String prefix = PermissionManager.getInstance().getPrefix(player.getUUID());
                            ctx.getSource().sendSuccess(() -> Component.literal("§6Player: §f" + player.getName().getString()), false);
                            ctx.getSource().sendSuccess(() -> Component.literal("§6Group: §f" + group), false);
                            ctx.getSource().sendSuccess(() -> Component.literal("§6Prefix: §f" + prefix), false);
                            return 1;
                        }))))
            .then(Commands.literal("group")
                .then(Commands.literal("list")
                    .executes(ctx -> {
                        ctx.getSource().sendSuccess(() -> Component.literal("§6Groups:"), false);
                        Collection<PermissionGroup> groups = PermissionManager.getInstance().getGroups();
                        for (PermissionGroup group : groups) {
                            ctx.getSource().sendSuccess(() -> Component.literal("§e- " + group.name() + " §7(priority: " + group.priority() + ")"), false);
                        }
                        return 1;
                    }))
                .then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.literal("info")
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            PermissionGroup group = PermissionManager.getInstance().getGroup(name);
                            if (group == null) {
                                ctx.getSource().sendFailure(Component.literal("§cGroup not found: " + name));
                                return 0;
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("§6Group: §f" + group.name()), false);
                            ctx.getSource().sendSuccess(() -> Component.literal("§6Priority: §f" + group.priority()), false);
                            ctx.getSource().sendSuccess(() -> Component.literal("§6Prefix: §f" + group.prefix()), false);
                            ctx.getSource().sendSuccess(() -> Component.literal("§6Permissions: §f" + group.permissions().size()), false);
                            return 1;
                        }))))
        );
    }
}
