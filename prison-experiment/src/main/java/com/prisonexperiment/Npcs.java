package com.prisonexperiment;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Villager guards and prisoners: spawning, orders and the AI that drives them. */
public final class Npcs {
    private Npcs() {}

    public static final String TAG = "prison_npc";
    public static final String T_GUARD = "prison_guard";
    public static final String T_PRISONER = "prison_prisoner";
    public static final String T_CONTRA = "prison_contra";
    public static final String T_CELL = "prison_cell_";

    public enum Order {
        PATROL("Patrol the prison"),
        FOLLOW("Follow me"),
        STAY("Hold position"),
        RAID("Cell raid - search everyone"),
        YARD("Yard time"),
        CELLS("Lights out - back to cells"),
        LOCKDOWN("Lockdown (toggle)"),
        HEADCOUNT("Headcount"),
        CRUSH("Crush the riot");

        public final String label;

        Order(String label) {
            this.label = label;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Phase { CELLS, YARD, LINEUP, STAND }

    public static final class Npc {
        public final VillagerEntity e;
        public final boolean guard;
        public int cell = -1;
        public boolean contraband;
        public Vec3d dest;
        public int cooldown, wander, timeout, patrolIdx, escape, escapeTicks, bucket;
        public long lastSay;
        public UUID target;

        Npc(VillagerEntity e, boolean guard) {
            this.e = e;
            this.guard = guard;
        }
    }

    // ---------------------------------------------------------------- state

    public static final Map<UUID, Npc> ALL = new LinkedHashMap<>();
    public static final Deque<UUID> raidQueue = new ArrayDeque<>();
    public static final Map<UUID, Long> wanted = new HashMap<>();
    private static final Map<UUID, Long> playerSearchCd = new HashMap<>();
    private static final Random RNG = new Random();
    private static int bucketCounter;

    public static Order guardOrder = Order.PATROL;
    public static Phase phase = Phase.CELLS;
    public static UUID followId;
    public static boolean crush;
    public static long phaseStart, headcountAt = -1, riotEnd = -1;
    public static int raidFound, raidDone, escapedTotal;

    private static final String[] GUARD_NAMES = {"Mike", "Brandt", "Hollis", "Reyes", "Dunn", "Okafor", "Kowalski", "Frost", "Marsh", "Tanaka"};
    private static final String[] COMPLAINTS = {
            "The food in here is terrible.", "I didn't do it!", "Got any cigarettes, boss?",
            "When's my parole hearing?", "I'm innocent, I swear.", "Hmph.", "This place gives me the creeps."};

    // ---------------------------------------------------------------- registration

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof VillagerEntity v && v.getCommandTags().contains(TAG)) adopt(v);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> ALL.remove(entity.getUuid()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
        ServerTickEvents.END_SERVER_TICK.register(Npcs::tick);
    }

    private static void reset() {
        ALL.clear();
        raidQueue.clear();
        wanted.clear();
        playerSearchCd.clear();
        guardOrder = Order.PATROL;
        phase = Phase.CELLS;
        followId = null;
        crush = false;
        headcountAt = -1;
        riotEnd = -1;
    }

