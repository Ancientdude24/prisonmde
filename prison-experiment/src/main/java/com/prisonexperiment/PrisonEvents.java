package com.prisonexperiment;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class PrisonEvents {
    private PrisonEvents() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(PrisonEvents::tick);
        UseBlockCallback.EVENT.register(PrisonEvents::onUseBlock);
        UseEntityCallback.EVENT.register(PrisonEvents::onUseEntity);
        AttackEntityCallback.EVENT.register(PrisonEvents::onAttack);
        PlayerBlockBreakEvents.BEFORE.register(PrisonEvents::onBreak);
        ServerLivingEntityEvents.AFTER_DEATH.register(PrisonEvents::onDeath);
        ServerPlayerEvents.AFTER_RESPAWN.register(PrisonEvents::onRespawn);
    }

    // ---------------------------------------------------------------- helpers

    private static void actionBar(ServerPlayerEntity p, String msg, Formatting color) {
        p.sendMessage(Text.literal(msg).formatted(color), true);
    }

    public static void alertStaff(MinecraftServer server, PrisonData data, Text text) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            Role r = data.roleOf(p);
            if (r != null && r.isStaff()) p.sendMessage(text, false);
        }
    }

    public static void startRiot(MinecraftServer server, PrisonData data) {
        data.riot = true;
        data.markDirty();
        Npcs.crush = false;
        Npcs.riotEnd = -1;
        server.getPlayerManager().broadcast(
                Text.literal("RIOT! The prisoners have broken out of control!").formatted(Formatting.DARK_RED, Formatting.BOLD), false);
    }

    // ---------------------------------------------------------------- tick (once per second)

    private static void tick(MinecraftServer server) {
        if (server.getTicks() % 20 != 0) return;
        PrisonData data = PrisonData.get(server);
        if (data.roles.isEmpty()) return;

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            Role role = data.roleOf(p);
            if (role == null || p.isCreative() || p.isSpectator()) continue;

            Gear.enforceUniform(p, role);

            if (role == Role.PRISONER) {
                boolean inside = data.isInside(p);
                if (!inside && data.escaped.add(p.getUuid())) {
                    alertStaff(server, data, Text.literal("ESCAPE! " + p.getName().getString()
                            + " left the prison zone at " + p.getBlockPos().toShortString())
                            .formatted(Formatting.RED, Formatting.BOLD));
                    actionBar(p, "You are outside the prison - guards have been alerted!", Formatting.RED);
                } else if (inside && data.escaped.remove(p.getUuid())) {
                    actionBar(p, "Back inside the prison.", Formatting.YELLOW);
                    if (!data.riot) p.removeStatusEffect(StatusEffects.GLOWING);
                }
                if (data.escaped.contains(p.getUuid()) || data.riot) {
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 60, 0, false, false));
                }
                if (data.lockdown) {
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1, false, false));
                }
            }

            if (data.riot) {
                actionBar(p, "RIOT IN PROGRESS", Formatting.DARK_RED);
            } else if (data.lockdown) {
                actionBar(p, role == Role.PRISONER ? "LOCKDOWN - return to your cell!" : "LOCKDOWN ACTIVE", Formatting.RED);
            }
        }
    }

    // ---------------------------------------------------------------- doors / placing

    private static ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
        if (world.isClient || hand != Hand.MAIN_HAND || player.isSpectator()) return ActionResult.PASS;
        ServerPlayerEntity sp = (ServerPlayerEntity) player;
        PrisonData data = PrisonData.get(sp.getServer());
        Role role = data.roleOf(sp);
        BlockPos pos = hit.getBlockPos();
        BlockState state = world.getBlockState(pos);
        ItemStack held = sp.getMainHandStack();

        if (state.getBlock() instanceof DoorBlock) {
            BlockPos lower = Doors.lower(state, pos);
            long key = lower.asLong();
            boolean registered = data.cellDoors.contains(key);

            if (held.isOf(ModItems.CELL_KEY)) {
                if (sp.isSneaking()) {
                    // sneak + key = register / lock / unlock (staff only)
                    if (role == null || !role.isStaff()) {
                        actionBar(sp, "Only guards and the warden can lock cells.", Formatting.RED);
                        return ActionResult.FAIL;
                    }
                    if (!registered) {
                        data.cellDoors.add(key);
                        data.lockedDoors.add(key);
                        Doors.setOpen(world, lower, false, sp);
                        actionBar(sp, "Cell door registered and LOCKED.", Formatting.GREEN);
                    } else if (data.lockedDoors.remove(key)) {
                        actionBar(sp, "Cell door UNLOCKED.", Formatting.YELLOW);
                    } else {
                        data.lockedDoors.add(key);
                        Doors.setOpen(world, lower, false, sp);
                        actionBar(sp, "Cell door LOCKED.", Formatting.GREEN);
                    }
                    data.markDirty();
                    return ActionResult.SUCCESS;
                }
                // plain right click with a key opens/closes the door, locked or not
                if (registered) {
                    Doors.toggle(world, lower, sp);
                    return ActionResult.SUCCESS;
                }
                return ActionResult.PASS;
            }

            if (registered) {
                boolean staff = role != null && role.isStaff();
                if (data.isLocked(key) && !staff) {
                    actionBar(sp, "This door is locked.", Formatting.RED);
                    world.playSound(null, pos, SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundCategory.BLOCKS, 0.6f, 1.6f);
                    return ActionResult.FAIL;
                }
                Doors.toggle(world, lower, sp);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        }

        // prisoners may not place blocks (unless rioting), but can still use chests, tables, etc.
        if (role == Role.PRISONER && !data.riot && !sp.isCreative() && held.getItem() instanceof BlockItem
                && state.createScreenHandlerFactory(world, pos) == null) {
            actionBar(sp, "Prisoners can't build.", Formatting.RED);
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }

    private static boolean onBreak(World world, PlayerEntity player, BlockPos pos, BlockState state, BlockEntity be) {
        if (world.isClient || !(player instanceof ServerPlayerEntity sp)) return true;
        PrisonData data = PrisonData.get(sp.getServer());
        if (data.roleOf(sp) == Role.PRISONER && !data.riot && !sp.isCreative()) {
            actionBar(sp, "Prisoners can't break blocks.", Formatting.RED);
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- baton: stun + search

    private static ActionResult onAttack(PlayerEntity player, World world, Hand hand, Entity target, EntityHitResult hit) {
        if (world.isClient || hand != Hand.MAIN_HAND) return ActionResult.PASS;
        if (target instanceof VillagerEntity gv && gv.getCommandTags().contains(Npcs.T_GUARD)
                && player instanceof ServerPlayerEntity ap) {
            PrisonData d = PrisonData.get(ap.getServer());
            if (d.roleOf(ap) == Role.PRISONER) {
                Npcs.wanted.put(ap.getUuid(), (long) ap.getServer().getTicks() + 1200L);
                Npcs.say(gv, "Assaulting a guard! Get down on the ground!");
            }
        }
        if (!player.getMainHandStack().isOf(ModItems.BATON)) return ActionResult.PASS;
        if (!(target instanceof LivingEntity living)) return ActionResult.PASS;
        if (player.getItemCooldownManager().isCoolingDown(ModItems.BATON)) return ActionResult.FAIL;

        living.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 80, 3));
        living.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 160, 1));
        world.playSound(null, target.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 1f, 0.8f);
        player.getItemCooldownManager().set(ModItems.BATON, 20);
        if (living instanceof ServerPlayerEntity tp) {
            actionBar(tp, "You were stunned by a guard!", Formatting.RED);
        }
        return ActionResult.SUCCESS; // cancels normal damage
    }

    private static ActionResult onUseEntity(PlayerEntity player, World world, Hand hand, Entity entity, EntityHitResult hit) {
        if (world.isClient) return ActionResult.PASS;
        if (Npcs.isNpc(entity) && entity instanceof VillagerEntity npc && player instanceof ServerPlayerEntity sp0) {
            if (hand != Hand.MAIN_HAND) return ActionResult.FAIL;
            if (player.getMainHandStack().isOf(ModItems.BATON)) return Npcs.batonUse(sp0, npc);
            Npcs.talk(sp0, npc);
            return ActionResult.FAIL; // no trading GUI
        }
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
        if (!player.getMainHandStack().isOf(ModItems.BATON)) return ActionResult.PASS;
        if (!(player instanceof ServerPlayerEntity guard) || !(entity instanceof ServerPlayerEntity target)) return ActionResult.PASS;

        PrisonData data = PrisonData.get(guard.getServer());
        Role gr = data.roleOf(guard);
        if (gr == null || !gr.isStaff() || data.roleOf(target) != Role.PRISONER) return ActionResult.PASS;
        if (guard.getItemCooldownManager().isCoolingDown(ModItems.BATON)) return ActionResult.FAIL;
        guard.getItemCooldownManager().set(ModItems.BATON, 40);

        int found = Gear.confiscate(target, guard);
        String name = target.getName().getString();
        world.playSound(null, target.getBlockPos(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 1f, 0.7f);
        if (found > 0) {
            guard.sendMessage(Text.literal("Searched " + name + ": confiscated " + found + " contraband item(s).")
                    .formatted(Formatting.GREEN), false);
            target.sendMessage(Text.literal("You were searched. Contraband confiscated!").formatted(Formatting.RED), false);
            alertStaff(guard.getServer(), data, Text.literal("Contraband found on " + name + " (" + found + " items).")
                    .formatted(Formatting.GOLD));
        } else {
            guard.sendMessage(Text.literal(name + " is clean.").formatted(Formatting.GRAY), false);
            target.sendMessage(Text.literal("You were searched. Nothing found.").formatted(Formatting.GRAY), false);
        }
        return ActionResult.SUCCESS;
    }

    // ---------------------------------------------------------------- riot trigger / respawn

    private static void onDeath(LivingEntity entity, DamageSource source) {
        if (!(entity.getWorld() instanceof ServerWorld sw)) return;
        MinecraftServer server = sw.getServer();
        PrisonData data = PrisonData.get(server);
        if (data.riot) return;
        boolean victimStaff = (entity instanceof ServerPlayerEntity vp && data.roleOf(vp) != null && data.roleOf(vp).isStaff())
                || (entity instanceof VillagerEntity vv && vv.getCommandTags().contains(Npcs.T_GUARD));
        if (!victimStaff) return;
        Entity a = source.getAttacker();
        boolean prisonerKiller = (a instanceof ServerPlayerEntity ap && data.roleOf(ap) == Role.PRISONER)
                || (a instanceof VillagerEntity av && av.getCommandTags().contains(Npcs.T_PRISONER));
        if (prisonerKiller) startRiot(server, data);
    }

    private static void onRespawn(ServerPlayerEntity oldPlayer, ServerPlayerEntity newPlayer, boolean alive) {
        PrisonData data = PrisonData.get(newPlayer.getServer());
        Role role = data.roleOf(newPlayer);
        if (role == null) return;
        Gear.enforceUniform(newPlayer, role);
        Gear.giveKit(newPlayer, role);
        if (role == Role.PRISONER && data.spawn != null) {
            newPlayer.requestTeleport(data.spawn.getX() + 0.5, data.spawn.getY(), data.spawn.getZ() + 0.5);
        }
    }
}
