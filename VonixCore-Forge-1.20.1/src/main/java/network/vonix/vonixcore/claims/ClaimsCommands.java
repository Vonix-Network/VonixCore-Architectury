package network.vonix.vonixcore.claims;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.ClaimsConfig;

import java.util.List;

/**
 * Commands for the claims system.
 * Main command: /vonixcoreclaims (aliases: /vcclaims, /claims)
 */
public class ClaimsCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Main command with aliases
        var command = Commands.literal("vonixcoreclaims")
                // /vcclaims wand
                .then(Commands.literal("wand")
                        .executes(ClaimsCommands::giveWand))

                // /vcclaims create [radius]
                .then(Commands.literal("create")
                        .executes(ctx -> createClaim(ctx, -1))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> createClaim(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))

                // /vcclaims delete
                .then(Commands.literal("delete")
                        .executes(ClaimsCommands::deleteClaim))

                // /vcclaims trust <player>
                .then(Commands.literal("trust")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ClaimsCommands::trustPlayer)))

                // /vcclaims untrust <player>
                .then(Commands.literal("untrust")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ClaimsCommands::untrustPlayer)))

                // /vcclaims list
                .then(Commands.literal("list")
                        .executes(ClaimsCommands::listClaims))

                // /vcclaims info
                .then(Commands.literal("info")
                        .executes(ClaimsCommands::claimInfo))

                // /vcclaims admin ...
                .then(Commands.literal("admin")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                        .executes(ClaimsCommands::adminDeleteClaim)))
                        .then(Commands.literal("list")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ClaimsCommands::adminListClaims))))

                // Default - show help
                .executes(ClaimsCommands::showHelp);

        dispatcher.register(command);
        dispatcher.register(Commands.literal("vcclaims").redirect(dispatcher.getRoot().getChild("vonixcoreclaims")));
        dispatcher.register(Commands.literal("claims").redirect(dispatcher.getRoot().getChild("vonixcoreclaims")));

        VonixCore.LOGGER.info("[VonixCore] Claims commands registered");
    }

    private static int showHelp(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("§6=== VonixCore Claims ==="), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§e/vcclaims wand §7- Get claim selection wand"), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§e/vcclaims create [radius] §7- Create claim"), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§e/vcclaims delete §7- Delete claim you're in"), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§e/vcclaims trust <player> §7- Trust player"), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§e/vcclaims untrust <player> §7- Untrust player"), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§e/vcclaims list §7- List your claims"), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§e/vcclaims info §7- Info about current claim"), false);
        return 1;
    }

    private static int giveWand(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§cPlayers only"));
            return 0;
        }

        if (!hasCreatePermission(player)) {
            player.sendSystemMessage(Component.literal("§cYou don't have permission to create claims!"));
            return 0;
        }

        // Give golden shovel as wand
        ItemStack wand = new ItemStack(Items.GOLDEN_SHOVEL);
        wand.setHoverName(Component.literal("§6Claim Wand"));

        if (!player.getInventory().add(wand)) {
            player.drop(wand, false);
        }

        player.sendSystemMessage(Component.literal("§aReceived claim wand!"));
        player.sendSystemMessage(Component.literal("§7Left-click: Set corner 1"));
        player.sendSystemMessage(Component.literal("§7Right-click: Set corner 2"));
        player.sendSystemMessage(Component.literal("§7Then use §e/vcclaims create"));
        return 1;
    }

    private static int createClaim(CommandContext<CommandSourceStack> ctx, int radius) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§cPlayers only"));
            return 0;
        }

        if (!hasCreatePermission(player)) {
            player.sendSystemMessage(Component.literal("§cYou don't have permission to create claims!"));
            return 0;
        }

        ClaimsManager manager = ClaimsManager.getInstance();
        String world = player.level().dimension().location().toString();
        BlockPos pos1, pos2;

        if (radius > 0) {
            // Create claim around player with radius
            BlockPos center = player.blockPosition();
            pos1 = center.offset(-radius, -64, -radius);
            pos2 = center.offset(radius, 320, radius);
        } else if (manager.hasSelection(player.getUUID())) {
            // Use wand selection
            pos1 = manager.getCorner1(player.getUUID());
            pos2 = manager.getCorner2(player.getUUID());
            // Extend Y to full height
            pos1 = new BlockPos(pos1.getX(), -64, pos1.getZ());
            pos2 = new BlockPos(pos2.getX(), 320, pos2.getZ());
        } else {
            // Use default radius
            int defaultRadius = ClaimsConfig.CONFIG.defaultClaimRadius.get();
            BlockPos center = player.blockPosition();
            pos1 = center.offset(-defaultRadius, -64, -defaultRadius);
            pos2 = center.offset(defaultRadius, 320, defaultRadius);
        }

        Claim claim = manager.createClaim(player.getUUID(), player.getName().getString(), world, pos1, pos2);

        if (claim == null) {
            player.sendSystemMessage(Component.literal("§cFailed to create claim! Possible reasons:"));
            player.sendSystemMessage(Component.literal("§c- Claim overlaps existing claim"));
            player.sendSystemMessage(Component.literal("§c- Claim too large"));
            player.sendSystemMessage(Component.literal("§c- Claim limit reached"));
            return 0;
        }

        manager.clearSelection(player.getUUID());
        player.sendSystemMessage(Component.literal("§aClaim created! ID: " + claim.getId()));
        player.sendSystemMessage(Component.literal(String.format("§7Area: %d blocks (%d x %d)",
                claim.getArea(),
                claim.getX2() - claim.getX1() + 1,
                claim.getZ2() - claim.getZ1() + 1)));
        return 1;
    }

    private static int deleteClaim(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§cPlayers only"));
            return 0;
        }

        ClaimsManager manager = ClaimsManager.getInstance();
        String world = player.level().dimension().location().toString();
        Claim claim = manager.getClaimAt(world, player.blockPosition());

        if (claim == null) {
            player.sendSystemMessage(Component.literal("§cYou're not standing in a claim!"));
            return 0;
        }

        if (!claim.getOwner().equals(player.getUUID()) && !player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal("§cYou don't own this claim!"));
            return 0;
        }

        if (manager.deleteClaim(claim.getId())) {
            player.sendSystemMessage(Component.literal("§aClaim deleted!"));
            return 1;
        } else {
            player.sendSystemMessage(Component.literal("§cFailed to delete claim!"));
            return 0;
        }
    }

    private static int trustPlayer(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§cPlayers only"));
            return 0;
        }

        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        ClaimsManager manager = ClaimsManager.getInstance();
        String world = player.level().dimension().location().toString();
        Claim claim = manager.getClaimAt(world, player.blockPosition());

        if (claim == null) {
            player.sendSystemMessage(Component.literal("§cYou're not standing in a claim!"));
            return 0;
        }

        if (!claim.getOwner().equals(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§cYou don't own this claim!"));
            return 0;
        }

        if (manager.trustPlayer(claim.getId(), target.getUUID())) {
            player.sendSystemMessage(
                    Component.literal("§aTrusted §e" + target.getName().getString() + "§a in this claim!"));
            return 1;
        }
        return 0;
    }

    private static int untrustPlayer(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§cPlayers only"));
            return 0;
        }

        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        ClaimsManager manager = ClaimsManager.getInstance();
        String world = player.level().dimension().location().toString();
        Claim claim = manager.getClaimAt(world, player.blockPosition());

        if (claim == null) {
            player.sendSystemMessage(Component.literal("§cYou're not standing in a claim!"));
            return 0;
        }

        if (!claim.getOwner().equals(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§cYou don't own this claim!"));
            return 0;
        }

        if (manager.untrustPlayer(claim.getId(), target.getUUID())) {
            player.sendSystemMessage(Component.literal("§cRemoved trust for §e" + target.getName().getString()));
            return 1;
        }
        return 0;
    }

    private static int listClaims(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§cPlayers only"));
            return 0;
        }

        List<Claim> claims = ClaimsManager.getInstance().getPlayerClaims(player.getUUID());

        if (claims.isEmpty()) {
            player.sendSystemMessage(Component.literal("§7You don't have any claims."));
            return 1;
        }

        player.sendSystemMessage(Component.literal("§6=== Your Claims (" + claims.size() + ") ==="));
        for (Claim claim : claims) {
            player.sendSystemMessage(Component.literal(String.format(
                    "§e#%d §7- %s §8(%d,%d) to (%d,%d) §7[%d blocks]",
                    claim.getId(), claim.getWorld(),
                    claim.getX1(), claim.getZ1(),
                    claim.getX2(), claim.getZ2(),
                    claim.getArea())));
        }
        return 1;
    }

    private static int claimInfo(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§cPlayers only"));
            return 0;
        }

        ClaimsManager manager = ClaimsManager.getInstance();
        String world = player.level().dimension().location().toString();
        Claim claim = manager.getClaimAt(world, player.blockPosition());

        if (claim == null) {
            player.sendSystemMessage(Component.literal("§7You're not standing in a claim."));
            return 1;
        }

        player.sendSystemMessage(Component.literal("§6=== Claim Info ==="));
        player.sendSystemMessage(Component.literal("§7ID: §f" + claim.getId()));
        player.sendSystemMessage(Component.literal("§7Owner: §f" + claim.getOwnerName()));
        player.sendSystemMessage(Component.literal("§7World: §f" + claim.getWorld()));
        player.sendSystemMessage(Component.literal(String.format("§7Bounds: §f(%d,%d) to (%d,%d)",
                claim.getX1(), claim.getZ1(), claim.getX2(), claim.getZ2())));
        player.sendSystemMessage(Component.literal("§7Area: §f" + claim.getArea() + " blocks"));

        if (!claim.getTrusted().isEmpty()) {
            player.sendSystemMessage(Component.literal("§7Trusted: §f" + claim.getTrusted().size() + " players"));
        }

        if (claim.canInteract(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§aYou can build here."));
        } else {
            player.sendSystemMessage(Component.literal("§cYou cannot build here."));
        }

        return 1;
    }

    private static int adminDeleteClaim(CommandContext<CommandSourceStack> ctx) {
        int claimId = IntegerArgumentType.getInteger(ctx, "id");

        if (ClaimsManager.getInstance().deleteClaim(claimId)) {
            ctx.getSource().sendSuccess(() -> Component.literal("§aDeleted claim #" + claimId), true);
            return 1;
        } else {
            ctx.getSource().sendFailure(Component.literal("§cClaim not found!"));
            return 0;
        }
    }

    private static int adminListClaims(CommandContext<CommandSourceStack> ctx) {
        String playerName = StringArgumentType.getString(ctx, "player");

        // Find player UUID (simplified - would need proper lookup)
        ctx.getSource().sendSuccess(() -> Component.literal("§7Use /vcclaims list as that player or check database."),
                false);
        return 1;
    }

    private static boolean hasCreatePermission(ServerPlayer player) {
        if (!ClaimsConfig.CONFIG.requirePermissionToCreate.get()) {
            return true;
        }
        // Check permission or op status
        return player.hasPermissions(2); // TODO: Add proper permission check
    }
}