    private static Npc adopt(VillagerEntity v) {
        Npc n = new Npc(v, v.getCommandTags().contains(T_GUARD));
        for (String t : v.getCommandTags()) {
            if (t.startsWith(T_CELL)) {
                try {
                    n.cell = Integer.parseInt(t.substring(T_CELL.length()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        n.contraband = v.getCommandTags().contains(T_CONTRA);
        n.patrolIdx = RNG.nextInt(8);
        n.bucket = bucketCounter++ % 4;
        ALL.put(v.getUuid(), n);
        return n;
    }

    public static boolean isNpc(Entity e) {
        return e instanceof VillagerEntity v && v.getCommandTags().contains(TAG);
    }

    public static VillagerEntity spawn(ServerWorld w, Vec3d pos, boolean guard, int cell) {
        VillagerEntity v = EntityType.VILLAGER.create(w);
        if (v == null) return null;
        v.refreshPositionAndAngles(pos.x, pos.y, pos.z, RNG.nextFloat() * 360f, 0f);
        v.addCommandTag(TAG);
        v.addCommandTag(guard ? T_GUARD : T_PRISONER);
        v.setVillagerData(new VillagerData(VillagerType.PLAINS, guard ? VillagerProfession.ARMORER : VillagerProfession.NONE, 1));
        v.setPersistent();
        v.setCanPickUpLoot(false);
        v.setCustomNameVisible(true);
        if (guard) {
            v.setCustomName(Text.literal("Guard " + GUARD_NAMES[RNG.nextInt(GUARD_NAMES.length)]).formatted(Formatting.BLUE));
            var hp = v.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
            if (hp != null) hp.setBaseValue(40.0);
            v.setHealth(40f);
            v.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            v.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0f);
        } else {
            v.setCustomName(Text.literal("Inmate #" + (1000 + RNG.nextInt(9000))).formatted(Formatting.GOLD));
            v.addCommandTag(T_CELL + cell);
            if (RNG.nextInt(100) < 35) v.addCommandTag(T_CONTRA);
        }
        w.spawnEntity(v);
        return v;
    }

    public static int clearAll() {
        int n = 0;
        for (Npc npc : new ArrayList<>(ALL.values())) {
            npc.e.discard();
            n++;
        }
        ALL.clear();
        return n;
    }

    // ---------------------------------------------------------------- speech

    public static void say(VillagerEntity e, String msg) {
        if (!(e.getWorld() instanceof ServerWorld w)) return;
        Text t = Text.literal("<").append(e.getName()).append(Text.literal("> " + msg));
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.squaredDistanceTo(e) < 1600) p.sendMessage(t, false);
        }
    }

    private static void sayLimited(Npc n, String msg, long now) {
        if (now - n.lastSay < 160) return;
        n.lastSay = now;
        say(n.e, msg);
    }

    public static void talk(ServerPlayerEntity player, VillagerEntity v) {
        if (v.getCommandTags().contains(T_GUARD)) {
            say(v, "Yes, " + (player.getServer() != null && PrisonData.get(player.getServer()).roleOf(player) == Role.WARDEN
                    ? "Warden? Use your radio to give orders." : "inmate? Move along."));
        } else {
            say(v, COMPLAINTS[RNG.nextInt(COMPLAINTS.length)]);
        }
    }

    /** Baton used (right-click) on an NPC. */
    public static ActionResult batonUse(ServerPlayerEntity player, VillagerEntity v) {
        PrisonData data = PrisonData.get(player.getServer());
        Role r = data.roleOf(player);
        if (r == null || !r.isStaff() || player.getItemCooldownManager().isCoolingDown(ModItems.BATON)) return ActionResult.FAIL;
        player.getItemCooldownManager().set(ModItems.BATON, 30);
        Npc n = ALL.get(v.getUuid());
        if (n == null) return ActionResult.FAIL;
        if (n.guard) {
            say(v, "At your command.");
            return ActionResult.SUCCESS;
        }
        v.getNavigation().stop();
        if (n.contraband) {
            n.contraband = false;
            v.removeCommandTag(T_CONTRA);
            player.sendMessage(Text.literal("Confiscated contraband from " + v.getName().getString() + "!").formatted(Formatting.GREEN), false);
            say(v, "Ah, come on!");
        } else {
            player.sendMessage(Text.literal(v.getName().getString() + " is clean.").formatted(Formatting.GRAY), false);
        }
        return ActionResult.SUCCESS;
    }

    // ---------------------------------------------------------------- orders

    public static void issue(MinecraftServer server, Order o, ServerPlayerEntity warden) {
        PrisonData data = PrisonData.get(server);
        ServerWorld w = server.getOverworld();
        long now = server.getTicks();
        followId = null;
        for (Npc n : ALL.values()) {
            n.target = null;
            n.timeout = 0;
        }
        switch (o) {
            case PATROL -> guardOrder = Order.PATROL;
            case FOLLOW -> {
                guardOrder = Order.FOLLOW;
                followId = warden == null ? null : warden.getUuid();
            }
            case STAY -> {
                guardOrder = Order.STAY;
                for (Npc n : ALL.values()) if (n.guard) n.e.getNavigation().stop();
            }
            case RAID -> {
                guardOrder = Order.RAID;
                phase = Phase.STAND;
                phaseStart = now;
                openCells(w, data);
                raidQueue.clear();
                raidFound = 0;
                raidDone = 0;
                for (Npc n : ALL.values()) if (!n.guard) raidQueue.add(n.e.getUuid());
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (data.roleOf(p) == Role.PRISONER) raidQueue.add(p.getUuid());
                }
            }
            case YARD -> {
                guardOrder = Order.PATROL;
                phase = Phase.YARD;
                phaseStart = now;
                data.lockdown = false;
                openCells(w, data);
            }
            case CELLS -> {
                guardOrder = Order.PATROL;
                phase = Phase.CELLS;
                phaseStart = now;
            }
            case LOCKDOWN -> {
                if (data.lockdown) {
                    data.lockdown = false;
                    guardOrder = Order.PATROL;
                } else {
                    data.lockdown = true;
                    guardOrder = Order.LOCKDOWN;
                    phase = Phase.CELLS;
                    phaseStart = now;
                }
                data.markDirty();
            }
            case HEADCOUNT -> {
                guardOrder = Order.PATROL;
                phase = Phase.LINEUP;
                phaseStart = now;
                headcountAt = now + 400;
            }
            case CRUSH -> {
                if (!data.riot) {
                    if (warden != null) warden.sendMessage(Text.literal("There is no riot to crush.").formatted(Formatting.GRAY), true);
                    return;
                }
                crush = true;
                riotEnd = now + 400;
            }
        }
        server.getPlayerManager().broadcast(
                Text.literal("Warden orders: " + o.label).formatted(Formatting.AQUA), false);
    }

    private static void openCells(ServerWorld w, PrisonData data) {
        for (long[] c : data.cells) {
            Doors.setOpen(w, BlockPos.fromLong(c[0]), true, null);
            data.lockedDoors.remove(c[0]);
        }
        for (BlockPos yd : data.yardDoors) {
            Doors.setOpen(w, yd, true, null);
            data.lockedDoors.remove(yd.asLong());
        }
        data.markDirty();
    }

    private static boolean nobodyNear(ServerWorld w, BlockPos p, double r) {
        return w.getEntitiesByClass(LivingEntity.class, new Box(p).expand(r), e -> true).isEmpty();
    }

    private static void lockCells(ServerWorld w, PrisonData data) {
        for (long[] c : data.cells) {
            BlockPos door = BlockPos.fromLong(c[0]);
            if (nobodyNear(w, door, 1.6)) {
                Doors.setOpen(w, door, false, null);
                data.lockedDoors.add(c[0]);
            }
        }
        for (BlockPos yd : data.yardDoors) {
            if (nobodyNear(w, yd, 2.6)) {
                Doors.setOpen(w, yd, false, null);
                data.lockedDoors.add(yd.asLong());
            }
        }
        data.markDirty();
    }

    public static void endRiot(MinecraftServer server, PrisonData data) {
        if (!data.riot) return;
        data.riot = false;
        data.markDirty();
        crush = false;
        riotEnd = -1;
        guardOrder = Order.PATROL;
        phase = Phase.CELLS;
        phaseStart = server.getTicks();
        server.getPlayerManager().broadcast(
                Text.literal("The riot is over. Order is restored.").formatted(Formatting.GREEN, Formatting.BOLD), false);
    }

    // ---------------------------------------------------------------- main tick (every 0.5 s)

    private static void tick(MinecraftServer server) {
        if (ALL.isEmpty() || server.getTicks() % 10 != 0) return;
        PrisonData data = PrisonData.get(server);
        ServerWorld world = server.getOverworld();
        long now = server.getTicks();

        ALL.values().removeIf(n -> n.e.isRemoved() || !n.e.isAlive());
        List<Npc> guards = new ArrayList<>();
        List<Npc> prisoners = new ArrayList<>();
        for (Npc n : ALL.values()) {
            if (n.e.getWorld() != world) continue;
            (n.guard ? guards : prisoners).add(n);
        }
        List<ServerPlayerEntity> prisonerPlayers = new ArrayList<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (data.roleOf(p) == Role.PRISONER && !p.isCreative() && !p.isSpectator() && p.getWorld() == world) {
                prisonerPlayers.add(p);
            }
        }
        wanted.values().removeIf(t -> t < now);

        List<LivingEntity> escapees = new ArrayList<>();
        for (Npc p : prisoners) if (p.escape >= 2) escapees.add(p.e);
        for (ServerPlayerEntity p : prisonerPlayers) {
            if (data.escaped.contains(p.getUuid()) || wanted.containsKey(p.getUuid())) escapees.add(p);
        }

        if (data.riot && guards.isEmpty() && !prisoners.isEmpty()) overrun(server, world, data, prisoners);
        if (data.riot && riotEnd > 0 && now >= riotEnd) endRiot(server, data);
        if (headcountAt > 0 && now >= headcountAt) {
            headcountAt = -1;
            headcount(server, data, prisoners, prisonerPlayers);
        }
        if (phase == Phase.CELLS && now - phaseStart > 200 && now % 40 == 0) lockCells(world, data);
        if (data.gate != null && escapees.isEmpty() && prisoners.stream().noneMatch(p -> p.escape > 0)) {
            Doors.setOpen(world, data.gate, false, null);
        }
        if (guardOrder == Order.RAID && raidQueue.isEmpty() && guards.stream().noneMatch(g -> g.target != null)) {
            finishRaid(server, now);
        }

        int gi = 0;
        for (Npc g : guards) guardTick(server, world, data, g, gi++, prisoners, prisonerPlayers, escapees, now);
        int slot = (int) ((now / 10) % 4);
        int pi = 0;
        for (Npc p : prisoners) {
            int i = pi++;
            // 150+ villagers: calm prisoners think every 2 s (in rotating groups), busy ones every 0.5 s
            if (data.riot || p.escape > 0) prisonerTick(server, world, data, p, i, guards, now, 10);
            else if (p.bucket == slot) prisonerTick(server, world, data, p, i, guards, now, 40);
        }
    }

