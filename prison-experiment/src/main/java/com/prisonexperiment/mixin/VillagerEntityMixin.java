package com.prisonexperiment.mixin;

import com.prisonexperiment.Npcs;
import net.minecraft.entity.passive.VillagerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prison NPCs are driven by the mod's own AI, so the vanilla villager brain is switched off for them. */
@Mixin(VillagerEntity.class)
public abstract class VillagerEntityMixin {
    @Inject(method = "mobTick", at = @At("HEAD"), cancellable = true)
    private void prisonexperiment$skipBrain(CallbackInfo ci) {
        VillagerEntity self = (VillagerEntity) (Object) this;
        if (self.getCommandTags().contains(Npcs.TAG)) {
            ci.cancel();
        }
    }
}
