package com.prisonexperiment;

import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a prison compound and registers everything the mod needs.
 *
 * Parameters: B = number of cell blocks (stacked north to south), C = cells per side of each block.
 *   large  B=4 C=7 : 56 cells (168 inmates), 68 x 72 blocks
 *   medium B=2 C=6 : 24 cells ( 72 inmates), 52 x 40 blocks
 *   small  B=1 C=4 :  8 cells ( 24 inmates), 56 x 24 blocks
 *
 * Layout (x = east, z = south, origin = north-west corner, y = 0 is the floor):
 *   cell blocks   x 2..XE, each with 2 rows of cells around a corridor and an iron yard door on the east side
 *   yard          east of the cell blocks, 36 wide, gravel with paths, benches, light posts
 *   barracks + warden office along the south side, main gate in the south wall, 4 corner towers
 */
public final class PrisonBuilder {
    private static final int X0 = 2, Z0 = 2, PITCH = 16, YW = 36;

    private final ServerWorld w;
    private final int ox, oy, oz;
    private final int B, C;
    private final int XE, ZEND, ZB0, WIDTH, DEPTH, GX;

    private final List<BlockPos> poss = new ArrayList<>(120000);
    private final List<BlockState> states = new ArrayList<>(120000);

    private static final BlockState AIR = Blocks.AIR.getDefaultState();
    private static final BlockState BRICK = Blocks.STONE_BRICKS.getDefaultState();
    private static final BlockState CRACKED = Blocks.CRACKED_STONE_BRICKS.getDefaultState();

    private PrisonBuilder(ServerWorld w, BlockPos origin, int blocks, int cols) {
        this.w = w;
        this.ox = origin.getX();
        this.oy = origin.getY();
        this.oz = origin.getZ();
        this.B = blocks;
        this.C = cols;
        this.XE = X0 + 4 * cols;
        this.ZEND = Z0 + PITCH * blocks - 4;
        this.ZB0 = ZEND + 3;
        this.WIDTH = XE + YW + 2;
        this.DEPTH = ZB0 + 7;
        this.GX = XE + 1 + YW / 2;
    }

    public static String describe(int blocks, int cols) {
        int cells = 2 * blocks * cols;
        return (4 * cols + 2 + YW + 2) + " x " + (Z0 + PITCH * blocks + 6) + " blocks, " + cells + " cells (room for "
                + cells * 3 + " inmates)";
    }

    /**
     * @param origin north-west corner; origin.getY() is the FLOOR level (the block row you stand on).
     * @return false if a build is already running.
     */
    public static boolean build(ServerWorld world, BlockPos origin, PrisonData data, int blocks, int cols, Runnable onDone) {
        if (BuildQueue.busy()) return false;
        PrisonBuilder b = new PrisonBuilder(world, origin, blocks, cols);
        b.run(data);
        BuildQueue.start(world, b.poss, b.states, onDone);
        return true;
    }

    // ---------------------------------------------------------------- primitives (queued)

    private BlockPos pos(int x, int y, int z) {
        return new BlockPos(ox + x, oy + y, oz + z);
    }

