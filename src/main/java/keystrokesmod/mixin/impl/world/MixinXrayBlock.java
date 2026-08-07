package keystrokesmod.mixin.impl.world;

import keystrokesmod.module.impl.render.Xray;
import net.minecraft.block.Block;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumWorldBlockLayer;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Block.class)
public abstract class MixinXrayBlock {
    @Inject(method = "getBlockLayer", at = @At("HEAD"), cancellable = true)
    private void raven$getXrayLayer(CallbackInfoReturnable<EnumWorldBlockLayer> callback) {
        if (Xray.isEnabled) {
            Block self = (Block) (Object) this;
            callback.setReturnValue(Xray.blockIdList.contains(Block.getIdFromBlock(self))
                    ? EnumWorldBlockLayer.SOLID : EnumWorldBlockLayer.TRANSLUCENT);
        }
    }

    @Inject(method = "shouldSideBeRendered", at = @At("HEAD"), cancellable = true)
    private void raven$renderXrayBlockSides(IBlockAccess world, BlockPos pos, EnumFacing side,
                                             CallbackInfoReturnable<Boolean> callback) {
        Block self = (Block) (Object) this;
        if (Xray.isEnabled && Xray.blockIdList.contains(Block.getIdFromBlock(self))) {
            callback.setReturnValue(true);
        }
    }
}
