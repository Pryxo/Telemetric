package dev.telemetric.mixin;

import dev.telemetric.game.EntityVisualsAdapter;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the client's equipment asset manager (it has no public getter) for Hide Armor → Show True Items. */
@Mixin(EquipmentAssetManager.class)
public abstract class EquipmentAssetManagerMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void telemetric$capture(CallbackInfo ci) {
        EntityVisualsAdapter.setEquipmentAssets((EquipmentAssetManager) (Object) this);
    }
}
