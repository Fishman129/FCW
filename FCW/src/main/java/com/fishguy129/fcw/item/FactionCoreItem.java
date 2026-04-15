package com.fishguy129.fcw.item;

import com.fishguy129.fcw.FCWMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

// Block item wrapper so we can validate a newly placed core immediately and roll
// it back cleanly if the placement rules fail.
public class FactionCoreItem extends BlockItem {
    public FactionCoreItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        InteractionResult result = super.place(context);
        if (result.consumesAction() && context.getPlayer() instanceof ServerPlayer player) {
            // Vanilla placement already happened, so failed validation has to undo it here.
            if (!FCWMod.CORE_MANAGER.validatePostPlacement(player, context.getClickedPos())) {
                context.getLevel().removeBlock(context.getClickedPos(), false);
                if (!player.getAbilities().instabuild) {
                    player.getInventory().placeItemBackInInventory(new ItemStack(this));
                }
                return InteractionResult.FAIL;
            }
        }
        return result;
    }
}
