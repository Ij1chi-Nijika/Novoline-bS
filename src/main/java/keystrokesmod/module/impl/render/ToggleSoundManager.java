package keystrokesmod.module.impl.render;

import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.util.ResourceLocation;

/** Plays the bundled Novoline on/off sounds after a module changes state. */
public final class ToggleSoundManager {
    private static final Minecraft MC = Minecraft.getMinecraft();
    private static final ResourceLocation ENABLE_SOUND = new ResourceLocation("keystrokesmod", "novoline.toggle_on");
    private static final ResourceLocation DISABLE_SOUND = new ResourceLocation("keystrokesmod", "novoline.toggle_off");

    private ToggleSoundManager() {
    }

    public static void moduleState(Module module, boolean enabled) {
        if (module == null || module == ModuleManager.hud || module.alwaysOn || module.isHidden()) {
            return;
        }
        if (ModuleManager.hud == null || !ModuleManager.hud.isEnabled()
                || HUD.novolineToggleSound == null || !HUD.novolineToggleSound.isToggled()
                || MC.thePlayer == null || MC.theWorld == null) {
            return;
        }

        MC.getSoundHandler().playSound(PositionedSoundRecord.create(enabled ? ENABLE_SOUND : DISABLE_SOUND, 1.0f));
    }
}
