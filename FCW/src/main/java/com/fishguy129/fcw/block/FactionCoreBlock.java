package com.fishguy129.fcw.block;

import com.fishguy129.fcw.FCWMod;
import com.fishguy129.fcw.blockentity.FactionCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

// Double-height faction core.
// Both halves render their own normal 16x16 model.
// This avoids the lighting bug caused by rendering a 32-high model from only the lower block.
public class FactionCoreBlock extends BaseEntityBlock {
    public static final BooleanProperty ACTIVE = BlockStateProperties.LIT;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    private static final VoxelShape FULL_BLOCK_SHAPE = Block.box(
            0.0D, 0.0D, 0.0D,
            16.0D, 16.0D, 16.0D
    );

    public FactionCoreBlock(Properties properties) {
        super(properties);

        registerDefaultState(stateDefinition.any()
                .setValue(ACTIVE, true)
                .setValue(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE, HALF);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return 0.0F;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return FULL_BLOCK_SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return FULL_BLOCK_SHAPE;
    }

    @Override
    public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return FULL_BLOCK_SHAPE;
    }

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    public VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return false;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return 0;
    }

    @Override
    public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos lowerPos = context.getClickedPos();
        BlockPos upperPos = lowerPos.above();
        Level level = context.getLevel();

        if (upperPos.getY() >= level.getMaxBuildHeight()) {
            return null;
        }

        if (!level.getBlockState(upperPos).canBeReplaced(context)) {
            return null;
        }

        return defaultBlockState()
                .setValue(ACTIVE, true)
                .setValue(HALF, DoubleBlockHalf.LOWER);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide()) {
            BlockState upperState = defaultBlockState()
                    .setValue(ACTIVE, state.getValue(ACTIVE))
                    .setValue(HALF, DoubleBlockHalf.UPPER);

            level.setBlock(pos.above(), upperState, 3);
        }

        super.setPlacedBy(level, pos, state, placer, stack);
    }

    @Override
    public BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighborState,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos
    ) {
        DoubleBlockHalf half = state.getValue(HALF);

        if (direction == (half == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN)) {
            boolean matchingHalf = neighborState.is(this)
                    && neighborState.hasProperty(HALF)
                    && neighborState.getValue(HALF) != half;

            if (!matchingHalf) {
                return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            }
        }

        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public InteractionResult use(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit
    ) {
        if (player.isShiftKeyDown() && player.getItemInHand(hand).getItem() instanceof BlockItem) {
            return InteractionResult.PASS;
        }

        BlockPos lowerPos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();

        if (!level.isClientSide()) {
            BlockEntity blockEntity = level.getBlockEntity(lowerPos);

            if (blockEntity instanceof FactionCoreBlockEntity) {
                // GUI/open logic can go here later.
            }
        }

        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!oldState.is(newState.getBlock()) && !level.isClientSide()) {
            DoubleBlockHalf oldHalf = oldState.getValue(HALF);
            BlockPos counterpartPos = oldHalf == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState counterpartState = level.getBlockState(counterpartPos);

            if (counterpartState.is(this)
                    && counterpartState.hasProperty(HALF)
                    && counterpartState.getValue(HALF) != oldHalf) {
                level.setBlock(counterpartPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 35);
            }

            if (oldHalf == DoubleBlockHalf.LOWER) {
                FCWMod.CORE_MANAGER.onCoreBlockRemoved(level, pos);
            }
        }

        super.onRemove(oldState, level, pos, newState, moving);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER
                ? new FactionCoreBlockEntity(pos, state)
                : null;
    }
}