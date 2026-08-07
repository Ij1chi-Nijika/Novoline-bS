package keystrokesmod.mixin.impl.render;

import keystrokesmod.module.impl.render.Xray;
import net.minecraft.client.renderer.chunk.SetVisibility;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.util.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VisGraph.class)
public abstract class MixinXrayVisGraph {
    @Inject(method = "func_178606_a", at = @At("HEAD"), cancellable = true)
    private void raven$ignoreOpaqueBlock(BlockPos pos, CallbackInfo callback) {
        if (Xray.isEnabled) callback.cancel();
    }

    @Inject(method = "computeVisibility", at = @At("HEAD"), cancellable = true)
    private void raven$makeAllDirectionsVisible(CallbackInfoReturnable<SetVisibility> callback) {
        if (Xray.isEnabled) {
            SetVisibility visibility = new SetVisibility();
            visibility.setAllVisible(true);
            callback.setReturnValue(visibility);
        }
    }
}
