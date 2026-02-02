package network.vonix.vonixcore.claims;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import network.vonix.vonixcore.VonixCore;
import network.vonix.vonixcore.config.ClaimsConfig;
import network.vonix.vonixcore.config.EssentialsConfig;
import network.vonix.vonixcore.economy.ShopManager;

/**
 * Event handlers for claim protection.
 * Prevents unauthorized players from interacting with protected areas.
 */
@Mod.EventBusSubscriber(modid = VonixCore.MODID)
public class ClaimsListener {

    /**
     * Handle block breaking
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!ClaimsConfig.CONFIG.enabled.get() || !ClaimsConfig.CONFIG.protectBuilding.get())
            return;
        if (event.getLevel().isClientSide())
            return;

        if (!(event.getPlayer() instanceof ServerPlayer player))
            return;

        // Admins bypass
        if (player.hasPermissions(2))
            return;

        String world = player.level().dimension().location().toString();
        BlockPos pos = event.getPos();

        if (!ClaimsManager.getInstance().canBuild(player.getUUID(), world, pos)) {
            event.setCanceled(true);
            player.sendSystemMessage(Component.literal("§cYou can't break blocks in this claim!"));
        }
    }

    /**
     * Handle block placing
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!ClaimsConfig.CONFIG.enabled.get() || !ClaimsConfig.CONFIG.protectBuilding.get())
            return;
        if (event.getLevel().isClientSide())
            return;

        if (!(event.getEntity() instanceof ServerPlayer player))
            return;

        // Admins bypass
        if (player.hasPermissions(2))
            return;

        String world = player.level().dimension().location().toString();
        BlockPos pos = event.getPos();

        if (!ClaimsManager.getInstance().canBuild(player.getUUID(), world, pos)) {
            event.setCanceled(true);
            player.sendSystemMessage(Component.literal("§cYou can't place blocks in this claim!"));
        }
    }

    /**
     * Handle right-click interactions (containers, etc.)
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!ClaimsConfig.CONFIG.enabled.get())
            return;
        if (event.getLevel().isClientSide())
            return;

        if (!(event.getEntity() instanceof ServerPlayer player))
            return;

        // Admins bypass
        if (player.hasPermissions(2))
            return;

        String world = player.level().dimension().location().toString();
        BlockPos pos = event.getPos();
        var state = event.getLevel().getBlockState(pos);

        // Check if in claim
        Claim claim = ClaimsManager.getInstance().getClaimAt(world, pos);
        if (claim == null)
            return; // Not in claim

        // Check if player can interact
        if (claim.canInteract(player.getUUID()))
            return; // Owner or trusted

        // Check for VonixCore shop bypass
        if (ClaimsConfig.CONFIG.allowVonixShopsBypass.get()) {
            // Check chest shops
            if (state.getBlock() instanceof ChestBlock || state.is(Blocks.BARREL)) {
                if (EssentialsConfig.CONFIG.shopsEnabled.get()) {
                    if (ShopManager.getInstance().getShopAt(world, pos) != null) {
                        return; // Allow shop interaction
                    }
                }
            }
            // Check sign shops - signs with [Buy] or [Sell] on first line
            if (state.is(BlockTags.STANDING_SIGNS) || state.is(BlockTags.WALL_SIGNS)) {
                if (event.getLevel()
                        .getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
                    String line1 = sign.getFrontText().getMessage(0, false).getString().toLowerCase();
                    if (line1.contains("[buy]") || line1.contains("[sell]")) {
                        return; // Allow sign shop interaction
                    }
                }
            }
        }

        // Block container access
        if (ClaimsConfig.CONFIG.protectContainers.get()) {
            if (state.getBlock() instanceof ChestBlock || state.is(Blocks.BARREL) ||
                    state.is(Blocks.HOPPER) || state.is(Blocks.DROPPER) ||
                    state.is(Blocks.DISPENSER) || state.is(Blocks.FURNACE) ||
                    state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER) ||
                    state.is(Blocks.BREWING_STAND) || state.is(Blocks.SHULKER_BOX)) {
                event.setCanceled(true);
                player.sendSystemMessage(Component.literal("§cYou can't access containers in this claim!"));
                return;
            }
        }

        // Block other interactions (doors, buttons, etc. are usually allowed)
    }

    /**
     * Handle wand selection (golden shovel clicks)
     */
    @SubscribeEvent
    public static void onWandLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!ClaimsConfig.CONFIG.enabled.get())
            return;
        if (event.getLevel().isClientSide())
            return;

        if (!(event.getEntity() instanceof ServerPlayer player))
            return;

        // Check if holding golden shovel
        if (!player.getMainHandItem().is(Items.GOLDEN_SHOVEL))
            return;

        BlockPos pos = event.getPos();
        ClaimsManager.getInstance().setCorner1(player.getUUID(), pos);
        player.sendSystemMessage(Component.literal(String.format(
                "§aCorner 1 set: §e%d, %d, %d", pos.getX(), pos.getY(), pos.getZ())));
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onWandRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!ClaimsConfig.CONFIG.enabled.get())
            return;
        if (event.getLevel().isClientSide())
            return;

        if (!(event.getEntity() instanceof ServerPlayer player))
            return;

        // Check if holding golden shovel
        if (!player.getMainHandItem().is(Items.GOLDEN_SHOVEL))
            return;

        // Don't interfere with chest shop creation or other features
        BlockPos pos = event.getPos();
        var state = event.getLevel().getBlockState(pos);

        // Skip if interacting with a functional block
        if (state.getBlock() instanceof ChestBlock || state.is(Blocks.BARREL) ||
                state.is(BlockTags.STANDING_SIGNS) || state.is(BlockTags.WALL_SIGNS)) {
            return;
        }

        ClaimsManager.getInstance().setCorner2(player.getUUID(), pos);
        player.sendSystemMessage(Component.literal(String.format(
                "§aCorner 2 set: §e%d, %d, %d", pos.getX(), pos.getY(), pos.getZ())));

        // Show selection size
        BlockPos corner1 = ClaimsManager.getInstance().getCorner1(player.getUUID());
        if (corner1 != null) {
            int sizeX = Math.abs(pos.getX() - corner1.getX()) + 1;
            int sizeZ = Math.abs(pos.getZ() - corner1.getZ()) + 1;
            player.sendSystemMessage(Component.literal(String.format(
                    "§7Selection: §f%dx%d §7(%d blocks)", sizeX, sizeZ, sizeX * sizeZ)));
            player.sendSystemMessage(Component.literal("§7Use §e/vcclaims create §7to create claim"));
        }
    }

    /**
     * Handle explosions
     */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!ClaimsConfig.CONFIG.enabled.get() || !ClaimsConfig.CONFIG.preventExplosions.get())
            return;

        String world = event.getLevel().dimension().location().toString();

        // Remove blocks in claims from explosion
        event.getAffectedBlocks().removeIf(pos -> {
            Claim claim = ClaimsManager.getInstance().getClaimAt(world, pos);
            return claim != null;
        });
    }
}
