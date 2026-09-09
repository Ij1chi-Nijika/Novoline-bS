package keystrokesmod.module.impl.combat;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.event.AttackEvent;
import keystrokesmod.event.LeaderUpdateEvent;
import keystrokesmod.event.PreAttackEvent;
import keystrokesmod.utility.Utils;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class SmartAttack extends Module {
    
    private final ButtonSetting onGround = new ButtonSetting("CancelGroundAttack",true);
    private final ButtonSetting onRising = new ButtonSetting("CancelRisingAttack",true);
    private final SliderSetting stopHurtTime = new SliderSetting("StopHurtTime",7,0,9,1);
    private final SliderSetting targetHurtTime = new SliderSetting("TargetHurtTime",0,0,9,1);
    public static final ButtonSetting onKillAura = new ButtonSetting("OnKillAura",true);
    public static final ButtonSetting cancelAuraBlocking = new ButtonSetting("CancelAuraBlocking",true);
    public static boolean shouldCancel;
    public final ButtonSetting hitSelect = new ButtonSetting("HitSelect", false);
    public final SliderSetting hitSelectTicks = new SliderSetting("HitSelectTicks", 5, 1, 10, 1);
    public final SliderSetting hitSelectTimeoutTicks = new SliderSetting("HitSelectTimeoutTicks", 3, 1, 20, 1);
    public SmartAttack() {
        super("SmartAttack", category.combat);
        registerSetting(onGround); registerSetting(onRising); registerSetting(stopHurtTime);
        registerSetting(targetHurtTime); registerSetting(onKillAura); registerSetting(cancelAuraBlocking);
        registerSetting(hitSelect); registerSetting(hitSelectTicks); registerSetting(hitSelectTimeoutTicks);
    }
    @Override public void guiUpdate() {
        cancelAuraBlocking.setVisible(onKillAura.isToggled(), this);
        hitSelectTicks.setVisible(hitSelect.isToggled(), this);
        hitSelectTimeoutTicks.setVisible(hitSelect.isToggled(), this);
    }
    private EntityLivingBase target;
    private EntityLivingBase hitSelectTarget;
    private int hitSelectTimer;
    private boolean hitSelectWaiting;
    private boolean hitSelectHurt;
    @SubscribeEvent
    public void onAttack(AttackEvent event){
        if (isEnabled() && Utils.nullCheck()){
            target = (EntityLivingBase) event.target;
        }
    }
    @SubscribeEvent
    public void onUpdate(LeaderUpdateEvent event) {
        if (isEnabled() && Utils.nullCheck()){
            KillAura killAura = ModuleManager.killAura;
            EntityLivingBase current = killAura != null && killAura.isEnabled() ? KillAura.target : null;
            if (current != null) {
                target = current;
            }

            if (target != null && mc.thePlayer.getDistanceToEntity(target) > 6) {
                target = null;
                hitSelectTarget = null;
                hitSelectWaiting = false;
                hitSelectHurt = false;
                hitSelectTimer = 0;
            }
            if (target == null) {
                shouldCancel = false;
                return;
            }

            if (mc.thePlayer.onGround && onGround.isToggled()) shouldCancel = true;
            if (mc.thePlayer.motionY >= 0 && onRising.isToggled()) shouldCancel = true;
            if (target.hurtTime <= (int) targetHurtTime.getInput()) shouldCancel = false;
            if (target.isBurning()) shouldCancel = false;
            if (mc.thePlayer.hurtTime > (int) stopHurtTime.getInput()) shouldCancel = false;

            if (hitSelect.isToggled()) {
                if (mc.thePlayer.hurtTime == 0 && target != hitSelectTarget) {
                    hitSelectTarget = target;
                    hitSelectWaiting = true;
                    hitSelectTimer = 0;
                    hitSelectHurt = false;
                }
                if (hitSelectWaiting) {
                    shouldCancel = true;
                    if (mc.thePlayer.hurtTime > 0 && !hitSelectHurt) {
                        hitSelectHurt = true;
                        hitSelectTimer = 0;
                    }
                    hitSelectTimer++;
                    int limit = hitSelectHurt ? (int) hitSelectTicks.getInput() : (int) hitSelectTimeoutTicks.getInput();
                    if (hitSelectTimer >= limit) {
                        hitSelectWaiting = false;
                        hitSelectHurt = false;
                        shouldCancel = false;
                    }
                }
            }
        }
    }
    @SubscribeEvent
    public void onLeftClick(PreAttackEvent event) {
        if (shouldCancel) {
            event.setCanceled(true);
        }
    }
}