    private void set(int x, int y, int z, BlockState s) {
        poss.add(pos(x, y, z));
        states.add(s);
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState s) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                    set(x, y, z, s);
    }

    private void door(Block block, int x, int y, int z, Direction facing) {
        BlockState lower = block.getDefaultState().with(DoorBlock.FACING, facing).with(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        set(x, y, z, lower);
        set(x, y + 1, z, lower.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private void bed(int footX, int footZ, Direction headDir) {
        BlockState foot = Blocks.ORANGE_BED.getDefaultState().with(BedBlock.FACING, headDir).with(BedBlock.PART, BedPart.FOOT);
        set(footX, 1, footZ, foot);
        set(footX + headDir.getOffsetX(), 1, footZ + headDir.getOffsetZ(), foot.with(BedBlock.PART, BedPart.HEAD));
    }

    private void hangingLantern(int x, int y, int z) {
        set(x, y, z, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
    }

    private BlockState bars(boolean alongX) {
        BlockState b = Blocks.IRON_BARS.getDefaultState();
        return alongX ? b.with(PaneBlock.EAST, true).with(PaneBlock.WEST, true)
                      : b.with(PaneBlock.NORTH, true).with(PaneBlock.SOUTH, true);
    }

    private BlockState wallBlock(int x, int y, int z) {
        return (x * 31 + y * 7 + z * 17) % 11 == 0 ? CRACKED : BRICK;
    }

    private void wallColumn(int x, int z) {
        for (int y = 1; y <= 6; y++) set(x, y, z, wallBlock(x, y, z));
        if ((x + z) % 2 == 0) set(x, 7, z, BRICK);
    }

    // ---------------------------------------------------------------- build

    private void run(PrisonData data) {
        data.cells.clear();
        data.cellDoors.clear();
        data.lockedDoors.clear();
        data.yardDoors.clear();
        data.posts.clear();
        data.patrol.clear();

        // clear site, foundation, floors
        fill(-1, 1, -1, WIDTH, 16, DEPTH, AIR);
        fill(-1, -5, -1, WIDTH, -1, DEPTH, Blocks.COBBLESTONE.getDefaultState());
        fill(0, 0, 0, WIDTH - 1, 0, DEPTH - 1, Blocks.STONE.getDefaultState());
        fill(XE + 1, 0, 1, WIDTH - 2, 0, DEPTH - 2, Blocks.GRAVEL.getDefaultState());
        fill(GX, 0, 1, GX, 0, DEPTH - 2, BRICK);

        // outer wall (perimeter only)
        for (int x = 0; x < WIDTH; x++) {
            wallColumn(x, 0);
            wallColumn(x, DEPTH - 1);
        }
        for (int z = 1; z < DEPTH - 1; z++) {
            wallColumn(0, z);
            wallColumn(WIDTH - 1, z);
        }

        for (int k = 0; k < B; k++) cellBlock(data, Z0 + PITCH * k);
        barracks();
        yard();
        gate(data);
        tower(1, 1, 1, 1);
        tower(WIDTH - 2, 1, -1, 1);
        tower(1, DEPTH - 2, 1, -1);
        tower(WIDTH - 2, DEPTH - 2, -1, -1);

        registerData(data);
    }

    private void cellBlock(PrisonData data, int zb) {
        fill(X0, 1, zb, XE, 4, zb + 12, BRICK);
        fill(X0 + 1, 1, zb + 1, XE - 1, 4, zb + 11, AIR);
        fill(X0, 5, zb, XE, 5, zb + 12, BRICK);
        fill(X0 + 1, 0, zb + 1, XE - 1, 0, zb + 11, Blocks.SMOOTH_STONE.getDefaultState());
        fill(X0 + 1, 1, zb + 4, XE - 1, 4, zb + 4, BRICK);
        fill(X0 + 1, 1, zb + 8, XE - 1, 4, zb + 8, BRICK);
        for (int i = 0; i < C - 1; i++) {
            int dx = X0 + 4 + 4 * i;
            fill(dx, 1, zb + 1, dx, 4, zb + 3, BRICK);
            fill(dx, 1, zb + 9, dx, 4, zb + 11, BRICK);
        }

        for (int i = 0; i < C; i++) {
            int cx = X0 + 2 + 4 * i;
            int a = cx - 1;

            // north cell (door on zb+4, faces the corridor)
            set(cx, 1, zb + 4, AIR);
            set(cx, 2, zb + 4, AIR);
            door(Blocks.IRON_DOOR, cx, 1, zb + 4, Direction.SOUTH);
            set(cx, 3, zb, bars(true));
            bed(a, zb + 2, Direction.NORTH);
            bed(a + 2, zb + 2, Direction.NORTH);
            set(cx, 1, zb + 1, Blocks.CAULDRON.getDefaultState());
            hangingLantern(cx, 4, zb + 2);
            long nDoor = pos(cx, 1, zb + 4).asLong();
            data.cells.add(new long[]{nDoor, pos(cx, 1, zb + 2).asLong()});
            data.cellDoors.add(nDoor);
            data.lockedDoors.add(nDoor);

            // south cell (door on zb+8)
            set(cx, 1, zb + 8, AIR);
            set(cx, 2, zb + 8, AIR);
            door(Blocks.IRON_DOOR, cx, 1, zb + 8, Direction.NORTH);
            set(cx, 3, zb + 12, bars(true));
            bed(a, zb + 10, Direction.SOUTH);
            bed(a + 2, zb + 10, Direction.SOUTH);
            set(cx, 1, zb + 11, Blocks.CAULDRON.getDefaultState());
            hangingLantern(cx, 4, zb + 10);
            long sDoor = pos(cx, 1, zb + 8).asLong();
            data.cells.add(new long[]{sDoor, pos(cx, 1, zb + 10).asLong()});
            data.cellDoors.add(sDoor);
            data.lockedDoors.add(sDoor);

            hangingLantern(cx, 4, zb + 6);
        }

        // yard door at the east end of the corridor
        set(XE, 1, zb + 6, AIR);
        set(XE, 2, zb + 6, AIR);
        door(Blocks.IRON_DOOR, XE, 1, zb + 6, Direction.EAST);
        BlockPos yd = pos(XE, 1, zb + 6);
        data.yardDoors.add(yd);
        data.cellDoors.add(yd.asLong());
        data.lockedDoors.add(yd.asLong());

        // path from the yard door to the central road
        fill(XE + 1, 0, zb + 6, GX, 0, zb + 6, BRICK);
    }

    private void barracks() {
        // guard barracks: x 2..22
        fill(2, 1, ZB0, 22, 4, ZB0 + 4, BRICK);
        fill(3, 1, ZB0 + 1, 21, 4, ZB0 + 3, AIR);
        fill(2, 5, ZB0, 22, 5, ZB0 + 4, BRICK);
        fill(3, 0, ZB0 + 1, 21, 0, ZB0 + 3, Blocks.SPRUCE_PLANKS.getDefaultState());
        set(12, 1, ZB0, AIR);
        set(12, 2, ZB0, AIR);
        door(Blocks.OAK_DOOR, 12, 1, ZB0, Direction.NORTH);
        BlockState glass = Blocks.GLASS.getDefaultState();
        for (int x : new int[]{6, 12, 18}) set(x, 2, ZB0 + 4, glass);
        for (int x = 4; x <= 20; x += 2) bed(x, ZB0 + 2, Direction.NORTH);
        set(21, 1, ZB0 + 1, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.WEST));
        set(21, 1, ZB0 + 2, Blocks.BARREL.getDefaultState());
        set(21, 1, ZB0 + 3, Blocks.CRAFTING_TABLE.getDefaultState());
        hangingLantern(8, 4, ZB0 + 3);
        hangingLantern(16, 4, ZB0 + 3);

        // warden's office: x 24..32
        fill(24, 1, ZB0, 32, 4, ZB0 + 4, BRICK);
        fill(25, 1, ZB0 + 1, 31, 4, ZB0 + 3, AIR);
        fill(24, 5, ZB0, 32, 5, ZB0 + 4, BRICK);
        fill(25, 0, ZB0 + 1, 31, 0, ZB0 + 3, Blocks.SPRUCE_PLANKS.getDefaultState());
        set(28, 1, ZB0, AIR);
        set(28, 2, ZB0, AIR);
        door(Blocks.OAK_DOOR, 28, 1, ZB0, Direction.NORTH);
        set(26, 2, ZB0 + 4, glass);
        set(30, 2, ZB0 + 4, glass);
        bed(26, ZB0 + 2, Direction.NORTH);
        set(31, 1, ZB0 + 1, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.WEST));
        set(31, 1, ZB0 + 3, Blocks.CRAFTING_TABLE.getDefaultState());
        hangingLantern(28, 4, ZB0 + 2);
    }

    private void yard() {
        BlockState bench = Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.WEST);
        for (int z = 6; z < DEPTH - 6; z += 8) {
            for (int dz = 0; dz < 3; dz++) set(WIDTH - 2, 1, z + dz, bench);
        }
        for (int x : new int[]{XE + 8, WIDTH - 9}) {
            for (int z = 6; z < DEPTH - 5; z += 12) {
                if (Math.abs(z - (DEPTH - 3)) < 4) continue;
                fill(x, 1, z, x, 3, z, BRICK);
                set(x, 4, z, Blocks.LANTERN.getDefaultState());
            }
        }
    }

    private void gate(PrisonData data) {
        set(GX, 1, DEPTH - 1, AIR);
        set(GX, 2, DEPTH - 1, AIR);
        door(Blocks.IRON_DOOR, GX, 1, DEPTH - 1, Direction.SOUTH);
        set(GX, 3, DEPTH - 1, bars(true));
        set(GX, 4, DEPTH - 1, bars(true));
        set(GX - 1, 8, DEPTH - 1, Blocks.LANTERN.getDefaultState());
        set(GX + 1, 8, DEPTH - 1, Blocks.LANTERN.getDefaultState());
        BlockPos g = pos(GX, 1, DEPTH - 1);
        data.gate = g;
        data.cellDoors.add(g.asLong());
        data.lockedDoors.add(g.asLong());
    }

    /** Corner tower. (ix, iz) point from the tower towards the inside of the compound. */
    private void tower(int cx, int cz, int ix, int iz) {
        fill(cx - 1, 1, cz - 1, cx + 1, 8, cz + 1, BRICK);
        fill(cx, 1, cz, cx, 9, cz, AIR);
        set(cx + ix, 1, cz, AIR);
        set(cx + ix, 2, cz, AIR);
        set(cx - ix, 9, cz, BRICK);
        Direction facing = ix > 0 ? Direction.EAST : Direction.WEST;
        for (int y = 1; y <= 9; y++) {
            set(cx, y, cz, Blocks.LADDER.getDefaultState().with(LadderBlock.FACING, facing));
        }
        BlockState lantern = Blocks.LANTERN.getDefaultState();
        set(cx + ix, 9, cz + iz, lantern);
        set(cx - ix, 9, cz + iz, lantern);
        set(cx + ix, 9, cz - iz, lantern);
    }

    // ---------------------------------------------------------------- registration

    private void registerData(PrisonData data) {
        int midX = (X0 + XE) / 2;
        data.yardCenter = pos(GX, 1, DEPTH / 2);
        data.yardHalfX = YW / 2 - 2;
        data.yardHalfZ = DEPTH / 2 - 3;
        data.spawn = pos(midX, 1, Z0 + 6);
        data.pos1 = pos(0, -1, 0);
        data.pos2 = pos(WIDTH - 1, 14, DEPTH - 1);

        // patrol routes + lockdown posts
        for (int k = 0; k < B; k++) {
            int zc = Z0 + PITCH * k + 6;
            for (int x : new int[]{X0 + 3, midX, XE - 3}) {
                data.patrol.add(pos(x, 1, zc));
                data.posts.add(pos(x, 1, zc));
            }
            data.posts.add(pos(XE + 2, 1, zc));
            if (k < B - 1) data.patrol.add(pos(midX, 1, Z0 + PITCH * k + 14));
        }
        data.patrol.add(pos(midX, 1, ZEND + 2));
        for (int x : new int[]{XE + 8, GX, WIDTH - 9}) {
            for (int z : new int[]{8, DEPTH / 2, DEPTH - 9}) {
                data.patrol.add(pos(x, 1, z));
            }
        }
        data.patrol.add(pos(GX, 1, DEPTH - 3));
        for (int dx : new int[]{-3, 0, 3}) data.posts.add(pos(GX + dx, 1, DEPTH - 3));
        data.posts.add(pos(GX, 1, DEPTH / 2));
        data.posts.add(pos(XE + 8, 1, DEPTH / 2));
        data.posts.add(pos(WIDTH - 9, 1, DEPTH / 2));
        data.posts.add(pos(GX, 1, 6));
        data.posts.add(pos(GX, 1, DEPTH - 9));

        data.lockdown = false;
        data.riot = false;
        data.escaped.clear();
        data.markDirty();
    }
}
