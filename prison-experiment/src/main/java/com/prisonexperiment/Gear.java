package com.prisonexperiment;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Set;

/** Uniforms, kits and contraband rules. */
public final class Gear {
    private Gear() {}

    private static final String TAG = "prison_uniform";
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static final Set<Item> EXTRA_CONTRABAND = Set.of(
            Items.TNT, Items.FLINT_AND_STEEL, Items.FIRE_CHARGE, Items.ENDER_PEARL, Items.LAVA_BUCKET,
            Items.BOW, Items.CROSSBOW, Items.TRIDENT, Items.MACE, Items.SHEARS, Items.ELYTRA,
            Items.GUNPOWDER, Items.CHORUS_FRUIT, Items.ENDER_CHEST
    );

    // ---------- contraband ----------

    public static boolean isContraband(ItemStack s) {
        if (s.isEmpty()) return false;
        return s.isIn(ItemTags.PICKAXES) || s.isIn(ItemTags.SHOVELS) || s.isIn(ItemTags.AXES)
                || s.isIn(ItemTags.SWORDS) || s.isOf(ModItems.BATON) || s.isOf(ModItems.CELL_KEY) || s.isOf(ModItems.WARDEN_RADIO)
                || EXTRA_CONTRABAND.contains(s.getItem());
    }

    // ---------- uniforms ----------

    private static Item itemFor(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> Items.LEATHER_HELMET;
            case CHEST -> Items.LEATHER_CHESTPLATE;
            case LEGS -> Items.LEATHER_LEGGINGS;
            default -> Items.LEATHER_BOOTS;
        };
    }

    public static ItemStack makeUniform(Role role, EquipmentSlot slot) {
        ItemStack s = new ItemStack(itemFor(slot));
        s.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(role.color, false));
        s.set(DataComponentTypes.UNBREAKABLE, new UnbreakableComponent(false));
        s.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal(role.displayName + " Uniform").styled(st -> st.withItalic(false).withColor(role.formatting)));
        NbtCompound tag = new NbtCompound();
        tag.putString(TAG, role.name());
        s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        return s;
    }

    /** Role name stored on a uniform piece, or null if the stack is not a prison uniform. */
    public static String uniformRole(ItemStack s) {
        if (s.isEmpty()) return null;
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        if (c == null) return null;
        NbtCompound n = c.copyNbt();
        return n.contains(TAG) ? n.getString(TAG) : null;
    }

    /** Make sure the player wears exactly the uniform for their role. */
    public static void enforceUniform(ServerPlayerEntity p, Role role) {
        for (EquipmentSlot slot : SLOTS) {
            ItemStack cur = p.getEquippedStack(slot);
            String u = uniformRole(cur);
            if (role.name().equals(u)) continue;
            if (!cur.isEmpty() && u == null) p.getInventory().offerOrDrop(cur.copy());
            p.equipStack(slot, makeUniform(role, slot));
        }
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.main.size(); i++) {
            if (uniformRole(inv.getStack(i)) != null) inv.setStack(i, ItemStack.EMPTY);
        }
    }

    public static void stripUniform(ServerPlayerEntity p) {
        for (EquipmentSlot slot : SLOTS) {
            if (uniformRole(p.getEquippedStack(slot)) != null) p.equipStack(slot, ItemStack.EMPTY);
        }
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.main.size(); i++) {
            if (uniformRole(inv.getStack(i)) != null) inv.setStack(i, ItemStack.EMPTY);
        }
    }

    /** Remove contraband from a prisoner; items go to {@code to} (may be null = destroyed). Returns the item count. */
    public static int confiscate(ServerPlayerEntity from, ServerPlayerEntity to) {
        int found = 0;
        PlayerInventory inv = from.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (isContraband(s)) {
                found += s.getCount();
                if (to != null) to.getInventory().offerOrDrop(s.copy());
                inv.setStack(i, ItemStack.EMPTY);
            }
        }
        return found;
    }

    // ---------- kits ----------

    public static boolean has(ServerPlayerEntity p, Item item) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isOf(item)) return true;
        return false;
    }

    public static void giveKit(ServerPlayerEntity p, Role role) {
        if (!role.isStaff()) return;
        if (!has(p, ModItems.BATON)) p.getInventory().offerOrDrop(new ItemStack(ModItems.BATON));
        if (!has(p, ModItems.CELL_KEY)) p.getInventory().offerOrDrop(new ItemStack(ModItems.CELL_KEY));
        if (role == Role.WARDEN && !has(p, ModItems.WARDEN_RADIO)) p.getInventory().offerOrDrop(new ItemStack(ModItems.WARDEN_RADIO));
    }

    public static void takeKit(ServerPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(ModItems.BATON) || s.isOf(ModItems.CELL_KEY) || s.isOf(ModItems.WARDEN_RADIO)) inv.setStack(i, ItemStack.EMPTY);
        }
    }
}
