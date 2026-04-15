package com.fishguy129.fcw.block;

import com.fishguy129.fcw.FCWMod;
import com.fishguy129.fcw.blockentity.FactionCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

// The core block itself stays pretty strict. Most of the real rules live in the
// manager cuz I didn't wanna bloat this
public class FactionCoreBlock extends BaseEntityBlock {
    public static final BooleanProperty ACTIVE = BlockStateProperties.LIT;

    public FactionCoreBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ACTIVE, true));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(ACTIVE);
    }

    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return 0F;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.getItemInHand(hand).getItem() instanceof BlockItem) {
            return InteractionResult.PASS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moving) {
        // If the block disappears outside the normal pack/raid flow, mark the
        // saved record dirty so the team does not keep a ghost core.
        if (!oldState.is(newState.getBlock()) && !level.isClientSide()) {
            FCWMod.CORE_MANAGER.onCoreBlockRemoved(level, pos);
        }
        super.onRemove(oldState, level, pos, newState, moving);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FactionCoreBlockEntity(pos, state);
    }
}