    private static void finishRaid(MinecraftServer server, long now) {
        server.getPlayerManager().broadcast(Text.literal("Raid complete: " + raidFound + " contraband item(s) found, "
                + raidDone + " people searched.").formatted(Formatting.GOLD), false);
        guardOrder = Order.PATROL;
        phase = Phase.CELLS;
        phaseStart = now;
    }

    private static void headcount(MinecraftServer server, PrisonData data, List<Npc> prisoners, List<ServerPlayerEntity> pps) {
        if (data.yardCenter == null) return;
        Vec3d spot = center(data.yardCenter);
        int total = prisoners.size() + pps.size();
        int present = 0;
        for (Npc p : prisoners) if (p.escape == 0 && p.e.getPos().distanceTo(spot) < 28) present++;
        for (ServerPlayerEntity p : pps) if (p.getPos().distanceTo(spot) < 28) present++;
        boolean ok = present >= total;
        server.getPlayerManager().broadcast(Text.literal("Headcount: " + present + "/" + total + " accounted for"
                + (ok ? "." : " - someone is missing!")).formatted(ok ? Formatting.GREEN : Formatting.RED, Formatting.BOLD), false);
    }

    private static void overrun(MinecraftServer server, ServerWorld world, PrisonData data, List<Npc> prisoners) {
        data.riot = false;
        data.markDirty();
        crush = false;
        riotEnd = -1;
        if (data.gate != null) Doors.setOpen(world, data.gate, true, null);
        for (Npc p : prisoners) {
            p.escape = 3;
            p.escapeTicks = 0;
        }
        server.getPlayerManager().broadcast(Text.literal("The guards have fallen! The prisoners have taken the prison!")
                .formatted(Formatting.DARK_RED, Formatting.BOLD), false);
    }

