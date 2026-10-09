package com.prisonexperiment;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** All saved prison state (stored with the overworld). */
public class PrisonData extends PersistentState {
    public static final PersistentState.Type<PrisonData> TYPE =
            new PersistentState.Type<>(PrisonData::new, PrisonData::fromNbt, null);

    public final Map<UUID, Role> roles = new HashMap<>();
    public final Set<Long> cellDoors = new HashSet<>();   // lower-half BlockPos.asLong() of every registered door
    public final Set<Long> lockedDoors = new HashSet<>();
    public final List<long[]> cells = new ArrayList<>();   // {doorLowerPos, insidePos} per prisoner cell
    public final List<BlockPos> posts = new ArrayList<>();  // guard posts (lockdown)
    public final List<BlockPos> patrol = new ArrayList<>(); // patrol waypoints
    public BlockPos pos1, pos2, spawn, yardCenter, gate;
    public final List<BlockPos> yardDoors = new ArrayList<>();
    public int yardHalfX = 6, yardHalfZ = 8;
    public boolean lockdown, riot;

    /** Runtime only (not saved): prisoner players currently outside the prison zone. */
    public final Set<UUID> escaped = new HashSet<>();

    public static PrisonData get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "prisonexperiment");
    }

    public Role roleOf(UUID id) {
        return roles.get(id);
    }

    public Role roleOf(PlayerEntity p) {
        return roles.get(p.getUuid());
    }

    public boolean isLocked(long door) {
        return lockdown || lockedDoors.contains(door);
    }

    public boolean hasZone() {
        return pos1 != null && pos2 != null;
    }

    /** True if the block position is inside the prison zone (or no zone is defined). */
    public boolean contains(BlockPos b) {
        if (!hasZone()) return true;
        return b.getX() >= Math.min(pos1.getX(), pos2.getX()) && b.getX() <= Math.max(pos1.getX(), pos2.getX())
                && b.getY() >= Math.min(pos1.getY(), pos2.getY()) && b.getY() <= Math.max(pos1.getY(), pos2.getY())
                && b.getZ() >= Math.min(pos1.getZ(), pos2.getZ()) && b.getZ() <= Math.max(pos1.getZ(), pos2.getZ());
    }

    public boolean isInside(ServerPlayerEntity p) {
        if (!hasZone()) return true;
        if (p.getWorld().getRegistryKey() != World.OVERWORLD) return false;
        return contains(p.getBlockPos());
    }

    private static void putPos(NbtCompound n, String key, BlockPos p) {
        if (p != null) n.putLong(key, p.asLong());
    }

    private static BlockPos getPos(NbtCompound n, String key) {
        return n.contains(key) ? BlockPos.fromLong(n.getLong(key)) : null;
    }

    private static long[] toLongs(List<BlockPos> l) {
        return l.stream().mapToLong(BlockPos::asLong).toArray();
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        NbtCompound r = new NbtCompound();
        roles.forEach((id, role) -> r.putString(id.toString(), role.name()));
        nbt.put("roles", r);
        nbt.putLongArray("cellDoors", cellDoors.stream().mapToLong(Long::longValue).toArray());
        nbt.putLongArray("locked", lockedDoors.stream().mapToLong(Long::longValue).toArray());
        long[] flat = new long[cells.size() * 2];
        for (int i = 0; i < cells.size(); i++) {
            flat[i * 2] = cells.get(i)[0];
            flat[i * 2 + 1] = cells.get(i)[1];
        }
        nbt.putLongArray("cellPairs", flat);
        nbt.putLongArray("posts", toLongs(posts));
        nbt.putLongArray("patrol", toLongs(patrol));
        putPos(nbt, "pos1", pos1);
        putPos(nbt, "pos2", pos2);
        putPos(nbt, "spawn", spawn);
        putPos(nbt, "yardCenter", yardCenter);
        putPos(nbt, "gate", gate);
        nbt.putLongArray("yardDoors", toLongs(yardDoors));
        nbt.putInt("yardHalfX", yardHalfX);
        nbt.putInt("yardHalfZ", yardHalfZ);
        nbt.putBoolean("lockdown", lockdown);
        nbt.putBoolean("riot", riot);
        return nbt;
    }

    public static PrisonData fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        PrisonData d = new PrisonData();
        NbtCompound r = nbt.getCompound("roles");
        for (String k : r.getKeys()) {
            Role role = Role.byName(r.getString(k));
            if (role == null) continue;
            try {
                d.roles.put(UUID.fromString(k), role);
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (long l : nbt.getLongArray("cellDoors")) d.cellDoors.add(l);
        for (long l : nbt.getLongArray("locked")) d.lockedDoors.add(l);
        long[] flat = nbt.getLongArray("cellPairs");
        for (int i = 0; i + 1 < flat.length; i += 2) d.cells.add(new long[]{flat[i], flat[i + 1]});
        for (long l : nbt.getLongArray("posts")) d.posts.add(BlockPos.fromLong(l));
        for (long l : nbt.getLongArray("patrol")) d.patrol.add(BlockPos.fromLong(l));
        d.pos1 = getPos(nbt, "pos1");
        d.pos2 = getPos(nbt, "pos2");
        d.spawn = getPos(nbt, "spawn");
        d.yardCenter = getPos(nbt, "yardCenter");
        d.gate = getPos(nbt, "gate");
        for (long l : nbt.getLongArray("yardDoors")) d.yardDoors.add(BlockPos.fromLong(l));
        if (nbt.contains("yardHalfX")) {
            d.yardHalfX = nbt.getInt("yardHalfX");
            d.yardHalfZ = nbt.getInt("yardHalfZ");
        }
        d.lockdown = nbt.getBoolean("lockdown");
        d.riot = nbt.getBoolean("riot");
        return d;
    }
}
