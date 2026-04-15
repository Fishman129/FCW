package com.fishguy129.fcw.item;

import com.fishguy129.fcw.FCWMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

// Faction upgrade item
public class ClaimCatalystItem extends Item {
    public ClaimCatalystItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!context.getLevel().isClientSide() && context.getPlayer() instanceof ServerPlayer player) {
            if (FCWMod.CORE_MANAGER.applyClaimUpgrade(player, context.getClickedPos())) {
                context.getItemInHand().shrink(1);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.FAIL;
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
    }
}