    // ---------------------------------------------------------------- helpers

    private static Vec3d center(BlockPos p) {
        return Vec3d.ofBottomCenter(p);
    }

    private static void moveTo(Npc n, Vec3d d, double speed) {
        if (n.dest != null && n.dest.squaredDistanceTo(d) < 1.0 && !n.e.getNavigation().isIdle()) return;
        n.dest = d;
        n.e.getNavigation().startMovingTo(d.x, d.y, d.z, speed);
    }

    private static BlockPos homeOf(PrisonData data, Npc p) {
        if (data.cells.isEmpty()) return data.spawn != null ? data.spawn : p.e.getBlockPos();
        int c = p.cell >= 0 && p.cell < data.cells.size() ? p.cell : 0;
        return BlockPos.fromLong(data.cells.get(c)[1]);
    }

    /** Standing spot inside the cell; up to three inmates share a cell. */
    private static Vec3d homeSpot(PrisonData data, Npc p) {
        if (data.cells.isEmpty()) return center(homeOf(data, p));
        int c = p.cell >= 0 && p.cell < data.cells.size() ? p.cell : 0;
        long[] cell = data.cells.get(c);
        BlockPos in = BlockPos.fromLong(cell[1]);
        BlockPos door = BlockPos.fromLong(cell[0]);
        int toward = Integer.signum(door.getZ() - in.getZ());
        int slot = Math.floorMod(p.e.getUuid().hashCode(), 3);
        int dx = slot == 0 ? 0 : (slot == 1 ? -1 : 1);
        int dz = slot == 0 ? 0 : toward;
        return center(in).add(dx, 0, dz);
    }

