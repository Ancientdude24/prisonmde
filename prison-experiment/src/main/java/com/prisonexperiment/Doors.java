package com.prisonexperiment;

import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class Doors {
    private Doors() {}

    public static BlockPos lower(BlockState state, BlockPos pos) {
        return state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.down() : pos;
    }

    public static void setOpen(World world, BlockPos lower, boolean open, Entity actor) {
        BlockState s = world.getBlockState(lower);
        if (s.getBlock() instanceof DoorBlock door && s.get(DoorBlock.OPEN) != open) {
            door.setOpen(actor, world, s, lower, open);
        }
    }

    public static void toggle(World world, BlockPos lower, Entity actor) {
        BlockState s = world.getBlockState(lower);
        if (s.getBlock() instanceof DoorBlock) {
            setOpen(world, lower, !s.get(DoorBlock.OPEN), actor);
        }
    }
}
