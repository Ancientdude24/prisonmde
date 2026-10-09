package com.prisonexperiment;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class PrisonCommands {
    private PrisonCommands() {}

    // ---------- permission checks ----------

    private static Role roleOf(ServerCommandSource src) {
        ServerPlayerEntity p = src.getPlayer();
        return p == null ? null : PrisonData.get(src.getServer()).roleOf(p);
    }

    /** Ops (cheats) or the warden. */
    private static boolean isAuthority(ServerCommandSource src) {
        return src.hasPermissionLevel(2) || roleOf(src) == Role.WARDEN;
    }

    /** Ops, warden or guards. */
    private static boolean isStaff(ServerCommandSource src) {
        if (src.hasPermissionLevel(2)) return true;
        Role r = roleOf(src);
        return r != null && r.isStaff();
    }

    private static boolean canStartRiot(ServerCommandSource src) {
        return isAuthority(src) || roleOf(src) == Role.PRISONER;
    }

    private static void say(CommandContext<ServerCommandSource> ctx, String msg, Formatting color) {
        ctx.getSource().sendFeedback(() -> Text.literal(msg).formatted(color), false);
    }

    // ---------- registration ----------

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var roleArg = argument("player", EntityArgumentType.player());
            for (Role r : Role.values()) {
                roleArg.then(literal(r.id).executes(ctx -> setRole(ctx, r)));
            }
            roleArg.then(literal("none").executes(ctx -> setRole(ctx, null)));

            var orderNode = literal("order").requires(PrisonCommands::isAuthority);
            for (Npcs.Order o : Npcs.Order.values()) {
                orderNode.then(literal(o.id()).executes(ctx -> {
                    Npcs.issue(ctx.getSource().getServer(), o, ctx.getSource().getPlayer());
                    return Command.SINGLE_SUCCESS;
                }));
            }

            dispatcher.register(literal("prison")
                    .then(literal("claimwarden").executes(PrisonCommands::claimWarden))
                    .then(literal("play")
                            .then(literal("warden").executes(PrisonCommands::claimWarden))
                            .then(literal("guard").executes(ctx -> selfRole(ctx, Role.GUARD)))
                            .then(literal("prisoner").executes(ctx -> selfRole(ctx, Role.PRISONER))))
                    .then(literal("role").requires(PrisonCommands::isAuthority).then(roleArg))
                    .then(literal("build").requires(PrisonCommands::isAuthority)
                            .executes(ctx -> build(ctx, 4, 7))
                            .then(literal("small").executes(ctx -> build(ctx, 1, 4)))
                            .then(literal("medium").executes(ctx -> build(ctx, 2, 6)))
                            .then(literal("large").executes(ctx -> build(ctx, 4, 7))))
                    .then(literal("populate").requires(PrisonCommands::isAuthority)
                            .executes(ctx -> populate(ctx, -1, -1))
                            .then(argument("guards", IntegerArgumentType.integer(0, 60))
                                    .then(argument("prisoners", IntegerArgumentType.integer(0, 250))
                                            .executes(ctx -> populate(ctx, IntegerArgumentType.getInteger(ctx, "guards"),
                                                    IntegerArgumentType.getInteger(ctx, "prisoners"))))))
                    .then(literal("npc").requires(PrisonCommands::isAuthority)
                            .then(literal("spawn")
                                    .then(literal("guard").executes(ctx -> spawnHere(ctx, true))
                                            .then(argument("count", IntegerArgumentType.integer(1, 20))
                                                    .executes(ctx -> spawnHere(ctx, true))))
                                    .then(literal("prisoner").executes(ctx -> spawnHere(ctx, false))
                                            .then(argument("count", IntegerArgumentType.integer(1, 20))
                                                    .executes(ctx -> spawnHere(ctx, false)))))
                            .then(literal("clear").executes(PrisonCommands::clearNpcs)))
                    .then(orderNode)
                    .then(literal("zone").requires(PrisonCommands::isAuthority)
                            .then(literal("pos1").executes(ctx -> setCorner(ctx, true)))
                            .then(literal("pos2").executes(ctx -> setCorner(ctx, false)))
                            .then(literal("clear").executes(PrisonCommands::clearZone)))
                    .then(literal("setspawn").requires(PrisonCommands::isAuthority).executes(PrisonCommands::setSpawn))
                    .then(literal("arrest").requires(PrisonCommands::isStaff)
                            .then(argument("player", EntityArgumentType.player()).executes(PrisonCommands::arrest)))
                    .then(literal("lockdown").requires(PrisonCommands::isStaff)
                            .then(argument("enabled", BoolArgumentType.bool()).executes(PrisonCommands::lockdown)))
                    .then(literal("riot").requires(PrisonCommands::canStartRiot)
                            .then(literal("start").executes(ctx -> riot(ctx, true)))
                            .then(literal("stop").requires(PrisonCommands::isAuthority).executes(ctx -> riot(ctx, false))))
                    .then(literal("give").requires(PrisonCommands::isAuthority)
                            .then(literal("baton").executes(ctx -> give(ctx, ModItems.BATON)))
                            .then(literal("key").executes(ctx -> give(ctx, ModItems.CELL_KEY)))
                            .then(literal("radio").executes(ctx -> give(ctx, ModItems.WARDEN_RADIO))))
                    .then(literal("status").requires(PrisonCommands::isStaff).executes(PrisonCommands::status)));
        });
    }

    // ---------- roles ----------

    private static int claimWarden(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        PrisonData data = PrisonData.get(ctx.getSource().getServer());
        if (data.roleOf(p) == Role.WARDEN) {
            say(ctx, "You are already the Warden.", Formatting.YELLOW);
            return Command.SINGLE_SUCCESS;
        }
        if (data.roles.containsValue(Role.WARDEN)) {
            say(ctx, "There is already a warden. Ask them (or an op) to change roles.", Formatting.RED);
            return 0;
        }
        applyRole(p, data, Role.WARDEN);
        say(ctx, "You are now the Warden. Use the Warden Radio (right-click: pick order, sneak+right-click: transmit).", Formatting.GREEN);
        return Command.SINGLE_SUCCESS;
    }

    private static int selfRole(CommandContext<ServerCommandSource> ctx, Role role) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        PrisonData data = PrisonData.get(ctx.getSource().getServer());
        applyRole(p, data, role);
        say(ctx, "You now play as: " + role.displayName, Formatting.GREEN);
        return Command.SINGLE_SUCCESS;
    }

    private static int setRole(CommandContext<ServerCommandSource> ctx, Role role) throws CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
        PrisonData data = PrisonData.get(ctx.getSource().getServer());
        applyRole(target, data, role);
        say(ctx, target.getName().getString() + " is now: " + (role == null ? "free (no role)" : role.displayName), Formatting.GREEN);
        if (role != null) {
            target.sendMessage(Text.literal("You are now a " + role.displayName + "!").formatted(role.formatting, Formatting.BOLD), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void applyRole(ServerPlayerEntity p, PrisonData data, Role role) {
        if (data.roleOf(p) != null) {
            Gear.stripUniform(p);
            Gear.takeKit(p);
        }
        data.escaped.remove(p.getUuid());
        p.removeStatusEffect(StatusEffects.GLOWING);
        if (role == null) {
            data.roles.remove(p.getUuid());
        } else {
            data.roles.put(p.getUuid(), role);
            Gear.enforceUniform(p, role);
            Gear.giveKit(p, role);
            if (role == Role.PRISONER && data.spawn != null) {
                p.requestTeleport(data.spawn.getX() + 0.5, data.spawn.getY(), data.spawn.getZ() + 0.5);
            }
        }
        data.markDirty();
    }

    // ---------- building / NPCs ----------

    private static int build(CommandContext<ServerCommandSource> ctx, int blocks, int cols) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        PrisonData data = PrisonData.get(ctx.getSource().getServer());
        BlockPos feet = p.getBlockPos();
        BlockPos origin = new BlockPos(feet.getX(), feet.getY() - 1, feet.getZ());
        ServerWorld world = p.getServerWorld();
        Npcs.clearAll();
        boolean started = PrisonBuilder.build(world, origin, data, blocks, cols, () -> {
            for (ServerPlayerEntity pl : world.getServer().getPlayerManager().getPlayerList()) {
                pl.sendMessage(Text.literal("Prison construction finished. Next: /prison populate").formatted(Formatting.GREEN, Formatting.BOLD), false);
            }
            p.requestTeleport(origin.getX() + 10.5, origin.getY() + 1, origin.getZ() + 2.5);
        });
        if (!started) {
            say(ctx, "A build is already running (" + BuildQueue.progressPercent() + "%).", Formatting.RED);
            return 0;
        }
        say(ctx, "Building: " + PrisonBuilder.describe(blocks, cols) + " towards +X / +Z. This takes a few seconds - stay close "
                + "so the chunks are loaded.", Formatting.YELLOW);
        return Command.SINGLE_SUCCESS;
    }

    private static int populate(CommandContext<ServerCommandSource> ctx, int guards, int prisoners) {
        MinecraftServer server = ctx.getSource().getServer();
        PrisonData data = PrisonData.get(server);
        if (BuildQueue.busy()) {
            say(ctx, "The prison is still being built (" + BuildQueue.progressPercent() + "%).", Formatting.RED);
            return 0;
        }
        if (data.cells.isEmpty() || data.patrol.isEmpty()) {
            say(ctx, "Build the prison first with /prison build.", Formatting.RED);
            return 0;
        }
        int pr = prisoners >= 0 ? prisoners : Math.min(data.cells.size() * 3, 140);
        int gd = guards >= 0 ? guards : Math.max(4, Math.min(24, pr / 6));
        ServerWorld w = server.getOverworld();
        // guards start outside the cell blocks (yard / lanes), prisoners in their cells
        java.util.List<BlockPos> outside = new java.util.ArrayList<>();
        for (BlockPos pp : data.patrol) {
            if (data.yardCenter != null && pp.getX() >= data.yardCenter.getX() - data.yardHalfX - 4) outside.add(pp);
        }
        if (outside.isEmpty()) outside.addAll(data.patrol);
        for (int i = 0; i < pr; i++) {
            final int cell = i % data.cells.size();
            BuildQueue.later(() -> Npcs.spawn(w, Vec3d.ofBottomCenter(BlockPos.fromLong(data.cells.get(cell)[1])), false, cell));
        }
        for (int i = 0; i < gd; i++) {
            final BlockPos at = outside.get(i % outside.size());
            BuildQueue.later(() -> Npcs.spawn(w, Vec3d.ofBottomCenter(at), true, -1));
        }
        say(ctx, "Spawning " + gd + " guard(s) and " + pr + " prisoner(s) over the next few seconds...", Formatting.GREEN);
        if (pr + gd > 120) {
            say(ctx, "Tip: that is a lot of villagers - lower render/simulation distance if the game stutters.", Formatting.GRAY);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int spawnHere(CommandContext<ServerCommandSource> ctx, boolean guard) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        PrisonData data = PrisonData.get(ctx.getSource().getServer());
        int count = 1;
        try {
            count = IntegerArgumentType.getInteger(ctx, "count");
        } catch (IllegalArgumentException ignored) {
        }
        for (int i = 0; i < count; i++) {
            int cell = data.cells.isEmpty() ? 0 : i % data.cells.size();
            Npcs.spawn(p.getServerWorld(), p.getPos(), guard, cell);
        }
        say(ctx, "Spawned " + count + " " + (guard ? "guard(s)" : "prisoner(s)") + ".", Formatting.GREEN);
        return Command.SINGLE_SUCCESS;
    }

    private static int clearNpcs(CommandContext<ServerCommandSource> ctx) {
        say(ctx, "Removed " + Npcs.clearAll() + " NPC(s).", Formatting.YELLOW);
        return Command.SINGLE_SUCCESS;
    }

    // ---------- zone / spawn ----------

    private static int setCorner(CommandContext<ServerCommandSource> ctx, boolean first) throws CommandSyntaxException {
        BlockPos pos = ctx.getSource().getPlayerOrThrow().getBlockPos();
        PrisonData data = PrisonData.get(ctx.getSource().getServer());
        if (first) data.pos1 = pos; else data.pos2 = pos;
        data.markDirty();
        say(ctx, "Prison zone corner " + (first ? "1" : "2") + " set to " + pos.toShortString()
                + (data.hasZone() ? " - zone is active." : ""), Formatting.GREEN);
        return Command.SINGLE_SUCCESS;
    }

    private static int clearZone(CommandContext<ServerCommandSource> ctx) {
        PrisonData data = PrisonData.get(ctx.getSource().getServer());
        data.pos1 = null;
        data.pos2 = null;
        data.escaped.clear();
        data.markDirty();
        say(ctx, "Prison zone cleared (escape detection off).", Formatting.YELLOW);
        return Command.SINGLE_SUCCESS;
    }

    private static int setSpawn(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        BlockPos pos = ctx.getSource().getPlayerOrThrow().getBlockPos();
        PrisonData data = PrisonData.get(ctx.getSource().getServer());
        data.spawn = pos;
        data.markDirty();
        say(ctx, "Prisoner spawn set to " + pos.toShortString(), Formatting.GREEN);
        return Command.SINGLE_SUCCESS;
    }

    // ---------- control ----------

    private static int arrest(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
        PrisonData data = PrisonData.get(ctx.getSource().getServer());
        if (data.roleOf(target) != Role.PRISONER) {
            say(ctx, target.getName().getString() + " is not a prisoner.", Formatting.RED);
            return 0;
        }
        if (data.spawn == null) {
            say(ctx, "Set a prisoner spawn first with /prison setspawn.", Formatting.RED);
            return 0;
        }
        target.requestTeleport(data.spawn.getX() + 0.5, data.spawn.getY(), data.spawn.getZ() + 0.5);
        data.escaped.remove(target.getUuid());
        Npcs.wanted.remove(target.getUuid());
        if (!data.riot) target.removeStatusEffect(StatusEffects.GLOWING);
        target.sendMessage(Text.literal("You were arrested and returned to your cell block.").formatted(Formatting.RED), false);
        say(ctx, "Arrested " + target.getName().getString() + ".", Formatting.GREEN);
        return Command.SINGLE_SUCCESS;
    }

    private static int lockdown(CommandContext<ServerCommandSource> ctx) {
        boolean on = BoolArgumentType.getBool(ctx, "enabled");
        MinecraftServer server = ctx.getSource().getServer();
        PrisonData data = PrisonData.get(server);
        data.lockdown = on;
        data.markDirty();
        if (on) {
            ServerWorld w = server.getOverworld();
            for (long l : data.cellDoors) Doors.setOpen(w, BlockPos.fromLong(l), false, null);
            Npcs.guardOrder = Npcs.Order.LOCKDOWN;
            Npcs.phase = Npcs.Phase.CELLS;
            Npcs.phaseStart = server.getTicks();
        } else {
            Npcs.guardOrder = Npcs.Order.PATROL;
        }
        server.getPlayerManager().broadcast(
                Text.literal(on ? "LOCKDOWN! All cell doors are sealed. Prisoners return to your cells!"
                                : "Lockdown lifted.")
                        .formatted(on ? Formatting.RED : Formatting.GREEN, Formatting.BOLD), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int riot(CommandContext<ServerCommandSource> ctx, boolean start) {
        MinecraftServer server = ctx.getSource().getServer();
        PrisonData data = PrisonData.get(server);
        if (start) {
            PrisonEvents.startRiot(server, data);
        } else {
            Npcs.endRiot(server, data);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int give(CommandContext<ServerCommandSource> ctx, net.minecraft.item.Item item) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        p.getInventory().offerOrDrop(new ItemStack(item));
        return Command.SINGLE_SUCCESS;
    }

    private static int status(CommandContext<ServerCommandSource> ctx) {
        PrisonData d = PrisonData.get(ctx.getSource().getServer());
        long prisoners = d.roles.values().stream().filter(r -> r == Role.PRISONER).count();
        long guards = d.roles.values().stream().filter(r -> r == Role.GUARD).count();
        long wardens = d.roles.values().stream().filter(r -> r == Role.WARDEN).count();
        long npcG = Npcs.ALL.values().stream().filter(n -> n.guard).count();
        long npcP = Npcs.ALL.values().stream().filter(n -> !n.guard).count();
        say(ctx, "Players - prisoners: " + prisoners + " | guards: " + guards + " | wardens: " + wardens, Formatting.AQUA);
        say(ctx, "NPCs - guards: " + npcG + " | prisoners: " + npcP + " | escaped so far: " + Npcs.escapedTotal, Formatting.AQUA);
        say(ctx, "Cell doors: " + d.cellDoors.size() + " (" + d.lockedDoors.size() + " locked)"
                + " | Zone: " + (d.hasZone() ? "set" : "not set")
                + " | Spawn: " + (d.spawn != null ? d.spawn.toShortString() : "not set"), Formatting.AQUA);
        say(ctx, "Guard order: " + Npcs.guardOrder.label + " | Prisoner phase: " + Npcs.phase
                + " | Lockdown: " + (d.lockdown ? "ON" : "off") + " | Riot: " + (d.riot ? "ON" : "off"), Formatting.AQUA);
        return Command.SINGLE_SUCCESS;
    }
}