    private static LivingEntity nearest(LivingEntity from, List<LivingEntity> list, double max) {
        LivingEntity best = null;
        double bd = max * max;
        for (LivingEntity t : list) {
            if (!t.isAlive()) continue;
            double d = from.squaredDistanceTo(t);
            if (d < bd) {
                bd = d;
                best = t;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- guards

    private static void guardTick(MinecraftServer server, ServerWorld world, PrisonData data, Npc g, int idx,
                                  List<Npc> prisoners, List<ServerPlayerEntity> pps, List<LivingEntity> escapees, long now) {
        VillagerEntity e = g.e;
        if (g.cooldown > 0) g.cooldown -= 10;

        // guards open the yard door when they walk up to it
        for (BlockPos yd : data.yardDoors) {
            if (e.squaredDistanceTo(yd.getX() + 0.5, yd.getY(), yd.getZ() + 0.5) < 7.0) {
                Doors.setOpen(world, yd, true, e);
            }
        }

        if (data.riot) {
            List<LivingEntity> foes = new ArrayList<>();
            for (Npc p : prisoners) foes.add(p.e);
            foes.addAll(pps);
            LivingEntity t = nearest(e, foes, 28);
            if (t != null) {
                engage(world, g, t, crush ? 2f : 0f);
                return;
            }
        }

        if (!escapees.isEmpty()) {
            LivingEntity t = nearest(e, escapees, 80);
            if (t != null) {
                if (e.distanceTo(t) < 2.4f) {
                    arrest(world, data, g, t, now);
                } else {
                    moveTo(g, t.getPos(), 1.35);
                    e.getLookControl().lookAt(t);
                    sayLimited(g, "Stop right there!", now);
                }
                return;
            }
        }

        switch (guardOrder) {
            case FOLLOW -> {
                ServerPlayerEntity w = followId == null ? null : server.getPlayerManager().getPlayer(followId);
                if (w != null && w.getWorld() == world) {
                    if (e.distanceTo(w) > 4f) moveTo(g, w.getPos(), 1.25);
                    else e.getNavigation().stop();
                }
            }
            case STAY -> e.getNavigation().stop();
            case LOCKDOWN -> {
                if (!data.posts.isEmpty()) {
                    BlockPos post = data.posts.get(idx % data.posts.size());
                    if (e.getPos().distanceTo(center(post)) > 2.0) moveTo(g, center(post), 1.1);
                    else e.getNavigation().stop();
                }
            }
            case RAID -> raidTick(world, data, g, now);
            default -> patrol(world, data, g, idx, pps, now);
        }
    }

    private static void patrol(ServerWorld world, PrisonData data, Npc g, int idx, List<ServerPlayerEntity> pps, long now) {
        VillagerEntity e = g.e;
        for (ServerPlayerEntity p : pps) {
            if (e.distanceTo(p) < 3f && playerSearchCd.getOrDefault(p.getUuid(), 0L) <= now && RNG.nextInt(100) < 40) {
                playerSearchCd.put(p.getUuid(), now + 2400);
                searchPlayer(world, data, g, p, now);
                return;
            }
        }
        if (g.timeout > 0) {
            g.timeout -= 10;
            return;
        }
        if (data.patrol.isEmpty()) return;
        BlockPos wp = data.patrol.get((g.patrolIdx + idx * 2) % data.patrol.size());
        if (e.getPos().distanceTo(center(wp)) < 2.2) {
            g.patrolIdx++;
            g.timeout = 60 + RNG.nextInt(80);
            e.getNavigation().stop();
        } else {
            moveTo(g, center(wp), 1.0);
        }
    }

    private static void raidTick(ServerWorld world, PrisonData data, Npc g, long now) {
        if (g.target == null) {
            g.target = raidQueue.pollFirst();
            g.timeout = 0;
            if (g.target == null) {
                g.e.getNavigation().stop();
                return;
            }
        }
        Entity t = world.getEntity(g.target);
        if (!(t instanceof LivingEntity le) || !le.isAlive()) {
            g.target = null;
            return;
        }
        if (g.e.distanceTo(le) > 2.4f) {
            moveTo(g, le.getPos(), 1.25);
            g.timeout += 10;
            if (g.timeout > 600) {
                g.target = null;
                raidDone++;
            }
            return;
        }
        if (t instanceof ServerPlayerEntity sp) {
            searchPlayer(world, data, g, sp, now);
        } else if (t instanceof VillagerEntity v) {
            Npc pn = ALL.get(v.getUuid());
            if (pn != null) searchNpc(g, pn, now);
        }
        raidDone++;
        g.target = null;
    }

    private static void searchPlayer(ServerWorld world, PrisonData data, Npc g, ServerPlayerEntity p, long now) {
        int found = Gear.confiscate(p, null);
        raidFound += found > 0 ? 1 : 0;
        g.e.getNavigation().stop();
        g.e.getLookControl().lookAt(p);
        world.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.NEUTRAL, 1f, 0.7f);
        if (found > 0) {
            say(g.e, "Contraband! I'm confiscating this.");
            p.sendMessage(Text.literal("A guard searched you and confiscated " + found + " item(s)!").formatted(Formatting.RED), false);
        } else {
            say(g.e, "Hands up... all clear. Move along.");
            p.sendMessage(Text.literal("A guard searched you. Nothing found.").formatted(Formatting.GRAY), false);
        }
    }

    private static void searchNpc(Npc guard, Npc prisoner, long now) {
        prisoner.e.getNavigation().stop();
        guard.e.getNavigation().stop();
        guard.e.getLookControl().lookAt(prisoner.e);
        if (prisoner.contraband) {
            prisoner.contraband = false;
            prisoner.e.removeCommandTag(T_CONTRA);
            raidFound++;
            say(guard.e, "Found a shiv on " + prisoner.e.getName().getString() + "!");
            prisoner.e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 2));
        } else {
            sayLimited(guard, prisoner.e.getName().getString() + " is clean.", now);
        }
    }

