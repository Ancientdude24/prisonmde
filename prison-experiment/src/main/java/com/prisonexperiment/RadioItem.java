package com.prisonexperiment;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Warden Radio: right-click cycles the order, sneak + right-click transmits it to the guards. */
public class RadioItem extends Item {
    private static final Map<UUID, Integer> SELECTED = new HashMap<>();

    public RadioItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient || !(user instanceof ServerPlayerEntity sp)) return TypedActionResult.success(stack);

        PrisonData data = PrisonData.get(sp.getServer());
        if (data.roleOf(sp) != Role.WARDEN) {
            sp.sendMessage(Text.literal("Only the warden can use the radio.").formatted(Formatting.RED), true);
            return TypedActionResult.fail(stack);
        }
        Npcs.Order[] orders = Npcs.Order.values();
        int i = SELECTED.getOrDefault(sp.getUuid(), 0);
        if (sp.isSneaking()) {
            Npcs.issue(sp.getServer(), orders[i], sp);
            sp.getItemCooldownManager().set(this, 20);
        } else {
            i = (i + 1) % orders.length;
            SELECTED.put(sp.getUuid(), i);
            sp.sendMessage(Text.literal("Radio: " + orders[i].label + "  (sneak + use to transmit)")
                    .formatted(Formatting.AQUA), true);
        }
        return TypedActionResult.success(stack);
    }
}
