package com.prisonexperiment;

import net.fabricmc.api.ModInitializer;

public class PrisonExperiment implements ModInitializer {
    public static final String MOD_ID = "prisonexperiment";

    @Override
    public void onInitialize() {
        ModItems.init();
        PrisonEvents.register();
        Npcs.register();
        BuildQueue.register();
        PrisonCommands.register();
    }
}