    private static void engage(ServerWorld w, Npc a, LivingEntity t, float dmg) {
        VillagerEntity e = a.e;
        if (e.distanceTo(t) > 2.3f) {
            moveTo(a, t.getPos(), 1.3);
            return;
        }
        e.getNavigation().stop();
        e.getLookControl().lookAt(t);
        if (a.cooldown > 0) return;
        a.cooldown = 20;
        e.swingHand(Hand.MAIN_HAND);
        w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.NEUTRAL, 1f, 1f);
        if (a.guard) {
            t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 2));
            if (dmg > 0 && t.getHealth() > 6f) t.damage(w.getDamageSources().mobAttack(e), dmg);
        } else if (dmg > 0) {
            t.damage(w.getDamageSources().mobAttack(e), dmg);
        }
    }

    private static void arrest(ServerWorld w, PrisonData data, Npc g, LivingEntity t, long now) {
        if (g.cooldown > 0) return;
        g.cooldown = 40;
        t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 3));
        if (t instanceof ServerPlayerEntity p) {
            int found = Gear.confiscate(p, null);
            data.escaped.remove(p.getUuid());
            wanted.remove(p.getUuid());
            if (data.spawn != null) p.requestTeleport(data.spawn.getX() + 0.5, data.spawn.getY(), data.spawn.getZ() + 0.5);
            if (!data.riot) p.removeStatusEffect(StatusEffects.GLOWING);
            p.sendMessage(Text.literal("You were caught and dragged back inside" + (found > 0 ? " - contraband confiscated!" : "."))
                    .formatted(Formatting.RED), false);
            say(g.e, "Caught you! Back to your block.");
        } else if (t instanceof VillagerEntity v) {
            Npc pn = ALL.get(v.getUuid());
            if (pn != null) {
                pn.escape = 0;
                pn.escapeTicks = 0;
                if (pn.contraband) {
                    pn.contraband = false;
                    v.removeCommandTag(T_CONTRA);
                }
                BlockPos home = homeOf(data, pn);
                v.requestTeleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
                v.getNavigation().stop();
            }
            say(g.e, "Back in your cell, inmate!");
        }
    }

    // ---------------------------------------------------------------- prisoners

    private static void prisonerTick(MinecraftServer server, ServerWorld world, PrisonData data, Npc p, int idx,
                                     List<Npc> guards, long now, int dt) {
        VillagerEntity e = p.e;
        if (p.cooldown > 0) p.cooldown -= dt;
        if (p.wander > 0) p.wander -= dt;

        if (data.riot) {
            Npc best = null;
            double bd = 28 * 28;
            for (Npc g : guards) {
                double d = e.squaredDistanceTo(g.e);
                if (d < bd) {
                    bd = d;
                    best = g;
                }
            }
            if (best != null) engage(world, p, best.e, 2f);
            else wanderYard(p, data);
            return;
        }
        if (p.escape > 0) {
            escapeTick(server, world, data, p);
            return;
        }
        switch (phase) {
            case STAND -> e.getNavigation().stop();
            case CELLS -> {
                Vec3d hc = homeSpot(data, p);
                if (e.getPos().distanceTo(hc) > 2.2) {
                    moveTo(p, hc, 0.9);
                } else if (p.wander <= 0) {
                    p.wander = 60 + RNG.nextInt(100);
                    moveTo(p, hc.add((RNG.nextDouble() - 0.5) * 2, 0, (RNG.nextDouble() - 0.5) * 2), 0.5);
                }
            }
            case YARD -> {
                wanderYard(p, data);
                if (!data.lockdown && data.gate != null) {
                    if (!p.contraband && RNG.nextInt(100) < 1) {
                        p.contraband = true;
                        e.addCommandTag(T_CONTRA);
                    }
                    boolean otherEscaper = ALL.values().stream().anyMatch(n -> !n.guard && n.escape > 0);
                    if (p.contraband && !otherEscaper && RNG.nextInt(1000) < 6) {
                        p.escape = 1;
                        p.escapeTicks = 0;
                    }
                }
            }
            case LINEUP -> {
                if (data.yardCenter != null) {
                    Vec3d spot = center(data.yardCenter).add(((idx % 20) - 10) * 1.3, 0, (idx / 20 - 3) * 1.3);
                    if (e.getPos().distanceTo(spot) > 1.5) moveTo(p, spot, 1.0);
                    else e.getNavigation().stop();
                }
            }
        }
    }

    private static void wanderYard(Npc p, PrisonData data) {
        if (p.wander > 0 || data.yardCenter == null) return;
        p.wander = 60 + RNG.nextInt(100);
        Vec3d c = center(data.yardCenter);
        moveTo(p, c.add((RNG.nextDouble() - 0.5) * 2 * data.yardHalfX, 0, (RNG.nextDouble() - 0.5) * 2 * data.yardHalfZ), 0.8);
    }

    private static void escapeTick(MinecraftServer server, ServerWorld world, PrisonData data, Npc p) {
        VillagerEntity e = p.e;
        if (data.gate == null) {
            p.escape = 0;
            return;
        }
        Vec3d gateIn = center(data.gate).add(0, 0, -2);
        Vec3d gateOut = center(data.gate).add(0, 0, 30);
        switch (p.escape) {
            case 1 -> {
                if (e.getPos().distanceTo(gateIn) < 2.5) {
                    p.escape = 2;
                    p.escapeTicks = 0;
                    PrisonEvents.alertStaff(server, data, Text.literal("ALERT! " + e.getName().getString()
                            + " is tampering with the main gate!").formatted(Formatting.RED, Formatting.BOLD));
                } else {
                    moveTo(p, gateIn, 1.1);
                }
            }
            case 2 -> {
                e.getNavigation().stop();
                e.getLookControl().lookAt(center(data.gate));
                p.escapeTicks += 10;
                if (p.escapeTicks >= 300) {
                    Doors.setOpen(world, data.gate, true, e);
                    p.escape = 3;
                    p.escapeTicks = 0;
                    server.getPlayerManager().broadcast(Text.literal(e.getName().getString() + " picked the gate lock and is running!")
                            .formatted(Formatting.RED), false);
                }
            }
            default -> {
                moveTo(p, gateOut, 1.3);
                p.escapeTicks += 10;
                if (p.escapeTicks >= 600 && !data.contains(e.getBlockPos())) {
                    escapedTotal++;
                    server.getPlayerManager().broadcast(Text.literal(e.getName().getString() + " has ESCAPED! ("
                            + escapedTotal + " total)").formatted(Formatting.DARK_RED, Formatting.BOLD), false);
                    e.discard();
                }
            }
        }
    }
}
