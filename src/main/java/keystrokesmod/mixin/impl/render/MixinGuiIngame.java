package keystrokesmod.mixin.impl.render;

import keystrokesmod.module.impl.render.HUD;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiIngame;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin(GuiIngame.class)
public class MixinGuiIngame {
    private int raven$scoreboardLine;

    @Inject(method = "renderScoreboard", at = @At("HEAD"))
    private void raven$resetScoreboardLine(CallbackInfo ci) {
        raven$scoreboardLine = 0;
    }

    @Redirect(
            method = "renderScoreboard",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/FontRenderer;drawString(Ljava/lang/String;III)I",
                    ordinal = 0
            ),
            require = 1
    )
    private int raven$replaceScoreboardServerIp(FontRenderer fontRenderer, String text, int x, int y, int color) {
        raven$scoreboardLine++;
        if (raven$scoreboardLine == 1 && HUD.shouldReplaceScoreboardServerIp()) {
            return HUD.drawScoreboardServerIp(x, y);
        }
        return fontRenderer.drawString(text, x, y, color);
    }
}
