package com.avatarlink.block;

import com.avatarlink.link.LinkManager;
import javax.annotation.Nullable;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalBlock;
import net.minecraft.block.material.PushReaction;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.BlockItemUseContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.BooleanProperty;
import net.minecraft.state.EnumProperty;
import net.minecraft.state.StateContainer;
import net.minecraft.state.properties.BedPart;
import net.minecraft.state.properties.BlockStateProperties;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.shapes.ISelectionContext;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.IWorld;
import net.minecraft.world.IWorldReader;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

/**
 * Капсула связи: двухблочная "кровать" в хай-тек стиле.
 * FACING - направление от ножного блока к головному. OPEN - крышка открыта.
 */
public class LinkPodBlock extends HorizontalBlock {
    public static final EnumProperty<BedPart> PART = BlockStateProperties.BED_PART;
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;

    private static final VoxelShape OUTLINE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 12.0D, 16.0D);
    private static final VoxelShape COLLISION = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 6.0D, 16.0D);

    public LinkPodBlock(AbstractBlock.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(PART, BedPart.FOOT)
                .setValue(OPEN, Boolean.TRUE)
                .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateContainer.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, OPEN);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockItemUseContext context) {
        Direction direction = context.getHorizontalDirection();
        BlockPos pos = context.getClickedPos();
        BlockPos headPos = pos.relative(direction);
        return context.getLevel().getBlockState(headPos).canBeReplaced(context)
                ? this.defaultBlockState().setValue(FACING, direction)
                : null;
    }

    @Override
    public void setPlacedBy(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(world, pos, state, placer, stack);
        if (!world.isClientSide) {
            BlockPos headPos = pos.relative(state.getValue(FACING));
            world.setBlock(headPos, state.setValue(PART, BedPart.HEAD), 3);
            world.blockUpdated(pos, Blocks.AIR);
            state.updateNeighbourShapes(world, pos, 3);
        }
    }

    @Override
    public BlockState updateShape(BlockState state, Direction facing, BlockState facingState, IWorld world, BlockPos pos, BlockPos facingPos) {
        if (facing == getNeighbourDirection(state.getValue(PART), state.getValue(FACING))) {
            return facingState.is(this) && facingState.getValue(PART) != state.getValue(PART)
                    ? state.setValue(OPEN, facingState.getValue(OPEN))
                    : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, facing, facingState, world, pos, facingPos);
    }

    @Override
    public void playerWillDestroy(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClientSide && player.isCreative()) {
            BedPart part = state.getValue(PART);
            if (part == BedPart.FOOT) {
                BlockPos otherPos = pos.relative(getNeighbourDirection(part, state.getValue(FACING)));
                BlockState other = world.getBlockState(otherPos);
                if (other.getBlock() == this && other.getValue(PART) == BedPart.HEAD) {
                    world.setBlock(otherPos, Blocks.AIR.defaultBlockState(), 35);
                    world.levelEvent(player, 2001, otherPos, Block.getId(other));
                }
            }
        }
        super.playerWillDestroy(world, pos, state, player);
    }

    @Override
    public PushReaction getPistonPushReaction(BlockState state) {
        return PushReaction.DESTROY;
    }

    @Override
    public VoxelShape getShape(BlockState state, IBlockReader world, BlockPos pos, ISelectionContext context) {
        return OUTLINE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, IBlockReader world, BlockPos pos, ISelectionContext context) {
        return COLLISION;
    }

    @Override
    public ActionResultType use(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockRayTraceResult hit) {
        if (world.isClientSide) {
            return ActionResultType.SUCCESS;
        }
        if (player instanceof ServerPlayerEntity && world instanceof ServerWorld) {
            LinkManager.tryStart((ServerPlayerEntity) player, (ServerWorld) world, pos);
        }
        return ActionResultType.CONSUME;
    }

    // Forge: блок считается кроватью (нужно для позы лёжа и ориентации тела).

    @Override
    public boolean isBed(BlockState state, IBlockReader world, BlockPos pos, @Nullable Entity player) {
        return true;
    }

    @Override
    public Direction getBedDirection(BlockState state, IWorldReader world, BlockPos pos) {
        return state.getValue(FACING);
    }

    // --- помощники

    public static Direction getNeighbourDirection(BedPart part, Direction facing) {
        return part == BedPart.FOOT ? facing : facing.getOpposite();
    }

    /** Позиция головного блока капсулы. */
    public static BlockPos headPos(BlockState state, BlockPos pos) {
        return state.getValue(PART) == BedPart.HEAD ? pos : pos.relative(state.getValue(FACING));
    }

    /** Открыть или закрыть крышку у обеих половин. */
    public static void setOpen(World world, BlockPos pos, boolean open) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof LinkPodBlock)) {
            return;
        }
        BlockPos otherPos = pos.relative(getNeighbourDirection(state.getValue(PART), state.getValue(FACING)));
        world.setBlock(pos, state.setValue(OPEN, open), 3);
        BlockState other = world.getBlockState(otherPos);
        if (other.getBlock() instanceof LinkPodBlock) {
            world.setBlock(otherPos, other.setValue(OPEN, open), 3);
        }
    }
}
