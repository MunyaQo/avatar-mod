package com.avatarlink.block;

import com.avatarlink.link.LinkManager;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

/** Капсула связи: ПКМ по ней - вселение в ближайшего подготовленного моба. */
public class LinkPodBlock extends Block {
    public LinkPodBlock(AbstractBlock.Properties properties) {
        super(properties);
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
}
