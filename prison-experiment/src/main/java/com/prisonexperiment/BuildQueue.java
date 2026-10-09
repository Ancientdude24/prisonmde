package com.prisonexperiment;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Spreads big block placements and mass entity spawns over many ticks so the game does not freeze. */
public final class BuildQueue {
    private BuildQueue() {}

    private static final int FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
    private static final int BLOCKS_PER_TICK = 12000;
    private static final int TASKS_PER_TICK = 6;

    private static ServerWorld world;
    private static List<BlockPos> poss;
    private static List<BlockState> states;
    private static int index;
    private static Runnable onDone;
    private static final Deque<Runnable> tasks = new ArrayDeque<>();

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(BuildQueue::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            poss = null;
            states = null;
            tasks.clear();
        });
    }

    public static boolean busy() {
        return poss != null;
    }

    public static int progressPercent() {
        return poss == null || poss.isEmpty() ? 100 : index * 100 / poss.size();
    }

    public static void start(ServerWorld w, List<BlockPos> p, List<BlockState> s, Runnable done) {
        world = w;
        poss = p;
        states = s;
        index = 0;
        onDone = done;
    }

    /** Run a task on a later tick (a few per tick). */
    public static void later(Runnable r) {
        tasks.add(r);
    }

    private static void tick(MinecraftServer server) {
        if (poss != null) {
            int n = 0;
            while (index < poss.size() && n < BLOCKS_PER_TICK) {
                world.setBlockState(poss.get(index), states.get(index), FLAGS);
                index++;
                n++;
            }
            if (index >= poss.size()) {
                Runnable d = onDone;
                poss = null;
                states = null;
                onDone = null;
                if (d != null) d.run();
            }
        }
        int t = 0;
        while (!tasks.isEmpty() && t < TASKS_PER_TICK) {
            tasks.poll().run();
            t++;
        }
    }
}
