package keystrokesmod.mixin.impl.render;

import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.other.IRC;
import keystrokesmod.module.impl.other.NameHider;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@SideOnly(Side.CLIENT)
@Mixin(GuiPlayerTabOverlay.class)
public class MixinGuiPlayerTabOverlay {
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void raven$decorateTabName(NetworkPlayerInfo networkPlayerInfoIn, CallbackInfoReturnable<String> cir) {
        String name = cir.getReturnValue();
        if (ModuleManager.nameHider != null && ModuleManager.nameHider.isEnabled()) {
            name = NameHider.getTabName(networkPlayerInfoIn, name);
        }

        if (ModuleManager.irc != null && ModuleManager.irc.isEnabled()) {
            name = IRC.getTabName(networkPlayerInfoIn, name);
        }
        cir.setReturnValue(name);
    }
}
