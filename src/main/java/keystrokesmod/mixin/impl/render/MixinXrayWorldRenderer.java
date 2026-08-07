package keystrokesmod.mixin.impl.render;

import keystrokesmod.module.impl.render.Xray;
import net.minecraft.client.renderer.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteOrder;
import java.nio.IntBuffer;

@Mixin(WorldRenderer.class)
public abstract class MixinXrayWorldRenderer {
    @Shadow private IntBuffer rawIntBuffer;
    @Shadow private boolean noColor;
    @Shadow public abstract int getColorIndex(int vertexIndex);

    @Inject(method = "putColorMultiplier", at = @At("HEAD"), cancellable = true)
    private void raven$applyXrayOpacity(float red, float green, float blue, int vertexIndex, CallbackInfo callback) {
        if (!Xray.isEnabled) {
            return;
        }

        int index = getColorIndex(vertexIndex);
        int color = -1;
        if (!noColor) {
            color = rawIntBuffer.get(index);
            if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                int r = (int) ((color & 255) * red);
                int g = (int) ((color >> 8 & 255) * green);
                int b = (int) ((color >> 16 & 255) * blue);
                color = Xray.alpha << 24 | b << 16 | g << 8 | r;
            } else {
                int r = (int) ((color >> 24 & 255) * red);
                int g = (int) ((color >> 16 & 255) * green);
                int b = (int) ((color >> 8 & 255) * blue);
                color = r << 24 | g << 16 | b << 8 | Xray.alpha;
            }
        }
        rawIntBuffer.put(index, color);
        callback.cancel();
    }
}
