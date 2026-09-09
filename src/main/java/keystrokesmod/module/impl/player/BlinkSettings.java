package keystrokesmod.module.impl.player;

import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;

/** Leader BlinkSettings: configuration applies whether this module is toggled or not. */
public class BlinkSettings extends Module {
    public final ButtonSetting slowRelease;
    public final SliderSetting slowReleaseTime, slowReleaseDelay, maxPacketsPerTick, maxC03PacketsPerTick;
    public BlinkSettings() {
        super("BlinkSettings", category.player);
        registerSetting(slowRelease = new ButtonSetting("SlowRelease", false));
        registerSetting(slowReleaseTime = new SliderSetting("SlowReleaseTime", 0, new String[]{"Start Blink", "Stop Blink"}));
        registerSetting(slowReleaseDelay = new SliderSetting("DelayBetweenSlowRelease", 0, 0, 10, 1));
        registerSetting(maxPacketsPerTick = new SliderSetting("MaxPacketPerTick", 5, 1, 30, 1));
        registerSetting(maxC03PacketsPerTick = new SliderSetting("MaxC03PacketPerTick", 1, 1, 5, 1));
    }
    @Override public void guiUpdate() {
        slowReleaseTime.setVisible(slowRelease.isToggled(), this);
        slowReleaseDelay.setVisible(slowRelease.isToggled(), this);
        maxPacketsPerTick.setVisible(slowRelease.isToggled(), this);
        maxC03PacketsPerTick.setVisible(slowRelease.isToggled(), this);
    }
}
