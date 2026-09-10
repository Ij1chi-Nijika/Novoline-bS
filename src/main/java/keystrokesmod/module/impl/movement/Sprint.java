package keystrokesmod.module.impl.movement;

import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.event.PreMotionEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.utility.Utils;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.KeyBinding;

public class Sprint extends Module {

    private final ButtonSetting allowUsingItem;
    private final ButtonSetting allowBackwards;
    private final ButtonSetting allowSideways;
    private final ButtonSetting allowInInventory;

    public Sprint() {
        super("Sprint", category.movement, 0);
        this.registerSetting(new DescriptionSetting("Allow while"));
        this.registerSetting(allowUsingItem = new ButtonSetting("Using item", false));
        this.registerSetting(allowBackwards = new ButtonSetting("Backwards", false));
        this.registerSetting(allowSideways = new ButtonSetting("Sideways", false));
        this.registerSetting(allowInInventory = new ButtonSetting("In inventory", false));
        this.closetModule = true;
    }

    @Override
    public void onDisable() {
        if (Utils.nullCheck()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
            if (usesFluxKeepSprint()) mc.thePlayer.setSprinting(false);
        }
    }

    @Override
    public void onUpdate() {
        if (!Utils.nullCheck() || usesFluxKeepSprint()) return;
        boolean inGame = mc.inGameHasFocus;
        boolean inInv = allowInInventory.isToggled() && (mc.currentScreen instanceof GuiInventory || mc.currentScreen instanceof GuiChest);
        if (!inGame && !inInv) {
            return;
        }
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), true);
    }

    private boolean usesFluxKeepSprint() {
        return ModuleManager.keepSprint != null && ModuleManager.keepSprint.isEnabled();
    }

    @SubscribeEvent
    public void onFluxMotion(PreMotionEvent event) {
        if (!usesFluxKeepSprint() || !Utils.nullCheck()) return;
        // Flux Sprint updates the key during Motion, not the earlier client tick.
        if (mc.thePlayer.getFoodStats().getFoodLevel() > 6
                && mc.thePlayer.movementInput.moveForward > 0 && !mc.thePlayer.isCollidedHorizontally) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), true);
        }
    }

    public boolean allowWhileUsingItem() {
        return this.isEnabled() && !usesFluxKeepSprint() && allowUsingItem.isToggled();
    }

    public boolean allowWhileBackwards() {
        return this.isEnabled() && !usesFluxKeepSprint() && allowBackwards.isToggled();
    }

    public boolean allowWhileSideways() {
        return this.isEnabled() && !usesFluxKeepSprint() && allowSideways.isToggled();
    }
}
