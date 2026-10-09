package com.prisonexperiment;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModItems {
    private ModItems() {}

    public static final Item BATON = register("baton", new Item(new Item.Settings().maxCount(1)));
    public static final Item WARDEN_RADIO = register("warden_radio", new RadioItem(new Item.Settings().maxCount(1)));
    public static final Item CELL_KEY = register("cell_key", new Item(new Item.Settings().maxCount(1)));

    private static Item register(String id, Item item) {
        return Registry.register(Registries.ITEM, Identifier.of(PrisonExperiment.MOD_ID, id), item);
    }

    public static void init() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(BATON);
            entries.add(CELL_KEY);
            entries.add(WARDEN_RADIO);
        });
    }
}
