package keystrokesmod.mixin.impl.world;

import keystrokesmod.module.impl.render.Xray;
import net.minecraft.block.BlockGrass;
import net.minecraft.util.EnumWorldBlockLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockGrass.class)
public abstract class MixinXrayBlockGrass {
    @Inject(method = "getBlockLayer", at = @At("HEAD"), cancellable = true)
    private void raven$getXrayGrassLayer(CallbackInfoReturnable<EnumWorldBlockLayer> callback) {
        if (Xray.isEnabled) callback.setReturnValue(EnumWorldBlockLayer.TRANSLUCENT);
    }
}
