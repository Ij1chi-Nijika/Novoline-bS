package keystrokesmod.mixin.impl.render;

import keystrokesmod.module.impl.render.Xray;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockModelRenderer;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.util.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockModelRenderer.class)
public abstract class MixinXrayBlockModelRenderer {
    @Inject(method = "renderModel(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/client/resources/model/IBakedModel;Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockPos;Lnet/minecraft/client/renderer/WorldRenderer;Z)Z", at = @At("HEAD"))
    private void raven$discoverXrayBlock(IBlockAccess access, IBakedModel model, IBlockState state,
                                          BlockPos pos, WorldRenderer renderer, boolean checkSides,
                                          CallbackInfoReturnable<Boolean> callback) {
        Xray.discover(state.getBlock(), pos);
    }
}
